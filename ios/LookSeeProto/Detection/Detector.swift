//
//  Detector.swift
//  LookSeeTake2
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
    ///
    /// CameraPreview still uses this during the current migration pass. It
    /// intentionally remains the numeric class-index String until CameraPreview
    /// is replaced with direct local-manifest resolution in the next pass.
    var label: String {
        String(classIndex)
    }
}

/// Metadata for one landmark class, parsed from the manifest JSON sent down from AWS.
/// The manifest tells us what each class index means and where the landmark physically is.
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
    /// We use this during detection parsing to map a class index → coordinates + label.
    func landmark(for classIndex: Int) -> ObjectInfo? {
        landmarks.values.first { $0.classIndex == classIndex }
    }
}

// MARK: - Bounding Box Smoother

/// Reduces jitter in bounding boxes by averaging the last N frames.
/// Each detected class gets its own smoother instance so they don't interfere.
class BoundingBoxSmoother {
    private var history: [CGRect] = []
    private let maxFrames = 4 // number of frames to average over

    /// Call this every frame with the latest raw box. Returns the smoothed box.
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

    // MARK: Published state (consumed by the UI layer)

    @Published var detections: [Detection] = []         // final filtered + smoothed detections
    @Published var lastInferenceMS: Double = 0          // how long the last prediction took
    @Published var bufferSize: CGSize = .zero           // raw camera buffer dimensions
    @Published var isPaused: Bool = false               // lets the UI freeze detection
    @Published var classLabels: [String] = []           // label strings for each class index

    // MARK: Configuration

    /// The region of screen space inside which detections are considered valid.
    /// Defaults to full screen. Can be narrowed to a viewfinder crop area.
    var dynamicSafeZone: CGRect = .zero

    /// The manifest loaded from AWS alongside the model.
    /// Setting this enables proximity filtering. If nil, all detections pass through.
    var manifest: ModelManifest?

    /// Distance threshold in meters. Detections whose landmark is farther than this
    /// from the user are suppressed. Tune per-deployment as needed.
    var proximityThresholdMeters: Double = 150

    /// Injected from LocationManager. Updated externally whenever GPS updates.
    /// Kept as CLLocation so we can call .distance(from:) directly.
    var userLocation: CLLocation?

    // MARK: Private internals

    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()

    private var isAttached = false      // prevents double-attaching to AVCapture
    private var throttling = false      // rate-limits inference to ~30fps max

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

    private let inputSize = CGSize(width: 640, height: 640) // YOLO expects 640×640
    private let confidenceThreshold: Float = 0.35           // minimum score to consider a detection
    private let iouThreshold: Float = 0.45                  // overlap threshold for NMS

    /// One smoother per class label. Created lazily, removed when a label disappears.
    private var smoothers: [String: BoundingBoxSmoother] = [:]

    // MARK: Init

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

    // MARK: - Public API

    /// Clears all current detections and resets smoothing history.
    /// Call this when switching contexts (e.g. navigating away and back).
    func resetEngine() {
        DispatchQueue.main.async {
            self.detections.removeAll()
            for smoother in self.smoothers.values { smoother.reset() }
        }
    }

    /// Attaches this detector as the sample buffer delegate on the given capture output.
    /// Safe to call multiple times — only attaches once.
    func attach(to output: AVCaptureVideoDataOutput) {
        guard !isAttached else { return }
        isAttached = true

        // Lock to portrait so coordinate math stays consistent
        if let connection = output.connection(with: .video),
           connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        output.setSampleBufferDelegate(self, queue: queue)
    }

    // MARK: - Model Loading

    /// Observes ModelSelector for changes to the active cluster and reloads the ML model.
    /// Runs as an async stream so it picks up every future switch automatically.
    private func observeActiveCluster() {
        Task { @MainActor in
            for await clusterID in ModelSelector.shared.$activeClusterID.values {
                guard
                    let clusterID,
                    case .loaded(let models) = ModelService.shared.state,
                    let match = models.first(where: { $0.clusterID == clusterID }),
                    let url = match.compiledModelURL
                else { continue }

                loadModel(from: url, clusterID: clusterID)
            }
        }
    }

    /// Loads a compiled .mlmodel from disk on the inference queue, then publishes the result.
    private func loadModel(from url: URL, clusterID: String) {
        queue.async {
            do {
                let loaded = try MLModel(contentsOf: url)
                // Extract class label strings from the model's own metadata
                let labels = loaded.modelDescription.classLabels as? [String] ?? []

                DispatchQueue.main.async {
                    self.model = loaded
                    self.classLabels = labels
                    self.smoothers.removeAll() // reset smoothers — new model, new classes
                    print("✅ Detector switched to cluster \(clusterID) with \(labels.count) labels.")
                }
            } catch {
                print("❌ Model load error: \(error)")
            }
        }
    }

    // MARK: - Inference Pipeline

    /// Entry point for each camera frame. Runs on the YOLO queue.
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
        guard let model = model, !throttling, !isPaused else { return }

        throttling = true
        let start = CFAbsoluteTimeGetCurrent()

        // Capture raw buffer dimensions for coordinate math later
        let originalWidth = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let originalHeight = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        DispatchQueue.main.async { self.bufferSize = CGSize(width: originalWidth, height: originalHeight) }

        // Step 1: Resize + pad the frame to 640×640 without distortion
        let (inputBuffer, scale, padX, padY) = letterbox(pixelBuffer: pixelBuffer)

        // Step 2: Build the CoreML input feature dictionary
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

        // Step 3: Run inference
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

        // Throttle to ~30fps — prevents the queue from flooding on fast devices
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { self.throttling = false }
    }
    

    // MARK: - Letterboxing

    /// Scales the input frame to fit inside 640×640 while preserving aspect ratio,
    /// padding the remainder with black. Returns the padded buffer plus the transform
    /// parameters needed to map 640-space coordinates back to screen space.
    private func letterbox(pixelBuffer: CVPixelBuffer) -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
        let width = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let height = CGFloat(CVPixelBufferGetHeight(pixelBuffer))

        // Uniform scale factor — whichever axis hits the edge of 640 first
        let scale = min(inputSize.width / width, inputSize.height / height)
        let newW = width * scale
        let newH = height * scale

        // How much black padding was added on each side
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
        let composed = resized
            .transformed(by: CGAffineTransform(translationX: padX, y: padY))
            .composited(over: black)

        var output: CVPixelBuffer?
        CVPixelBufferCreate(nil, Int(inputSize.width), Int(inputSize.height),
                            kCVPixelFormatType_32BGRA, nil, &output)
        ciContext.render(composed, to: output!)
        return (output!, scale, padX, padY)
    }

    // MARK: - Detection Parsing

    /// Converts raw CoreML output arrays into screen-space Detection structs.
    /// Pipeline inside: threshold filter → NMS → proximity filter → smooth
    private func parseDetections(
        confArray: MLMultiArray,
        coordArray: MLMultiArray,
        scale: CGFloat,
        padX: CGFloat,
        padY: CGFloat,
        originalSize: CGSize
    ) -> [Detection] {

        let confPtr = confArray.dataPointer.bindMemory(to: Float.self, capacity: confArray.count)
        let coordPtr = coordArray.dataPointer.bindMemory(to: Float.self, capacity: coordArray.count)

        let numDetections = coordArray.shape[0].intValue
        let numClasses = confArray.shape[1].intValue

        // Compute screen→buffer scaling so boxes land correctly on the camera preview
        let screenWidth = UIScreen.main.bounds.width
        let screenHeight = UIScreen.main.bounds.height
        let screenScale = max(screenWidth / originalSize.width, screenHeight / originalSize.height)
        let offsetX = (originalSize.width * screenScale - screenWidth) / 2
        let offsetY = (originalSize.height * screenScale - screenHeight) / 2

        // Use the dynamic safe zone if set, otherwise accept detections anywhere on screen
        let activeSafeZone = dynamicSafeZone == .zero ? UIScreen.main.bounds : dynamicSafeZone

        // --- Step A: Score each candidate and convert to screen-space rects ---
        var rawDetections: [Detection] = []

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
            guard bestScore >= confidenceThreshold else { continue }

            // Coordinates may arrive normalised (0–1) or in pixel space; handle both
            let rawCx = CGFloat(coordPtr[i * 4 + 0])
            let rawCy = CGFloat(coordPtr[i * 4 + 1])
            let rawW  = CGFloat(coordPtr[i * 4 + 2])
            let rawH  = CGFloat(coordPtr[i * 4 + 3])

            let cx640 = rawCx <= 1.0 ? rawCx * inputSize.width  : rawCx
            let cy640 = rawCy <= 1.0 ? rawCy * inputSize.height : rawCy
            let w640  = rawW  <= 1.0 ? rawW  * inputSize.width  : rawW
            let h640  = rawH  <= 1.0 ? rawH  * inputSize.height : rawH

            // Reverse the letterbox transform: strip padding, undo scale, apply screen scale
            let finalX = (((cx640 - padX) / scale) * screenScale) - offsetX
            let finalY = (((cy640 - padY) / scale) * screenScale) - offsetY
            let finalW = (w640 / scale) * screenScale
            let finalH = (h640 / scale) * screenScale
            guard finalW > 0, finalH > 0 else { continue }

            let rect = CGRect(x: finalX - finalW / 2, y: finalY - finalH / 2,
                              width: finalW, height: finalH)

            // Discard boxes that fall completely outside the viewfinder area
            guard rect.intersects(activeSafeZone) else { continue }

            let className = bestClass < classLabels.count ? classLabels[bestClass] : "Class \(bestClass)"
            rawDetections.append(Detection(label: className, confidence: bestScore, bbox: rect))
        }

        // --- Step B: NMS — remove duplicate boxes for the same object ---
        let nmsDetections = nonMaxSuppression(detections: rawDetections, iouThreshold: iouThreshold)

        // --- Step C: Proximity filter — suppress detections too far from the user ---
        let nearbyDetections = proximityFilter(nmsDetections)

        // --- Step D: Smooth bounding boxes across frames to reduce jitter ---
        // Remove smoothers for labels no longer visible
        let currentLabels = Set(nearbyDetections.map { $0.label })
        smoothers.keys.filter { !currentLabels.contains($0) }.forEach { smoothers.removeValue(forKey: $0) }

        var finalResults: [Detection] = []
        for det in nearbyDetections {
            if smoothers[det.label] == nil { smoothers[det.label] = BoundingBoxSmoother() }
            let smoothedBox = smoothers[det.label]!.smooth(newBox: det.bbox)
            finalResults.append(Detection(label: det.label, confidence: det.confidence, bbox: smoothedBox))
        }

        return finalResults
    }

    // MARK: - NMS

    /// Non-Maximum Suppression: when multiple boxes overlap heavily, keep only
    /// the most confident one. IoU (Intersection over Union) measures overlap —
    /// 0 means no overlap, 1 means identical boxes. Boxes above iouThreshold are dropped.
    private func nonMaxSuppression(detections: [Detection], iouThreshold: Float) -> [Detection] {
        var results: [Detection] = []
        var sorted = detections.sorted { $0.confidence > $1.confidence }

        while !sorted.isEmpty {
            let best = sorted.removeFirst()     // highest confidence box wins
            results.append(best)

            // Remove everything that overlaps too much with this winner
            sorted.removeAll {
                let inter = best.bbox.intersection($0.bbox)
                guard !inter.isNull else { return false }
                let interArea = inter.width * inter.height
                let unionArea = (best.bbox.width * best.bbox.height)
                             + ($0.bbox.width * $0.bbox.height)
                             - interArea
                let iou = Float(interArea / unionArea)
                return iou > iouThreshold
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


// MARK: - Proximity Filter

/// Compares each detected landmark against the user's current GPS location.
/// Detections whose real-world object is farther than proximityThresholdMeters are suppressed.
///
/// Falls back to allowing all detections if:
///   - manifest hasn't loaded yet (model just switched)
///   - GPS hasn't produced a fix yet
///   - the detected label isn't in the manifest (unknown class)
private func proximityFilter(_ detections: [Detection]) -> [Detection] {
    guard let manifest = manifest else {
        // Manifest not yet loaded — can't filter, let everything through
        return detections
    }
    guard let userLocation = userLocation else {
        // No GPS fix yet — let everything through rather than suppressing valid detections
        return detections
    }

    return detections.filter { detection in
        // Find this label's entry in the manifest to get its coordinates
        guard let object = manifest.landmarks.values
            .first(where: { $0.label == detection.label })
        else {
            // Label not found in manifest — unknown class, let it through
            return true
        }

        let objectLocation = CLLocation(
            latitude: object.latitude,
            longitude: object.longitude
        )
        let distanceMeters = userLocation.distance(from: objectLocation)

        if distanceMeters > proximityThresholdMeters {
            print("📍 Suppressed '\(detection.label)' — \(Int(distanceMeters))m away (threshold: \(Int(proximityThresholdMeters))m)")
        }
        return distanceMeters <= proximityThresholdMeters
    }
}
}
extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Called by AVFoundation for every camera frame on the YOLO queue.
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) {
            process(pixelBuffer: pb)
        }
    }
}
