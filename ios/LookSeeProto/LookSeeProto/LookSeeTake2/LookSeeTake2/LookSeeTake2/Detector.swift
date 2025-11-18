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
    /// Vision’s normalized bounding box (origin at bottom-left, [0,1] coords)
    let bbox: CGRect
}

/// Manages Vision + CoreML against camera frames.
final class Detector: NSObject, ObservableObject {
    @Published var detections: [Detection] = []
    @Published var isModelLoaded: Bool = false
    @Published var lastInferenceMS: Double = 0

    private var vnModel: VNCoreMLModel!
    private let visionQueue = DispatchQueue(label: "vision.queue")
    private var throttling = false

    override init() {
        super.init()
        loadModel()
    }

    /// Try to load the generated class from your .mlpackage first, then fall back to bundle search.
    private func loadModel() {
        do {
            // 1) Try the auto-generated class (rename "CokeCanDetect" if Xcode generated a different name)
            if let model = try? CokeCanDetect(configuration: MLModelConfiguration()).model {
                vnModel = try VNCoreMLModel(for: model)
                isModelLoaded = true
                print("✅ Loaded VNCoreMLModel from CokeCanDetect class")
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

    // Attach to camera output
    func attach(to videoOutput: AVCaptureVideoDataOutput) {
        videoOutput.setSampleBufferDelegate(self, queue: visionQueue)
    }

    private func handle(pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) {
        guard vnModel != nil else { return }

        // Throttle a bit so we don’t spam Vision (smooth preview + lower battery)
        if throttling { return }
        throttling = true
        defer {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { self.throttling = false }
        }

        let request = VNCoreMLRequest(model: vnModel) { [weak self] req, _ in
            guard let self = self else { return }
            let start = CFAbsoluteTimeGetCurrent()

            var found: [Detection] = []
            if let objs = req.results as? [VNRecognizedObjectObservation] {
                for obs in objs {
                    let top = obs.labels.first
                    let label = top?.identifier ?? "Object"
                    let conf: Float = top?.confidence ?? 0
                    // Only keep likely coke cans (tune this as you gather more data)
                    if conf >= 0.45 { // early filter
                        found.append(Detection(label: label, confidence: conf, bbox: obs.boundingBox))
                    }
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
            print("Vision perform error: \(error)")
        }
    }
}

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // Map current device orientation to Vision orientation
        let orientation: CGImagePropertyOrientation = .right // camera in portrait
        handle(pixelBuffer: pb, orientation: orientation)
    }
}
