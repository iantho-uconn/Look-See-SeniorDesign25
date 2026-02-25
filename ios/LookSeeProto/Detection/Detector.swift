//
//  Detector.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import Foundation
import AVFoundation
import Vision
import CoreML
import Combine

struct Detection: Identifiable {
    let id = UUID()
    let label: String
    let confidence: Float
    let bbox: CGRect  // normalized Vision bbox (origin bottom-left)
}

final class Detector: NSObject, ObservableObject {
    @Published var detections: [Detection] = []
    @Published var isModelLoaded: Bool = false
    @Published var lastInferenceMS: Double = 0

    private var vnModel: VNCoreMLModel!
    private let visionQueue = DispatchQueue(label: "vision.queue")
    private var throttling = false
    private var isAttached = false

    // Debug throttling so we don't spam console
    private var lastDebugPrint: CFAbsoluteTime = 0

    override init() {
        super.init()
        loadModel()
    }

    private func loadModel() {
        do {
            // 1) Try the generated model class for your new model
            if let model = try? best(configuration: MLModelConfiguration()).model {
                vnModel = try VNCoreMLModel(for: model)
                isModelLoaded = true
                print("✅ Loaded VNCoreMLModel from: best.mlpackage (generated class: best)")
                return
            }

            // 2) Fallback: find any compiled model in bundle
            if let url = Bundle.main.urls(forResourcesWithExtension: "mlmodelc", subdirectory: nil)?.first {
                let coreMLModel = try MLModel(contentsOf: url)
                vnModel = try VNCoreMLModel(for: coreMLModel)
                isModelLoaded = true
                print("✅ Loaded VNCoreMLModel from bundle: \(url.lastPathComponent)")
                return
            }

            print("❌ Could not find any mlmodelc in bundle.")
        } catch {
            print("❌ Model load error: \(error)")
        }
    }

    func attach(to videoOutput: AVCaptureVideoDataOutput) {
        guard !isAttached else { return }
        isAttached = true
        videoOutput.setSampleBufferDelegate(self, queue: visionQueue)
    }

    private func debugPrintOncePerSecond(_ msg: String) {
        let now = CFAbsoluteTimeGetCurrent()
        if now - lastDebugPrint >= 1.0 {
            lastDebugPrint = now
            print(msg)
        }
    }

    private func handle(pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) {
        guard vnModel != nil else { return }

        // Throttle a bit so we don’t spam Vision
        if throttling { return }
        throttling = true
        defer {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { self.throttling = false }
        }

        let start = CFAbsoluteTimeGetCurrent()

        let request = VNCoreMLRequest(model: vnModel) { [weak self] req, err in
            guard let self = self else { return }

            if let err = err {
                self.debugPrintOncePerSecond("❌ VNCoreMLRequest error: \(err)")
                return
            }

            // --- DEBUG: what did Vision return? ---
            let resultsCount = req.results?.count ?? 0
            let firstType = req.results?.first.map { String(describing: type(of: $0)) } ?? "nil"
            self.debugPrintOncePerSecond("🔎 Vision results: count=\(resultsCount), firstType=\(firstType)")

            // CASE 1: Vision-native object detections
            var found: [Detection] = []
            if let objs = req.results as? [VNRecognizedObjectObservation] {
                self.debugPrintOncePerSecond("✅ VNRecognizedObjectObservation count=\(objs.count)")

                for obs in objs {
                    let top = obs.labels.first
                    let label = top?.identifier ?? "Object"
                    let conf: Float = top?.confidence ?? 0

                    // TEMP: lower threshold to see anything
                    if conf >= 0.90 {
                        found.append(Detection(label: label, confidence: conf, bbox: obs.boundingBox))
                    }
                }
            } else {
                // CASE 2: Not object observations → your model likely outputs raw tensors
                if let fv = req.results as? [VNCoreMLFeatureValueObservation] {
                    self.debugPrintOncePerSecond("⚠️ VNCoreMLFeatureValueObservation outputs=\(fv.count) (raw tensors; requires custom decode)")
                    if let first = fv.first {
                        self.debugPrintOncePerSecond("   ↳ featureName=\(first.featureName)")
                    }
                } else if let cls = req.results as? [VNClassificationObservation] {
                    self.debugPrintOncePerSecond("⚠️ VNClassificationObservation count=\(cls.count) (classification model, not detector)")
                }
            }

            let end = CFAbsoluteTimeGetCurrent()
            let ms = (end - start) * 1000.0

            DispatchQueue.main.async {
                self.detections = found
                self.lastInferenceMS = ms
            }
        }

        request.imageCropAndScaleOption = .scaleFill

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        do {
            try handler.perform([request])
        } catch {
            debugPrintOncePerSecond("❌ Vision perform error: \(error)")
        }
    }
}

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let orientation: CGImagePropertyOrientation = .right // portrait camera
        handle(pixelBuffer: pb, orientation: orientation)
    }
}
