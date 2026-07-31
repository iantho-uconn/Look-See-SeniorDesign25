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
        landmarkEntry?.label ?? "Class \(classIndex)"
    }

    var label: String {
        String(classIndex)
    }
    
    static func == (lhs: Detection, rhs: Detection) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - Bounding Box Smoother

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
    
    // N-FRAME CONFIRMATION: Kills false positives
    private var frameCounters: [String: Int] = [:]
    private let requiredFramesForDetection = 3

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
    
    // Cooldown state for notification debouncing
    private var notificationCooldowns: [String: Date] = [:]
    private let cooldownInterval: TimeInterval = 6.0

    private let inputSize = CGSize(width: 640, height: 640)
    private let confidenceThreshold: Float = 0.80
    private let iouThreshold: Float = 0.45

    private var smoothers: [String: BoundingBoxSmoother] = [:]

    // MARK: Self-contained location tracking
    private let locationManager = CLLocationManager()

    // MARK: Init
    override init() {
        super.init()
        observeActiveCluster()
        startLocationUpdatesIfNeeded()
    }

    private func observeActiveCluster() {
        Task { @MainActor in
            for await release in ModelSelector.shared.$activeRelease.values {
                guard let release else { continue }

                self.activeClusterID = release.clusterID
                self.activeModelVersion = release.modelVersion
                self.activeModelIdentifier = release.modelKey ?? "ota-model"
                self.activeExpectedClassCount = release.classCount
                self.activeReleaseIdentifier = release.releaseIdentifier

                loadModel(from: release.compiledModelURL, clusterID: release.clusterID)
                loadManifest(from: release.manifestFileURL)
            }
        }
    }

    private func loadModel(from url: URL, clusterID: String) {
        queue.async {
            do {
                let config = MLModelConfiguration()
                config.computeUnits = .cpuAndNeuralEngine

                let loaded = try MLModel(contentsOf: url, configuration: config)
                DispatchQueue.main.async {
                    self.model = loaded
                    print("✅ Detector hot-swapped to OTA cluster \(clusterID) (Metal Bypassed)")
                }
            } catch {
                print("❌ OTA Model load error for cluster \(clusterID): \(error)")
            }
        }
    }

    private func loadManifest(from url: URL) {
        do {
            let data = try Data(contentsOf: url)
            let decodedManifest = try JSONDecoder().decode(ClusterLandmarkManifest.self, from: data)
            try decodedManifest.validate()

            DispatchQueue.main.async {
                self.manifest = decodedManifest
                print("✅ Loaded manifest: \(url.lastPathComponent) (\(decodedManifest.landmarks.count) landmarks)")
            }
        } catch {
            print("❌ Failed to load manifest: \(error)")
            DispatchQueue.main.async { self.manifest = nil }
        }
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
        DispatchQueue.main.async {
            self.detections.removeAll()
            self.currentLabel = nil
            self.newlyDetectedLandmark = nil
            for smoother in self.smoothers.values { smoother.reset() }
            self.frameCounters.removeAll()
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
            throttling = false
            return
        }

        do {
            let result = try model.prediction(from: input)

            guard
                let confidenceArray = result.featureValue(for: "confidence")?.multiArrayValue,
                let coordinatesArray = result.featureValue(for: "coordinates")?.multiArrayValue
            else {
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

            logPhaseTwoDiagnostics(newDetections, expectedClassCount: expectedClassCount, confidenceShape: confidenceShape, coordinatesShape: coordinatesShape)

            let end = CFAbsoluteTimeGetCurrent()
            
            // Check cooldowns for the notification stack
            var triggerNotification: Detection? = nil
            if let best = newDetections.max(by: { $0.confidence < $1.confidence }) {
                let now = Date()
                let lastNotified = notificationCooldowns[best.displayLabel] ?? Date.distantPast
                
                // 6 second cooldown before triggering another notification for the same landmark
                if now.timeIntervalSince(lastNotified) > cooldownInterval {
                    notificationCooldowns[best.displayLabel] = now
                    triggerNotification = best
                }
            }

            DispatchQueue.main.async {
                // If hideBoundingBoxes is true, we pass an empty array to visually clear the screen
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

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) {
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

        // --- NMS TOGGLE ---
        // If you switch back to YOLO11 or YOLO10, uncomment the two lines below:
        // let nmsDetections = nonMaxSuppression(detections: rawDetections, iouThreshold: iouThreshold)
        // let nearbyDetections = proximityFilter(nmsDetections)
        
        // For YOLO26 (End-to-End, NMS-free), we pass rawDetections directly:
        let nearbyDetections = proximityFilter(rawDetections)
        // ------------------

        let currentLabels = Set(nearbyDetections.map { $0.label })
        
        // Remove objects that have left the screen from both the smoother and our frame counter
        let lostLabels = smoothers.keys.filter { !currentLabels.contains($0) }
        for label in lostLabels {
            smoothers.removeValue(forKey: label)
            frameCounters.removeValue(forKey: label)
        }

        var finalResults: [Detection] = []
        for det in nearbyDetections {
            let label = det.label
            
            // 1. Increment how many consecutive frames we have seen this object
            let currentCount = (frameCounters[label] ?? 0) + 1
            frameCounters[label] = currentCount

            // 2. Smooth the coordinates (using your existing smoother)
            if smoothers[label] == nil { smoothers[label] = BoundingBoxSmoother() }
            let smoothedBox = smoothers[label]!.smooth(newBox: det.bbox)

            // 3. ONLY pass it to the UI if it has survived 3 frames (kills false positives)
            if currentCount >= requiredFramesForDetection {
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
        }

        return finalResults
    }

    /*
    // --- NMS BACKUP FUNCTION ---
    // Uncomment this entire block if you switch back to a model that requires NMS (like YOLO11s).
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
    */

    private func proximityFilter(_ detections: [Detection]) -> [Detection] {
        guard let manifest = manifest, let userLocation = userLocation else { return detections }

        return detections.filter { detection in
            guard let object = manifest.landmark(for: detection.classIndex) else { return true }
            let objectLocation = CLLocation(latitude: object.latitude, longitude: object.longitude)
            let distanceMeters = userLocation.distance(from: objectLocation)
            let isNearby = distanceMeters <= proximityThresholdMeters
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

