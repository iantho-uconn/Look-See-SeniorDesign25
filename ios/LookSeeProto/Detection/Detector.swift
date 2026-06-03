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
    let label: String
    let confidence: Float
    let bbox: CGRect
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
                DispatchQueue.main.async {
                    self.model = loaded
                    print("✅ Detector switched to cluster \(clusterID)")
                    print("📥 Inputs:", loaded.modelDescription.inputDescriptionsByName.keys)
                    print("📤 Outputs:", loaded.modelDescription.outputDescriptionsByName.keys)
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
        guard let model = model else {
            print("⚠️ No model loaded yet — skipping frame")
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
                originalSize: CGSize(width: originalWidth, height: originalHeight)
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
                                 originalSize: CGSize) -> [Detection] {
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

            results.append(Detection(label: "\(bestClass)", confidence: bestScore, bbox: rect))
        }

        return results
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
