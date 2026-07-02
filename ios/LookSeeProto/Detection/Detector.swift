//
//  Detector.swift
//  LookSeeProto
//
//  Rewritten to integrate proximity-based detection filtering using LocationManager.
//  Flow: Camera frame → letterbox → CoreML (YOLO) → parse → NMS → proximity filter → smooth → publish
//

import Foundation
import AVFoundation
import CoreML
import SwiftUI
import Combine
import CoreImage
import UIKit
import CoreLocation

// MARK: - Data Models

/// A single detected object from the YOLO model, used to drive the bounding box UI.
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
    var label: String {
        String(classIndex)
    }
}

/// Metadata for one landmark class, parsed from the manifest JSON sent down from AWS.
struct ObjectInfo: Codable {
    let classIndex: Int
    let landmarkId: String
    let label: String
    let shortDescription: String
    let latitude: Double
    let longitude: Double
}

/// Top-level manifest model. AWS sends one of these alongside each compiled .mlmodel.
struct ModelManifest: Codable {
    let schemaVersion: Int
    let clusterId: Int
    let classCount: Int
    let landmarks: [String: ObjectInfo]

    /// Look up landmark metadata by its class index (0-based integer from the model output).
    func landmark(for classIndex: Int) -> ObjectInfo? {
        landmarks.values.first { $0.classIndex == classIndex }
    }
}

// MARK: - Bounding Box Smoother

/// Reduces jitter in bounding boxes by averaging the last N frames.
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

// MARK: - Detector

final class Detector: NSObject, ObservableObject {

    // MARK: Published state
    @Published var detections: [Detection] = []
    @Published var lastInferenceMS: Double = 0
    @Published var bufferSize: CGSize = .zero
    @Published var isPaused: Bool = false
    @Published var classLabels: [String] = []

    // MARK: Configuration
    var dynamicSafeZone: CGRect = .zero
    var manifest: ModelManifest?
    var proximityThresholdMeters: Double = 150
    var userLocation: CLLocation?

    // MARK: Private internals
    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()

    private var isAttached = false
    private var throttling = false

    private var activeClusterID: String?
    private var activeModelVersion: String?
    private var activeModelIdentifier: String?
    private var activeExpectedClassCount: Int?
    private var activeReleaseIdentifier: String?

    private var lastDetectionLogKey: String?
    private var lastDetectionLogDate = Date.distantPast
    private let detectionLogInterval: TimeInterval = 2.0

    private let inputSize = CGSize(width: 640, height: 640)
    private let confidenceThreshold: Float = 0.35
    private let iouThreshold: Float = 0.45

    private var smoothers: [String: BoundingBoxSmoother] = [:]

    // MARK: Init
    override init() {
        super.init()
        // observeActiveRelease() -> Assuming ModelSelector logic exists elsewhere
    }

    // MARK: - Public API
    func resetEngine() {
        DispatchQueue.main.async {
            self.detections.removeAll()
            for smoother in self.smoothers.values { smoother.reset() }
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
    fileprivate func process(pixelBuffer: CVPixelBuffer) {
        guard
            let model = model,
            let clusterID = activeClusterID,
            let modelVersion = activeModelVersion,
            let modelIdentifier = activeModelIdentifier,
            let expectedClassCount = activeExpectedClassCount
        else { return }

        guard !throttling, !isPaused else { return }

        throttling = true
        let start = CFAbsoluteTimeGetCurrent()

        let originalWidth = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let originalHeight = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        DispatchQueue.main.async { self.bufferSize = CGSize(width: originalWidth, height: originalHeight) }

        let (inputBuffer, scale, padX, padY) = letterbox(pixelBuffer: pixelBuffer)

        guard let input = try? MLDictionaryFeatureProvider(dictionary: [
            "image": MLFeatureValue(pixelBuffer: inputBuffer),
            "confidenceThreshold": NSNumber(value: confidenceThreshold),
            "iouThreshold": NSNumber(value: iouThreshold)
        ]) else {
            print("❌ Failed to create input feature provider")
            throttling = false
            return
        }

        do {
            let result = try model.prediction(from: input)

            guard
                let confidenceArray = result.featureValue(for: "confidence")?.multiArrayValue,
                let coordinatesArray = result.featureValue(for: "coordinates")?.multiArrayValue
            else {
                print("❌ Missing model outputs")
                throttling = false
                return
            }

            let confidenceShape = confidenceArray.shape.map { $0.intValue }
            let coordinatesShape = coordinatesArray.shape.map { $0.intValue }

            let newDetections = parseDetections(
                confArray: confidenceArray,
                coordArray: coordinatesArray,
                scale: scale,
                padX: padX,
                padY: padY,
                originalSize: CGSize(width: originalWidth, height: originalHeight),
                clusterID: clusterID,
                modelVersion: modelVersion,
                modelIdentifier: modelIdentifier,
                expectedClassCount: expectedClassCount
            )

            logPhaseTwoDiagnostics(
                newDetections,
                expectedClassCount: expectedClassCount,
                confidenceShape: confidenceShape,
                coordinatesShape: coordinatesShape
            )

            let end = CFAbsoluteTimeGetCurrent()

            DispatchQueue.main.async {
                self.detections = newDetections
                self.lastInferenceMS = (end - start) * 1000
            }
        } catch {
            print("❌ Prediction error: \(error)")
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) {
            self.throttling = false
        }
    }

    // MARK: - Letterbox
    private func letterbox(pixelBuffer: CVPixelBuffer) -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
        let width = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let height = CGFloat(CVPixelBufferGetHeight(pixelBuffer))

        let scale = min(inputSize.width / width, inputSize.height / height)
        let newW = width * scale
        let newH = height * scale

        let padX = (inputSize.width - newW) / 2
        let padY = (inputSize.height - newH) / 2

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let resized = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let black = CIImage(color: .black).cropped(to: CGRect(origin: .zero, size: inputSize))
        let composed = resized
            .transformed(by: CGAffineTransform(translationX: padX, y: padY))
            .composited(over: black)

        var output: CVPixelBuffer?
        CVPixelBufferCreate(nil, Int(inputSize.width), Int(inputSize.height),
                            kCVPixelFormatType_32BGRA, nil, &output)

        if let validOutput = output {
            ciContext.render(composed, to: validOutput)
        }

        return (output!, scale, padX, padY)
    }

    // MARK: - Detection Parsing
    private func parseDetections(
        confArray: MLMultiArray,
        coordArray: MLMultiArray,
        scale: CGFloat,
        padX: CGFloat,
        padY: CGFloat,
        originalSize: CGSize,
        clusterID: String,
        modelVersion: String,
        modelIdentifier: String,
        expectedClassCount: Int
    ) -> [Detection] {

        let confPtr = confArray.dataPointer.bindMemory(to: Float.self, capacity: confArray.count)
        let coordPtr = coordArray.dataPointer.bindMemory(to: Float.self, capacity: coordArray.count)

        let numDetections = coordArray.shape[0].intValue
        let numClasses = confArray.shape[1].intValue
        let classesToInspect = min(numClasses, expectedClassCount)

        let screenWidth = UIScreen.main.bounds.width
        let screenHeight = UIScreen.main.bounds.height
        let screenScale = max(screenWidth / originalSize.width, screenHeight / originalSize.height)
        let offsetX = (originalSize.width * screenScale - screenWidth) / 2
        let offsetY = (originalSize.height * screenScale - screenHeight) / 2

        let activeSafeZone = dynamicSafeZone == .zero ? UIScreen.main.bounds : dynamicSafeZone

        var rawDetections: [Detection] = []

        for i in 0..<numDetections {
            var bestScore: Float = 0
            var bestClass = 0

            for c in 0..<classesToInspect {
                let score = confPtr[i * numClasses + c]
                if score > bestScore { bestScore = score; bestClass = c }
            }
            guard bestScore >= confidenceThreshold else { continue }
            guard bestClass >= 0, bestClass < expectedClassCount else { continue }

            let rawCx = CGFloat(coordPtr[i * 4 + 0])
            let rawCy = CGFloat(coordPtr[i * 4 + 1])
            let rawW  = CGFloat(coordPtr[i * 4 + 2])
            let rawH  = CGFloat(coordPtr[i * 4 + 3])

            let cx640 = rawCx <= 1.0 ? rawCx * inputSize.width  : rawCx
            let cy640 = rawCy <= 1.0 ? rawCy * inputSize.height : rawCy
            let w640  = rawW  <= 1.0 ? rawW  * inputSize.width  : rawW
            let h640  = rawH  <= 1.0 ? rawH  * inputSize.height : rawH

            let finalX = (((cx640 - padX) / scale) * screenScale) - offsetX
            let finalY = (((cy640 - padY) / scale) * screenScale) - offsetY
            let finalW = (w640 / scale) * screenScale
            let finalH = (h640 / scale) * screenScale

            guard finalW > 0, finalH > 0 else { continue }

            let rect = CGRect(x: finalX - finalW / 2, y: finalY - finalH / 2, width: finalW, height: finalH)
            guard rect.intersects(activeSafeZone) else { continue }

            rawDetections.append(
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
        }

        let nmsDetections = nonMaxSuppression(detections: rawDetections, iouThreshold: iouThreshold)
        let nearbyDetections = proximityFilter(nmsDetections)

        let currentLabels = Set(nearbyDetections.map { $0.label })
        smoothers.keys.filter { !currentLabels.contains($0) }.forEach { smoothers.removeValue(forKey: $0) }

        var finalResults: [Detection] = []
        for det in nearbyDetections {
            if smoothers[det.label] == nil { smoothers[det.label] = BoundingBoxSmoother() }
            let smoothedBox = smoothers[det.label]!.smooth(newBox: det.bbox)
            
            finalResults.append(
                Detection(
                    clusterID: det.clusterID,
                    modelVersion: det.modelVersion,
                    modelIdentifier: det.modelIdentifier,
                    classIndex: det.classIndex,
                    classCount: det.classCount,
                    confidence: det.confidence,
                    bbox: smoothedBox
                )
            )
        }

        return finalResults
    }

    // MARK: - NMS
    private func nonMaxSuppression(detections: [Detection], iouThreshold: Float) -> [Detection] {
        var results: [Detection] = []
        var sorted = detections.sorted { $0.confidence > $1.confidence }

        while !sorted.isEmpty {
            let best = sorted.removeFirst()
            results.append(best)

            sorted.removeAll {
                let inter = best.bbox.intersection($0.bbox)
                guard !inter.isNull else { return false }
                let interArea = inter.width * inter.height
                let unionArea = (best.bbox.width * best.bbox.height) + ($0.bbox.width * $0.bbox.height) - interArea
                let iou = Float(interArea / unionArea)
                return iou > iouThreshold
            }
        }
        return results
    }

    // MARK: - Proximity Filter
    private func proximityFilter(_ detections: [Detection]) -> [Detection] {
        guard let manifest = manifest, let userLocation = userLocation else {
            return detections
        }

        return detections.filter { detection in
            guard let object = manifest.landmark(for: detection.classIndex) else {
                return true
            }

            let objectLocation = CLLocation(latitude: object.latitude, longitude: object.longitude)
            let distanceMeters = userLocation.distance(from: objectLocation)

            if distanceMeters > proximityThresholdMeters {
                print("📍 Suppressed '\(detection.displayLabel)' — \(Int(distanceMeters))m away")
            }
            return distanceMeters <= proximityThresholdMeters
        }
    }

    // MARK: - Phase Two Diagnostics
    private func logPhaseTwoDiagnostics(
        _ detections: [Detection],
        expectedClassCount: Int,
        confidenceShape: [Int],
        coordinatesShape: [Int]
    ) {
        guard let strongest = detections.max(by: { $0.confidence < $1.confidence }) else { return }

        let logKey = "\(strongest.releaseIdentifier)|\(strongest.classIndex)"
        let now = Date()
        let enoughTimePassed = now.timeIntervalSince(lastDetectionLogDate) >= detectionLogInterval

        guard logKey != lastDetectionLogKey || enoughTimePassed else { return }

        lastDetectionLogKey = logKey
        lastDetectionLogDate = now

        print("\n🔬 [Phase 2] Release-aware detection")
        print("   clusterID: \(strongest.clusterID)")
        print("   modelVersion: \(strongest.modelVersion)")
        print("   classIndex: \(strongest.classIndex)")
        print("   manifest label: \(strongest.displayLabel)")
        print("   confidence: \(String(format: "%.4f", strongest.confidence))")
        print("")
    }
}

// MARK: - AVFoundation Delegate

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) {
            process(pixelBuffer: pb)
        }
    }
}
