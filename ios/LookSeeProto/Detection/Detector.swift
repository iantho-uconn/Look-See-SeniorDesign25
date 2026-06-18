//
//  Detector.swift
//  LookSeeProto
//

import Foundation
import AVFoundation
import CoreML
import SwiftUI
import Combine
import CoreImage

struct Detection: Identifiable {
    let id = UUID()

    /// Cluster whose model produced this detection.
    let clusterID: String

    /// Local compiled-model folder/file name used for inference.
    let modelIdentifier: String

    /// Zero-based YOLO/Core ML class index selected from the confidence output.
    let classIndex: Int

    let confidence: Float
    let bbox: CGRect

    /// Temporary compatibility property for existing overlay and API code.
    /// This is NOT a human-readable landmark label.
    var label: String { String(classIndex) }
}

final class Detector: NSObject, ObservableObject {

    @Published var detections: [Detection] = []
    @Published var lastInferenceMS: Double = 0
    @Published var bufferSize: CGSize = .zero

    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()

    private var isAttached = false
    private var throttling = false

    // The model and its identity are read/written only on `queue`.
    private var activeClusterID: String?
    private var activeModelIdentifier: String?

    // Prevent phase-one diagnostics from printing on every camera frame.
    private var lastDetectionLogKey: String?
    private var lastDetectionLogDate = Date.distantPast
    private let detectionLogInterval: TimeInterval = 2.0

    private let inputSize = CGSize(width: 640, height: 640)
    private let confidenceThreshold: Float = 0.25
    private let iouThreshold: Float = 0.45

    override init() {
        super.init()
        observeActiveCluster()
    }

    // MARK: - Observe Active Cluster
    // Whenever ModelSelector switches clusters, load the matching compiled model
    private func observeActiveCluster() {
        Task { @MainActor in
            for await clusterID in ModelSelector.shared.$activeClusterID.values {
                guard let clusterID else { continue }

                // Find the ModelInfo whose clusterID matches
                if case .loaded(let models) = ModelService.shared.state,
                   let match = models.first(where: { $0.clusterID == clusterID }),
                   let compiledURL = match.compiledModelURL {
                    loadModel(from: compiledURL, clusterID: clusterID)
                }
            }
        }
    }

    // MARK: - Load Model from URL
    private func loadModel(from url: URL, clusterID: String) {
        queue.async {
            do {
                let loaded = try MLModel(contentsOf: url)
                let modelIdentifier = url.deletingPathExtension().lastPathComponent

                self.model = loaded
                self.activeClusterID = clusterID
                self.activeModelIdentifier = modelIdentifier
                self.lastDetectionLogKey = nil

                let inputNames = Array(loaded.modelDescription.inputDescriptionsByName.keys).sorted()
                let outputNames = Array(loaded.modelDescription.outputDescriptionsByName.keys).sorted()

                DispatchQueue.main.async {
                    print("")
                    print("✅ [Phase 1] Detector model loaded")
                    print("   clusterID: \(clusterID)")
                    print("   modelIdentifier: \(modelIdentifier)")
                    print("   modelURL: \(url.lastPathComponent)")
                    print("   inputs: \(inputNames)")
                    print("   outputs: \(outputNames)")
                    print("")
                }
            } catch {
                print("❌ Model load error for cluster \(clusterID): \(error)")
            }
        }
    }

    // MARK: - Attach Camera
    func attach(to output: AVCaptureVideoDataOutput) {
        guard !isAttached else { return }
        isAttached = true
        output.setSampleBufferDelegate(self, queue: queue)
    }

    // MARK: - Process Frame
    private func process(pixelBuffer: CVPixelBuffer) {
        guard
            let model = model,
            let clusterID = activeClusterID,
            let modelIdentifier = activeModelIdentifier
        else {
            print("⚠️ No fully identified model loaded yet — skipping frame")
            return
        }
        guard !throttling else { return }
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

            let detections = parseDetections(
                confidenceArray: confidenceArray,
                coordinatesArray: coordinatesArray,
                scale: scale,
                padX: padX,
                padY: padY,
                originalSize: CGSize(width: originalWidth, height: originalHeight),
                clusterID: clusterID,
                modelIdentifier: modelIdentifier
            )

            logPhaseOneDiagnostics(
                detections,
                confidenceShape: confidenceArray.shape.map { $0.intValue },
                coordinatesShape: coordinatesArray.shape.map { $0.intValue }
            )

            let end = CFAbsoluteTimeGetCurrent()
            DispatchQueue.main.async {
                self.detections = detections
                self.lastInferenceMS = (end - start) * 1000
            }

        } catch {
            print("❌ Prediction error:", error)
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) {
            self.throttling = false
        }
    }

    // MARK: - Letterbox
    private func letterbox(pixelBuffer: CVPixelBuffer)
    -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
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
        ciContext.render(composed, to: output!)
        return (output!, scale, padX, padY)
    }

    // MARK: - Parse Detections
    private func parseDetections(confidenceArray: MLMultiArray,
                                 coordinatesArray: MLMultiArray,
                                 scale: CGFloat,
                                 padX: CGFloat,
                                 padY: CGFloat,
                                 originalSize: CGSize,
                                 clusterID: String,
                                 modelIdentifier: String) -> [Detection] {
        var results: [Detection] = []

        let confPtr = confidenceArray.dataPointer.bindMemory(to: Float.self,
                                                             capacity: confidenceArray.count)
        let coordPtr = coordinatesArray.dataPointer.bindMemory(to: Float.self,
                                                               capacity: coordinatesArray.count)

        let numDetections = coordinatesArray.shape[0].intValue
        let numClasses = confidenceArray.shape[1].intValue

        for i in 0..<numDetections {
            var bestScore: Float = 0
            var bestClass = 0

            for c in 0..<numClasses {
                let score = confPtr[i * numClasses + c]
                if score > bestScore {
                    bestScore = score
                    bestClass = c
                }
            }

            if bestScore < confidenceThreshold { continue }

            let cx = CGFloat(coordPtr[i * 4 + 0])
            let cy = CGFloat(coordPtr[i * 4 + 1])
            let w  = CGFloat(coordPtr[i * 4 + 2])
            let h  = CGFloat(coordPtr[i * 4 + 3])

            let x = cx - w / 2
            let y = cy - h / 2
            let rect = CGRect(x: x, y: y, width: w, height: h)

            results.append(
                Detection(
                    clusterID: clusterID,
                    modelIdentifier: modelIdentifier,
                    classIndex: bestClass,
                    confidence: bestScore,
                    bbox: rect
                )
            )
        }

        return results
    }

    // MARK: - Phase One Diagnostics
    private func logPhaseOneDiagnostics(
        _ detections: [Detection],
        confidenceShape: [Int],
        coordinatesShape: [Int]
    ) {
        guard let strongest = detections.max(by: { $0.confidence < $1.confidence }) else {
            return
        }

        let logKey = "\(strongest.clusterID)|\(strongest.modelIdentifier)|\(strongest.classIndex)"
        let now = Date()
        let enoughTimePassed = now.timeIntervalSince(lastDetectionLogDate) >= detectionLogInterval

        guard logKey != lastDetectionLogKey || enoughTimePassed else {
            return
        }

        lastDetectionLogKey = logKey
        lastDetectionLogDate = now

        print("")
        print("🔬 [Phase 1] Raw detection mapping")
        print("   clusterID: \(strongest.clusterID)")
        print("   modelIdentifier: \(strongest.modelIdentifier)")
        print("   classIndex: \(strongest.classIndex)")
        print("   legacy detection.label: '\(strongest.label)'")
        print("   confidence: \(String(format: "%.4f", strongest.confidence))")
        print("   confidence output shape: \(confidenceShape)")
        print("   coordinates output shape: \(coordinatesShape)")
        print("   conclusion: detection.label is the zero-based class index converted to String")
        print("")
    }
}

// MARK: - Camera Delegate
extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        process(pixelBuffer: pb)
    }
}
