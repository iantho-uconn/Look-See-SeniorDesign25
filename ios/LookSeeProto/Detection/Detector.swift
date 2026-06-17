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
import Vision

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
    private func observeActiveCluster() {
        Task { @MainActor in
            for await clusterID in ModelSelector.shared.$activeClusterID.values {
                guard let clusterID else { continue }

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
        // Ensure a real model is loaded before processing
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
            // Run the actual YOLO model prediction
            let result = try model.prediction(from: input)

            guard let confidenceArray = result.featureValue(for: "confidence")?.multiArrayValue,
                  let coordinatesArray = result.featureValue(for: "coordinates")?.multiArrayValue else {
                print("❌ Missing model outputs")
                throttling = false
                return
            }

            // Extract bounding boxes from CoreML output
            var finalDetections = parseDetections(
                confidenceArray: confidenceArray,
                coordinatesArray: coordinatesArray,
                scale: scale,
                padX: padX,
                padY: padY,
                originalSize: CGSize(width: originalWidth, height: originalHeight)
            )

            // --- ON-DEVICE OCR TEXT RECOGNITION ---
            // If YOLO found an object, scan the frame for text
            if !finalDetections.isEmpty {
                let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
                if let cgImage = self.ciContext.createCGImage(ciImage, from: ciImage.extent) {
                    
                    let request = VNRecognizeTextRequest { (request, error) in
                        guard let observations = request.results as? [VNRecognizedTextObservation], error == nil else { return }
                        
                        // Combine text and replace spaces with underscores
                        let extractedText = observations.compactMap { $0.topCandidates(1).first?.string }
                            .joined(separator: "_")
                            .replacingOccurrences(of: " ", with: "_")
                        
                        if !extractedText.isEmpty {
                            for i in 0..<finalDetections.count {
                                let oldLabel = finalDetections[i].label
                                finalDetections[i] = Detection(
                                    label: "\(oldLabel)_\(extractedText)",
                                    confidence: finalDetections[i].confidence,
                                    bbox: finalDetections[i].bbox
                                )
                            }
                            print("📝 Vision OCR Extracted: \(extractedText)")
                        }
                    }
                    
                    request.recognitionLevel = .accurate
                    
                    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
                    try? handler.perform([request])
                }
            }
            // -------------------------------------------

            let end = CFAbsoluteTimeGetCurrent()
            DispatchQueue.main.async {
                self.detections = finalDetections
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

        let confPtr = confidenceArray.dataPointer.bindMemory(to: Float.self, capacity: confidenceArray.count)
        let coordPtr = coordinatesArray.dataPointer.bindMemory(to: Float.self, capacity: coordinatesArray.count)

        let numDetections = coordinatesArray.shape[0].intValue
        let numClasses = confidenceArray.shape[1].intValue

        // Optional: Add your YOLO class names here if you want clean text on screen
        let classNames = ["structure", "sign", "plaque", "statue"]

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
            
            // Apply scale normalizations
            let correctedX = (x - padX) / scale
            let correctedY = (y - padY) / scale
            let correctedW = w / scale
            let correctedH = h / scale
            
            let normalizedRect = CGRect(
                x: correctedX / originalSize.width,
                y: correctedY / originalSize.height,
                width: correctedW / originalSize.width,
                height: correctedH / originalSize.height
            )

            let cleanLabel = classNames.indices.contains(bestClass) ? classNames[bestClass] : "\(bestClass)"
            results.append(Detection(label: cleanLabel, confidence: bestScore, bbox: normalizedRect))
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
