//
//  Detector.swift
//  LookSeeProto
//
//  OTA-Enabled: Actively listens to ModelSelector to hot-swap CoreML models
//  and landmark manifests, driven by ModelSelector.activeRelease.
//  Metal-Bypass: Restricts compute units to CPU & Neural Engine to prevent Signal 9 crashes.
//  Self-contained location: runs its own CLLocationManager for proximity filtering,
//  independent of the app's main LocationManager.
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

struct Detection: Identifiable, Equatable {
    let id = UUID()
    let clusterID: String
    let modelVersion: String
    let modelIdentifier: String
    let classIndex: Int
    let classCount: Int
    let confidence: Float
    let bbox: CGRect
    let overrideLabel: String?

    init(
        clusterID: String,
        modelVersion: String,
        modelIdentifier: String,
        classIndex: Int,
        classCount: Int,
        confidence: Float,
        bbox: CGRect,
        overrideLabel: String? = nil
    ) {
        self.clusterID = clusterID
        self.modelVersion = modelVersion
        self.modelIdentifier = modelIdentifier
        self.classIndex = classIndex
        self.classCount = classCount
        self.confidence = confidence
        self.bbox = bbox
        self.overrideLabel = overrideLabel
    }

    var releaseIdentifier: String {
        "\(clusterID)|\(modelVersion)"
    }

    var landmarkEntry: LandmarkManifestEntry? {
        LandmarkManifestStore.shared.resolve(
            clusterId: clusterID,
            trainingRunId: modelVersion,
            classIndex: classIndex
        )
    }

    var displayLabel: String {
        if let overrideLabel, !overrideLabel.isEmpty {
            return overrideLabel
        }
        return landmarkEntry?.label ?? "Class \(classIndex)"
    }

    var label: String {
        String(classIndex)
    }

    static func == (lhs: Detection, rhs: Detection) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - Bounding Box Smoother

private final class BoundingBoxSmoother {
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
    @Published var newlyDetectedLandmark: Detection? = nil
    @Published var currentLabel: String? = nil
    @Published var lastInferenceMS: Double = 0
    @Published var bufferSize: CGSize = .zero
    @Published var isPaused: Bool = false
    @Published var classLabels: [String] = []

    // NEW: Controls whether bounding boxes are visually passed to the UI
    @Published var hideBoundingBoxes: Bool = false

    // MARK: Configuration
    var dynamicSafeZone: CGRect = .zero
    var manifest: ClusterLandmarkManifest?
    var proximityThresholdMeters: Double = 150

    var userLocation: CLLocation?

    // MARK: Private internals
    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()

    // N-frame confirmation and box smoothing from the latest detector.
    private var frameCounters: [String: Int] = [:]
    private let requiredFramesForDetection = 3

    private var isAttached = false
    private var throttling = false

    private var activeClusterID: String?
    private var activeModelVersion: String?
    private var activeModelIdentifier: String?
    private var activeExpectedClassCount: Int?
    private var activeClassLabels: [String] = []
    private var activeReleaseIdentifier: String?

    private var lastDetectionLogKey: String?
    private var lastDetectionLogDate = Date.distantPast
    private let detectionLogInterval: TimeInterval = 2.0

    // Cooldown state for notification debouncing
    private var notificationCooldowns: [String: Date] = [:]
    private let cooldownInterval: TimeInterval = 6.0

    private let inputSize = CGSize(width: 640, height: 640)

    /// UI-facing value used by the testing slider. Inference reads the
    /// queue-confined copy below so a drag cannot race a camera frame.
    @Published var confidenceThreshold: Float = 0.65 {
        didSet {
            let clamped = min(max(confidenceThreshold, 0.01), 0.99)
            queue.async { [weak self] in
                guard let self else { return }
                self.inferenceConfidenceThreshold = clamped
                self.resetTrackingState()
                self.clearPublishedDetectionState()
            }
        }
    }
    private var inferenceConfidenceThreshold: Float = 0.65
    private let iouThreshold: Float = 0.45

    private var smoothers: [String: BoundingBoxSmoother] = [:]

    // MARK: Self-contained location tracking
    private let locationManager = CLLocationManager()

    // MARK: Init
    override init() {
        super.init()
        observeActiveModel()
        startLocationUpdatesIfNeeded()
    }

    private func observeActiveModel() {
        Task { @MainActor in
            for await release in ModelSelector.shared.$activeRelease.values {
                if let release {
                    self.loadModel(for: release)
                } else {
                    self.unloadModel()
                }
            }
        }
    }

    /// Loads the candidate completely before replacing the active inference
    /// state. This prevents frames from being processed by the old model with
    /// the new model's class count or labels during a hot swap.
    private func loadModel(for release: ActiveModelRelease) {
        queue.async {
            do {
                let config = MLModelConfiguration()
                config.computeUnits = .cpuAndNeuralEngine

                let loaded = try MLModel(
                    contentsOf: release.compiledModelURL,
                    configuration: config
                )
                let inferredClassCount = release.classCount > 0
                    ? release.classCount
                    : Self.inferClassCount(from: loaded)
                let loadedManifest = try Self.decodeManifest(
                    from: release.manifestFileURL
                )

                DispatchQueue.main.async {
                    // A second selection may have happened while Core ML was
                    // loading. Never install a stale result over that choice.
                    guard ModelSelector.shared.activeRelease?.id == release.id else {
                        return
                    }

                    self.queue.async {
                        self.model = loaded
                        self.activeClusterID = release.clusterID
                        self.activeModelVersion = release.modelVersion
                        self.activeModelIdentifier = release.modelKey ?? "ota-model"
                        self.activeExpectedClassCount = inferredClassCount
                        self.activeClassLabels = release.classLabels
                        self.activeReleaseIdentifier = release.releaseIdentifier
                        self.manifest = loadedManifest
                        self.resetTrackingState()

                        DispatchQueue.main.async {
                            self.classLabels = release.classLabels
                            self.detections.removeAll()
                            self.currentLabel = nil
                            self.newlyDetectedLandmark = nil
                            print(
                                "✅ Detector hot-swapped to \(release.displayName) " +
                                "(\(inferredClassCount) classes, Metal bypassed)"
                            )
                        }
                    }
                }
            } catch {
                print(
                    "❌ Model load error for \(release.displayName): \(error)"
                )
            }
        }
    }

    private func unloadModel() {
        queue.async {
            self.model = nil
            self.activeClusterID = nil
            self.activeModelVersion = nil
            self.activeModelIdentifier = nil
            self.activeExpectedClassCount = nil
            self.activeClassLabels = []
            self.activeReleaseIdentifier = nil
            self.manifest = nil
            self.resetTrackingState()

            DispatchQueue.main.async {
                self.classLabels = []
                self.detections.removeAll()
                self.currentLabel = nil
                self.newlyDetectedLandmark = nil
            }
        }
    }

    private static func inferClassCount(from model: MLModel) -> Int {
        let outputs = model.modelDescription.outputDescriptionsByName

        if let confidenceShape = outputs["confidence"]?
            .multiArrayConstraint?.shape,
           let last = confidenceShape.last?.intValue,
           last > 0 {
            return last
        }

        if let shape = outputs.values.first?
            .multiArrayConstraint?.shape.map({ $0.intValue }),
           shape.count >= 2 {
            let dimensions = shape.dropFirst()

            if let channels = dimensions.first(where: {
                $0 >= 5 && $0 <= 512
            }) {
                // Raw YOLO tensors contain x, y, width, height, then classes.
                // End-to-end tensors use six values but carry a class index;
                // a large upper bound lets that validated model output pass.
                return channels == 6 ? 10_000 : channels - 4
            }
        }

        return 10_000
    }

    private static func decodeManifest(
        from url: URL
    ) throws -> ClusterLandmarkManifest {
        let data = try Data(contentsOf: url)
        let decodedManifest = try JSONDecoder().decode(
            ClusterLandmarkManifest.self,
            from: data
        )
        try decodedManifest.validate()
        return decodedManifest
    }

    private func startLocationUpdatesIfNeeded() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        locationManager.distanceFilter = 15 // meters

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.startUpdatingLocation()
        default:
            break
        }
    }

    func resetEngine() {
        queue.async {
            self.resetTrackingState()
            self.clearPublishedDetectionState()
        }
    }

    private func resetTrackingState() {
        for smoother in smoothers.values {
            smoother.reset()
        }
        smoothers.removeAll()
        frameCounters.removeAll()
        notificationCooldowns.removeAll()
        lastDetectionLogKey = nil
        lastDetectionLogDate = .distantPast
    }

    private func clearPublishedDetectionState() {
        DispatchQueue.main.async {
            self.detections.removeAll()
            self.currentLabel = nil
            self.newlyDetectedLandmark = nil
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

    fileprivate func process(pixelBuffer: CVPixelBuffer) {
        guard
            let model = model,
            let clusterID = activeClusterID,
            let modelVersion = activeModelVersion,
            let modelIdentifier = activeModelIdentifier,
            let expectedClassCount = activeExpectedClassCount
        else { return }

        let classLabels = activeClassLabels
        let frameConfidenceThreshold = inferenceConfidenceThreshold

        guard !throttling, !isPaused else { return }

        throttling = true
        let start = CFAbsoluteTimeGetCurrent()

        let originalWidth = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let originalHeight = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        DispatchQueue.main.async { self.bufferSize = CGSize(width: originalWidth, height: originalHeight) }

        let (inputBuffer, scale, padX, padY) = letterbox(pixelBuffer: pixelBuffer)

        // 🚀 DYNAMIC INPUT MAPPER
        // Checks the model description to only pass exactly what the model expects
        var inputDict: [String: Any] = ["image": MLFeatureValue(pixelBuffer: inputBuffer)]
        let inputDescriptions = model.modelDescription.inputDescriptionsByName

        if inputDescriptions.keys.contains("iouThreshold") {
            inputDict["iouThreshold"] = NSNumber(value: iouThreshold)
        }
        if inputDescriptions.keys.contains("confidenceThreshold") {
            // Keep the Core ML NMS gate and our parser gate on the exact same
            // per-frame slider snapshot. This was hard-coded in one branch,
            // which made slider changes appear to do nothing.
            inputDict["confidenceThreshold"] = NSNumber(
                value: frameConfidenceThreshold
            )
        }

        guard let input = try? MLDictionaryFeatureProvider(dictionary: inputDict) else {
            print("❌ MLDictionaryFeatureProvider Failed. Check model inputs: \(inputDescriptions.keys)")
            throttling = false
            return
        }

        do {
            let result = try model.prediction(from: input)
            let outputKeys = result.featureNames

            var newDetections: [Detection] = []
            var diagConfShape: [Int] = []
            var diagCoordShape: [Int] = []

            // 🚀 DYNAMIC OUTPUT PARSER
            if outputKeys.contains("confidence") && outputKeys.contains("coordinates") {
                // 🍎 OLD YOLO STYLE (Separate Confidence & Coordinate Arrays)
                let conf = result.featureValue(for: "confidence")!.multiArrayValue!
                let coord = result.featureValue(for: "coordinates")!.multiArrayValue!

                diagConfShape = conf.shape.map { $0.intValue }
                diagCoordShape = coord.shape.map { $0.intValue }

                newDetections = parseDetections(
                    confArray: conf,
                    coordArray: coord,
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
                    expectedClassCount: expectedClassCount,
                    classLabels: classLabels,
                    confidenceThreshold: frameConfidenceThreshold
                )

            } else if let firstKey = outputKeys.first, let combinedArray = result.featureValue(for: firstKey)?.multiArrayValue {
                // 🚀 NEW YOLO26 E2E STYLE (Single Combined Array)
                diagConfShape = combinedArray.shape.map { $0.intValue }

                newDetections = parseEndToEndDetections(
                    combinedArray: combinedArray,
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
                    expectedClassCount: expectedClassCount,
                    classLabels: classLabels,
                    confidenceThreshold: frameConfidenceThreshold
                )
            } else {
                print("❌ Unknown CoreML Output Configuration: \(outputKeys)")
            }

            logPhaseTwoDiagnostics(newDetections, expectedClassCount: expectedClassCount, confidenceShape: diagConfShape, coordinatesShape: diagCoordShape)

            let end = CFAbsoluteTimeGetCurrent()

            // Check cooldowns for the notification stack
            var triggerNotification: Detection? = nil
            if let best = newDetections.max(by: { $0.confidence < $1.confidence }) {
                let now = Date()
                let lastNotified = notificationCooldowns[best.displayLabel] ?? Date.distantPast

                if now.timeIntervalSince(lastNotified) > cooldownInterval {
                    notificationCooldowns[best.displayLabel] = now
                    triggerNotification = best
                }
            }

            DispatchQueue.main.async {
                self.detections = self.hideBoundingBoxes ? [] : newDetections
                self.lastInferenceMS = (end - start) * 1000
                self.currentLabel = newDetections.max(by: { $0.confidence < $1.confidence })?.displayLabel

                if let trigger = triggerNotification {
                    self.newlyDetectedLandmark = trigger
                }
            }
        } catch {
            print("❌ Prediction error: \(error)")
        }

        queue.asyncAfter(deadline: .now() + 0.03) {
            self.throttling = false
        }
    }

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
        CVPixelBufferCreate(nil, Int(inputSize.width), Int(inputSize.height), kCVPixelFormatType_32BGRA, nil, &output)

        if let validOutput = output {
            ciContext.render(composed, to: validOutput)
        }

        return (output!, scale, padX, padY)
    }

    // 🚀 NEW: END-TO-END PARSER FOR YOLO26
    private func parseEndToEndDetections(
        combinedArray: MLMultiArray,
        scale: CGFloat,
        padX: CGFloat,
        padY: CGFloat,
        originalSize: CGSize,
        clusterID: String,
        modelVersion: String,
        modelIdentifier: String,
        expectedClassCount: Int,
        classLabels: [String],
        confidenceThreshold: Float
    ) -> [Detection] {

        let ptr = combinedArray.dataPointer.bindMemory(to: Float.self, capacity: combinedArray.count)
        let shape = combinedArray.shape.map { $0.intValue }

        let numBoxes = shape.count == 3 ? shape[1] : shape[0]
        let boxSize = shape.last ?? 6

        guard boxSize == 6 else {
            print("❌ Unknown E2E shape configuration: \(shape)")
            return []
        }

        let screenWidth = UIScreen.main.bounds.width
        let screenHeight = UIScreen.main.bounds.height
        let screenScale = max(screenWidth / originalSize.width, screenHeight / originalSize.height)
        let offsetX = (originalSize.width * screenScale - screenWidth) / 2
        let offsetY = (originalSize.height * screenScale - screenHeight) / 2
        let activeSafeZone = dynamicSafeZone == .zero ? UIScreen.main.bounds : dynamicSafeZone

        var rawDetections: [Detection] = []

        for i in 0..<numBoxes {
            let offset = i * 6
            let rawX1 = CGFloat(ptr[offset + 0])
            let rawY1 = CGFloat(ptr[offset + 1])
            let rawX2 = CGFloat(ptr[offset + 2])
            let rawY2 = CGFloat(ptr[offset + 3])
            let score = ptr[offset + 4]
            let classIdx = Int(ptr[offset + 5])

            guard score >= confidenceThreshold else { continue }
            guard classIdx >= 0, classIdx < expectedClassCount else { continue }

            let x1 = rawX1 <= 1.0 ? rawX1 * inputSize.width : rawX1
            let y1 = rawY1 <= 1.0 ? rawY1 * inputSize.height : rawY1
            let x2 = rawX2 <= 1.0 ? rawX2 * inputSize.width : rawX2
            let y2 = rawY2 <= 1.0 ? rawY2 * inputSize.height : rawY2

            let w640 = (x2 - x1)
            let h640 = (y2 - y1)
            let cx640 = x1 + (w640 / 2)
            let cy640 = y1 + (h640 / 2)

            let finalX = (((cx640 - padX) / scale) * screenScale) - offsetX
            let finalY = (((cy640 - padY) / scale) * screenScale) - offsetY
            let finalW = (w640 / scale) * screenScale
            let finalH = (h640 / scale) * screenScale

            guard finalW > 0, finalH > 0 else { continue }

            let rect = CGRect(x: finalX - finalW / 2, y: finalY - finalH / 2, width: finalW, height: finalH)
            guard rect.intersects(activeSafeZone) else { continue }

            rawDetections.append(
                Detection(clusterID: clusterID, modelVersion: modelVersion, modelIdentifier: modelIdentifier, classIndex: classIdx, classCount: expectedClassCount, confidence: score, bbox: rect, overrideLabel: classLabels[safe: classIdx])
            )
        }

        return finalizeDetections(rawDetections)
    }

    // LEGACY: OLD PARSER FOR YOLOv8 / YOLOv10
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
        expectedClassCount: Int,
        classLabels: [String],
        confidenceThreshold: Float
    ) -> [Detection] {
        let startTime = CFAbsoluteTimeGetCurrent()

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
                Detection(clusterID: clusterID, modelVersion: modelVersion, modelIdentifier: modelIdentifier, classIndex: bestClass, classCount: expectedClassCount, confidence: bestScore, bbox: rect, overrideLabel: classLabels[safe: bestClass])
            )
        }

        let finalResults = finalizeDetections(rawDetections)
        let elapsedTime = (CFAbsoluteTimeGetCurrent() - startTime) * 1000
        let formattedTime = elapsedTime.formatted(.number.precision(.fractionLength(2)))
        print("⏱ Parsing took \(formattedTime) ms | required frames: \(requiredFramesForDetection)" , "Avg: 6 frames")
        return finalResults
    }

    /// Applies the latest detector's proximity filtering, three-frame
    /// confirmation, and four-frame moving-average box smoothing. Only the
    /// strongest box for a landmark advances its tracker once per frame.
    private func finalizeDetections(_ rawDetections: [Detection]) -> [Detection] {
        let nearbyDetections = proximityFilter(rawDetections)
        var strongestByKey: [String: Detection] = [:]

        for detection in nearbyDetections {
            let key = trackingKey(for: detection)
            if detection.confidence > (strongestByKey[key]?.confidence ?? -Float.infinity) {
                strongestByKey[key] = detection
            }
        }

        let currentKeys = Set(strongestByKey.keys)
        let lostKeys = smoothers.keys.filter { !currentKeys.contains($0) }
        for lostKey in lostKeys {
            smoothers.removeValue(forKey: lostKey)
            frameCounters.removeValue(forKey: lostKey)
        }

        return strongestByKey
            .sorted { $0.value.classIndex < $1.value.classIndex }
            .compactMap { key, detection in
                let currentCount = (frameCounters[key] ?? 0) + 1
                frameCounters[key] = currentCount

                let smoother = smoothers[key] ?? BoundingBoxSmoother()
                smoothers[key] = smoother
                let smoothedBox = smoother.smooth(newBox: detection.bbox)

                guard currentCount >= requiredFramesForDetection else {
                    return nil
                }

                return Detection(
                    clusterID: detection.clusterID,
                    modelVersion: detection.modelVersion,
                    modelIdentifier: detection.modelIdentifier,
                    classIndex: detection.classIndex,
                    classCount: detection.classCount,
                    confidence: detection.confidence,
                    bbox: smoothedBox,
                    overrideLabel: detection.overrideLabel
                )
            }
    }

    private func trackingKey(for detection: Detection) -> String {
        "\(detection.releaseIdentifier)|\(detection.classIndex)"
    }

    private func proximityFilter(_ detections: [Detection]) -> [Detection] {
        guard let manifest = manifest, let userLocation = userLocation else { return detections }

        return detections.filter { detection in
            guard let object = manifest.landmark(for: detection.classIndex) else { return true }
            let objectLocation = CLLocation(latitude: object.latitude, longitude: object.longitude)
            let distanceMeters = userLocation.distance(from: objectLocation)
            let isNearby = distanceMeters <= proximityThresholdMeters

            if !isNearby {
                print(object.label, "suppressed because object is not nearby (distance: \(distanceMeters)m)")
            }

            return isNearby
        }
    }

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

private extension Array {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) {
            process(pixelBuffer: pb)
        }
    }
}

extension Detector: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last, last.horizontalAccuracy > 0, last.horizontalAccuracy <= 100 else { return }
        queue.async { self.userLocation = last }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("❌ Detector location error:", error.localizedDescription)
    }
}
