//
//  Detector.swift
//  LookSeeProto
//
//  Release-aware detector.
//
//  Each detection carries the exact cluster ID + model version that produced it,
//  so its zero-based class index can be resolved through the matching local
//  landmark manifest.
//

import Foundation
import AVFoundation
import CoreML
import SwiftUI
import Combine
import CoreImage
import UIKit

struct Detection: Identifiable {
    let id = UUID()

    /// Cluster whose model produced this detection.
    let clusterID: String

    /// Immutable model-release version. This currently matches trainingRunId.
    let modelVersion: String

    /// Local compiled-model folder/file name used for inference.
    let modelIdentifier: String

    /// Zero-based YOLO/Core ML class index.
    let classIndex: Int

    /// Number of valid classes declared by the matching landmark manifest.
    let classCount: Int

    let confidence: Float
    let bbox: CGRect

    var releaseIdentifier: String {
        "\(clusterID)|\(modelVersion)"
    }

    /// Local metadata for this detection, when the matching manifest is loaded.
    var landmarkEntry: LandmarkManifestEntry? {
        LandmarkManifestStore.shared.resolve(
            clusterId: clusterID,
            trainingRunId: modelVersion,
            classIndex: classIndex
        )
    }

    /// Human-readable label for overlays and diagnostics.
    var displayLabel: String {
        landmarkEntry?.label ?? "Class \(classIndex)"
    }

    /// Temporary compatibility property.
    ///
    /// CameraPreview still uses this during the current migration pass. It
    /// intentionally remains the numeric class-index String until CameraPreview
    /// is replaced with direct local-manifest resolution in the next pass.
    var label: String {
        String(classIndex)
    }
}

class BoundingBoxSmoother {
    private var history: [CGRect] = []
    private let maxFrames = 4
    
    func smooth(newBox: CGRect) -> CGRect {
        history.append(newBox)
        if history.count > maxFrames { history.removeFirst() }
        
        let count = CGFloat(history.count)
        let avgX = history.map { $0.minX }.reduce(0, +) / count
        let avgY = history.map { $0.minY }.reduce(0, +) / count
        let avgW = history.map { $0.width }.reduce(0, +) / count
        let avgH = history.map { $0.height }.reduce(0, +) / count
        
        return CGRect(x: avgX, y: avgY, width: avgW, height: avgH)
    }
    func reset() { history.removeAll() }
}

final class Detector: NSObject, ObservableObject {
    @Published var detections: [Detection] = []
    @Published var lastInferenceMS: Double = 0
    @Published var bufferSize: CGSize = .zero
    
    @Published var isPaused: Bool = false
    @Published var classLabels: [String] = [] // Dynamic model labels
    var dynamicSafeZone: CGRect = .zero

    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()
    
    private var isAttached = false
    private var throttling = false

    // The model and its release identity are read/written only on `queue`.
    private var activeClusterID: String?
    private var activeModelVersion: String?
    private var activeModelIdentifier: String?
    private var activeExpectedClassCount: Int?
    private var activeReleaseIdentifier: String?

    // Prevent diagnostics from printing on every camera frame.
    private var lastDetectionLogKey: String?
    private var lastDetectionLogDate = Date.distantPast
    private let detectionLogInterval: TimeInterval = 2.0

    private let inputSize = CGSize(width: 640, height: 640)
    private let confidenceThreshold: Float = 0.35 // Sensitive enough to catch the ceiling light
    private let iouThreshold: Float = 0.45
    
    private var smoothers: [String: BoundingBoxSmoother] = [:]

    override init() {
        super.init()
        observeActiveRelease()
    }

    // MARK: - Observe Active Release

    /// Whenever ModelSelector changes cluster OR version, load that exact
    /// compiled release. This fixes the old behavior where a new version of the
    /// same cluster did not necessarily reload Detector.
    private func observeActiveRelease() {
        Task { @MainActor [weak self] in
            guard let self else { return }

            for await release in ModelSelector.shared.$activeRelease.values {
                guard let release else {
                    self.clearActiveModel()
                    continue
                }

                self.loadModel(for: release)
            }
        }
    }

    // MARK: - Load / Clear Model

    private func loadModel(for release: ActiveModelRelease) {
        queue.async { [weak self] in
            guard let self else { return }

            // Ignore repeat emissions for the exact same immutable release.
            guard self.activeReleaseIdentifier != release.releaseIdentifier else {
                return
            }

            do {
                let loaded = try MLModel(
                    contentsOf: release.compiledModelURL
                )

                let modelIdentifier = release.compiledModelURL
                    .deletingPathExtension()
                    .lastPathComponent

                self.model = loaded
                self.activeClusterID = release.clusterID
                self.activeModelVersion = release.modelVersion
                self.activeModelIdentifier = modelIdentifier
                self.activeExpectedClassCount = release.classCount
                self.activeReleaseIdentifier = release.releaseIdentifier
                self.lastDetectionLogKey = nil

                let inputNames = Array(
                    loaded.modelDescription
                        .inputDescriptionsByName
                        .keys
                ).sorted()

                let outputNames = Array(
                    loaded.modelDescription
                        .outputDescriptionsByName
                        .keys
                ).sorted()

                DispatchQueue.main.async {
                    self.detections = []

                    print("")
                    print("✅ [Phase 2] Detector release loaded")
                    print("   release: \(release.releaseIdentifier)")
                    print("   clusterID: \(release.clusterID)")
                    print("   modelVersion: \(release.modelVersion)")
                    print("   expectedClassCount: \(release.classCount)")
                    print("   modelIdentifier: \(modelIdentifier)")
                    print(
                        "   modelURL: " +
                        release.compiledModelURL.lastPathComponent
                    )
                    print("   inputs: \(inputNames)")
                    print("   outputs: \(outputNames)")
                    print("")
                }
            } catch {
                DispatchQueue.main.async {
                    print(
                        "❌ Model load error for release " +
                        "\(release.releaseIdentifier): \(error)"
                    )
                }
            }
        }
    }

    private func clearActiveModel() {
        queue.async { [weak self] in
            guard let self else { return }

            self.model = nil
            self.activeClusterID = nil
            self.activeModelVersion = nil
            self.activeModelIdentifier = nil
            self.activeExpectedClassCount = nil
            self.activeReleaseIdentifier = nil
            self.lastDetectionLogKey = nil

            DispatchQueue.main.async {
                self.detections = []
            }
        }
    }

    // MARK: - Attach Camera

    func resetEngine() {
        DispatchQueue.main.async {
            self.detections.removeAll()
            for smoother in self.smoothers.values { smoother.reset() }
        }
    }

    private func observeActiveCluster() {
        Task { @MainActor in
            for await clusterID in ModelSelector.shared.$activeClusterID.values {
                guard let clusterID, case .loaded(let models) = ModelService.shared.state,
                      let match = models.first(where: { $0.clusterID == clusterID }),
                      let url = match.compiledModelURL else { continue }
                loadModel(from: url, clusterID: clusterID)
            }
        }
    }

    private func loadModel(from url: URL, clusterID: String) {
        queue.async {
            do {
                let loaded = try MLModel(contentsOf: url)
                let labels = loaded.modelDescription.classLabels as? [String] ?? []
                
                DispatchQueue.main.async {
                    self.model = loaded
                    self.classLabels = labels
                    self.smoothers.removeAll()
                    print("✅ Detector switched to cluster \(clusterID) with \(labels.count) labels.")
                }
            } catch { print("❌ Model load error: \(error)") }
        }
    }

    func attach(to output: AVCaptureVideoDataOutput) {
        guard !isAttached else { return }
        isAttached = true
        if let connection = output.connection(with: .video), connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        output.setSampleBufferDelegate(self, queue: queue)
    }

    // MARK: - Process Frame

    private func process(pixelBuffer: CVPixelBuffer) {
        guard
            let model,
            let clusterID = activeClusterID,
            let modelVersion = activeModelVersion,
            let modelIdentifier = activeModelIdentifier,
            let expectedClassCount = activeExpectedClassCount
        else {
            return
        }

        guard !throttling else { return }

    private func process(pixelBuffer: CVPixelBuffer) {
        guard let model = model, !throttling else { return }
        if isPaused { return }
        
        throttling = true
        let start = CFAbsoluteTimeGetCurrent()

        let originalWidth = CGFloat(
            CVPixelBufferGetWidth(pixelBuffer)
        )
        let originalHeight = CGFloat(
            CVPixelBufferGetHeight(pixelBuffer)
        )

        DispatchQueue.main.async {
            self.bufferSize = CGSize(
                width: originalWidth,
                height: originalHeight
            )
        }

        let (inputBuffer, scale, padX, padY) =
            letterbox(pixelBuffer: pixelBuffer)

        guard let input = try? MLDictionaryFeatureProvider(
            dictionary: [
                "image": MLFeatureValue(
                    pixelBuffer: inputBuffer
                ),
                "confidenceThreshold": NSNumber(
                    value: confidenceThreshold
                ),
                "iouThreshold": NSNumber(
                    value: iouThreshold
                )
            ]
        ) else {
            print("❌ Failed to create input feature provider")
            throttling = false
            return
        }

        do {
            let result = try model.prediction(from: input)

            guard
                let confidenceArray = result
                    .featureValue(for: "confidence")?
                    .multiArrayValue,
                let coordinatesArray = result
                    .featureValue(for: "coordinates")?
                    .multiArrayValue
            else {
                print("❌ Missing model outputs")
        guard let input = try? MLDictionaryFeatureProvider(dictionary: [
            "image": MLFeatureValue(pixelBuffer: inputBuffer),
            "confidenceThreshold": NSNumber(value: confidenceThreshold),
            "iouThreshold": NSNumber(value: iouThreshold)
        ]) else { throttling = false; return }

        do {
            let result = try model.prediction(from: input)
            guard let confArray = result.featureValue(for: "confidence")?.multiArrayValue,
                  let coordArray = result.featureValue(for: "coordinates")?.multiArrayValue else {
                throttling = false
                return
            }

            let confidenceShape = confidenceArray.shape.map {
                $0.intValue
            }
            let coordinatesShape = coordinatesArray.shape.map {
                $0.intValue
            }

            let detections = parseDetections(
                confidenceArray: confidenceArray,
                coordinatesArray: coordinatesArray,
              
            let newDetections = parseDetections(
                confArray: confArray,
                coordArray: coordArray,
                scale: scale,
                padX: padX,
                padY: padY,
                originalSize: CGSize(
                    width: originalWidth,
                    height: originalHeight
                ),
                clusterID: clusterID,
                modelVersion: modelVersion,
                modelIdentifier: modelIdentifier,
                expectedClassCount: expectedClassCount
            )

            logPhaseTwoDiagnostics(
                detections,
                expectedClassCount: expectedClassCount,
                confidenceShape: confidenceShape,
                coordinatesShape: coordinatesShape
            )

            let end = CFAbsoluteTimeGetCurrent()

            DispatchQueue.main.async {
                self.detections = newDetections
                self.lastInferenceMS = (end - start) * 1000
            }
        } catch { print("Prediction error: \(error)") }

        } catch {
            print("❌ Prediction error: \(error)")
        }

        DispatchQueue.main.asyncAfter(
            deadline: .now() + 0.03
        ) {
            self.throttling = false
        }
    }

    // MARK: - Letterbox

    private func letterbox(
        pixelBuffer: CVPixelBuffer
    ) -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
        let width = CGFloat(
            CVPixelBufferGetWidth(pixelBuffer)
        )
        let height = CGFloat(
            CVPixelBufferGetHeight(pixelBuffer)
        )

        let scale = min(
            inputSize.width / width,
            inputSize.height / height
        )

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { self.throttling = false }
    }

    private func letterbox(pixelBuffer: CVPixelBuffer) -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
        let width = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let height = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let scale = min(inputSize.width / width, inputSize.height / height)
        let newW = width * scale
        let newH = height * scale
        let padX = (inputSize.width - newW) / 2
        let padY = (inputSize.height - newH) / 2

        let ciImage = CIImage(
            cvPixelBuffer: pixelBuffer
        )

        let resized = ciImage.transformed(
            by: CGAffineTransform(
                scaleX: scale,
                y: scale
            )
        )

        let black = CIImage(color: .black)
            .cropped(
                to: CGRect(
                    origin: .zero,
                    size: inputSize
                )
            )

        let composed = resized
            .transformed(
                by: CGAffineTransform(
                    translationX: padX,
                    y: padY
                )
            )
            .composited(over: black)

        var output: CVPixelBuffer?

        CVPixelBufferCreate(
            nil,
            Int(inputSize.width),
            Int(inputSize.height),
            kCVPixelFormatType_32BGRA,
            nil,
            &output
        )

        guard let output else {
            fatalError("Failed to create detector input pixel buffer")
        }

        ciContext.render(composed, to: output)

        return (output, scale, padX, padY)
    }

    // MARK: - Parse Detections

    private func parseDetections(
        confidenceArray: MLMultiArray,
        coordinatesArray: MLMultiArray,
        scale: CGFloat,
        padX: CGFloat,
        padY: CGFloat,
        originalSize: CGSize,
        clusterID: String,
        modelVersion: String,
        modelIdentifier: String,
        expectedClassCount: Int
    ) -> [Detection] {
        guard
            confidenceArray.shape.count >= 2,
            coordinatesArray.shape.count >= 2,
            expectedClassCount > 0
        else {
            return []
        }

        var results: [Detection] = []

        let confPtr = confidenceArray.dataPointer
            .bindMemory(
                to: Float.self,
                capacity: confidenceArray.count
            )

        let coordPtr = coordinatesArray.dataPointer
            .bindMemory(
                to: Float.self,
                capacity: coordinatesArray.count
            )

        let coordinateDetectionCount =
            coordinatesArray.shape[0].intValue

        let confidenceDetectionCount =
            confidenceArray.shape[0].intValue

        let numDetections = min(
            coordinateDetectionCount,
            confidenceDetectionCount
        )

        let outputClassCount =
            confidenceArray.shape[1].intValue

        // The manifest is the authoritative class map for this release.
        //
        // The current test Core ML export reports 80 confidence columns while
        // its matching training data and manifest declare 18 classes. Until the
        // conversion export is regenerated and inspected, never allow Detector
        // to emit an index outside the manifest's valid 0..<classCount range.
        let classesToInspect = min(
            outputClassCount,
            expectedClassCount
        )

        guard numDetections > 0, classesToInspect > 0 else {
            return []
        }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let resized = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let black = CIImage(color: .black).cropped(to: CGRect(origin: .zero, size: inputSize))
        let composed = resized.transformed(by: CGAffineTransform(translationX: padX, y: padY)).composited(over: black)

        var output: CVPixelBuffer?
        CVPixelBufferCreate(nil, Int(inputSize.width), Int(inputSize.height), kCVPixelFormatType_32BGRA, nil, &output)
        ciContext.render(composed, to: output!)
        return (output!, scale, padX, padY)
    }

    private func parseDetections(confArray: MLMultiArray, coordArray: MLMultiArray, scale: CGFloat, padX: CGFloat, padY: CGFloat, originalSize: CGSize) -> [Detection] {
        var rawDetections: [Detection] = []
        let confPtr = confArray.dataPointer.bindMemory(to: Float.self, capacity: confArray.count)
        let coordPtr = coordArray.dataPointer.bindMemory(to: Float.self, capacity: coordArray.count)

        let numDetections = coordArray.shape[0].intValue
        let numClasses = confArray.shape[1].intValue

        let screenWidth = UIScreen.main.bounds.width
        let screenHeight = UIScreen.main.bounds.height
        let screenScale = max(screenWidth / originalSize.width, screenHeight / originalSize.height)
        let offsetX = (originalSize.width * screenScale - screenWidth) / 2
        let offsetY = (originalSize.height * screenScale - screenHeight) / 2

        let activeSafeZone = dynamicSafeZone == .zero ? UIScreen.main.bounds : dynamicSafeZone

        for detectionIndex in 0..<numDetections {
            var bestScore: Float = 0
            var bestClass = 0

            for classIndex in 0..<classesToInspect {
                let score = confPtr[
                    detectionIndex * outputClassCount + classIndex
                ]

                if score > bestScore {
                    bestScore = score
                    bestClass = classIndex
                }
            }

            guard bestScore >= confidenceThreshold else {
                continue
            }

            guard bestClass >= 0,
                  bestClass < expectedClassCount else {
                print(
                    "⚠️ Dropping out-of-range detection index " +
                    "\(bestClass) for release " +
                    "\(clusterID)|\(modelVersion), " +
                    "classCount=\(expectedClassCount)"
                )
                continue
            }

            let coordinateBase = detectionIndex * 4

            guard coordinateBase + 3 < coordinatesArray.count else {
                continue
            }

            let cx = CGFloat(
                coordPtr[coordinateBase + 0]
            )
            let cy = CGFloat(
                coordPtr[coordinateBase + 1]
            )
            let width = CGFloat(
                coordPtr[coordinateBase + 2]
            )
            let height = CGFloat(
                coordPtr[coordinateBase + 3]
            )

            let x = cx - width / 2
            let y = cy - height / 2

            let rect = CGRect(
                x: x,
                y: y,
                width: width,
                height: height
            )

            results.append(
                Detection(
                    clusterID: clusterID,
                    modelVersion: modelVersion,
                    modelIdentifier: modelIdentifier,
                    classIndex: bestClass,
                    classCount: expectedClassCount,
                    confidence: bestScore,
                    bbox: rect
                )
            )
            for c in 0..<numClasses {
                let score = confPtr[i * numClasses + c]
                if score > bestScore { bestScore = score; bestClass = c }
            }
            if bestScore < confidenceThreshold { continue }

            let rawCx = CGFloat(coordPtr[i * 4 + 0])
            let rawCy = CGFloat(coordPtr[i * 4 + 1])
            let rawW = CGFloat(coordPtr[i * 4 + 2])
            let rawH = CGFloat(coordPtr[i * 4 + 3])
            
            let cx640 = rawCx <= 1.0 ? rawCx * inputSize.width : rawCx
            let cy640 = rawCy <= 1.0 ? rawCy * inputSize.height : rawCy
            let w640 = rawW <= 1.0 ? rawW * inputSize.width : rawW
            let h640 = rawH <= 1.0 ? rawH * inputSize.height : rawH

            let finalX = (((cx640 - padX) / scale) * screenScale) - offsetX
            let finalY = (((cy640 - padY) / scale) * screenScale) - offsetY
            let finalW = (w640 / scale) * screenScale
            let finalH = (h640 / scale) * screenScale
            if finalW <= 0 || finalH <= 0 { continue }
            
            let rect = CGRect(x: finalX - finalW/2, y: finalY - finalH/2, width: finalW, height: finalH)
            
            // Re-enabled intersection check so ceiling light detects properly
            if rect.intersects(activeSafeZone) {
                let className = bestClass < classLabels.count ? classLabels[bestClass] : "Class \(bestClass)"
                rawDetections.append(Detection(label: className, confidence: bestScore, bbox: rect))
            }
        }

        let nmsDetections = nonMaxSuppression(detections: rawDetections, iouThreshold: iouThreshold)
        
        var currentLabels = Set<String>()
        for det in nmsDetections { currentLabels.insert(det.label) }
        for key in smoothers.keys { if !currentLabels.contains(key) { smoothers.removeValue(forKey: key) } }
        
        var finalResults: [Detection] = []
        for det in nmsDetections {
            if smoothers[det.label] == nil { smoothers[det.label] = BoundingBoxSmoother() }
            finalResults.append(Detection(label: det.label, confidence: det.confidence, bbox: smoothers[det.label]!.smooth(newBox: det.bbox)))
        }
        return finalResults
    }

    private func nonMaxSuppression(detections: [Detection], iouThreshold: Float) -> [Detection] {
        var results: [Detection] = []
        var sorted = detections.sorted { $0.confidence > $1.confidence }
        while !sorted.isEmpty {
            let best = sorted.removeFirst()
            results.append(best)
            sorted.removeAll {
                let inter = best.bbox.intersection($0.bbox)
                let iou = inter.isNull ? 0 : (inter.width * inter.height) / ((best.bbox.width * best.bbox.height) + ($0.bbox.width * $0.bbox.height) - (inter.width * inter.height))
                return iou > CGFloat(iouThreshold)
            }
        }
        return results
    }

    // MARK: - Phase Two Diagnostics

    private func logPhaseTwoDiagnostics(
        _ detections: [Detection],
        expectedClassCount: Int,
        confidenceShape: [Int],
        coordinatesShape: [Int]
    ) {
        guard let strongest = detections.max(
            by: { $0.confidence < $1.confidence }
        ) else {
            return
        }

        let logKey = [
            strongest.releaseIdentifier,
            String(strongest.classIndex)
        ].joined(separator: "|")

        let now = Date()
        let enoughTimePassed =
            now.timeIntervalSince(lastDetectionLogDate) >=
            detectionLogInterval

        guard logKey != lastDetectionLogKey ||
              enoughTimePassed else {
            return
        }

        lastDetectionLogKey = logKey
        lastDetectionLogDate = now

        let outputClassCount =
            confidenceShape.count >= 2
            ? confidenceShape[1]
            : -1

        print("")
        print("🔬 [Phase 2] Release-aware detection")
        print("   clusterID: \(strongest.clusterID)")
        print("   modelVersion: \(strongest.modelVersion)")
        print(
            "   release: \(strongest.releaseIdentifier)"
        )
        print(
            "   modelIdentifier: \(strongest.modelIdentifier)"
        )
        print("   classIndex: \(strongest.classIndex)")
        print("   manifest label: \(strongest.displayLabel)")
        print(
            "   confidence: " +
            String(
                format: "%.4f",
                strongest.confidence
            )
        )
        print(
            "   expected manifest classCount: " +
            "\(expectedClassCount)"
        )
        print(
            "   confidence output shape: " +
            "\(confidenceShape)"
        )
        print(
            "   coordinates output shape: " +
            "\(coordinatesShape)"
        )

        if outputClassCount != expectedClassCount {
            print(
                "⚠️ Core ML confidence width " +
                "\(outputClassCount) does not match manifest " +
                "classCount \(expectedClassCount). " +
                "Detection is restricted to indexes " +
                "0..<\(expectedClassCount)."
            )
        } else {
            print(
                "✅ Core ML confidence width matches " +
                "manifest classCount"
            )
        }

        if strongest.landmarkEntry == nil {
            print(
                "⚠️ No local manifest entry resolved for this " +
                "detection"
            )
        } else {
            print(
                "✅ Detection resolved through the matching " +
                "local manifest"
            )
        }

        print("")
    }
}

// MARK: - Camera Delegate

extension Detector:
    AVCaptureVideoDataOutputSampleBufferDelegate {

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer =
            CMSampleBufferGetImageBuffer(sampleBuffer)
        else {
            return
        }

        process(pixelBuffer: pixelBuffer)
extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) { process(pixelBuffer: pb) }
    }
}
