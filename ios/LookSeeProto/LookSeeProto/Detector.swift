//
//  Detector.swift
//  LookSeeProto
//

import Foundation
import Combine
import AVFoundation
import CoreML
import Vision
import os.log

final class Detector: NSObject, ObservableObject {
    static let shared = Detector()

    // MARK: Published state for UI/diagnostics
    @Published var isModelLoaded: Bool = false
    @Published var lastError: String?
    @Published var latestDetections: [VNRecognizedObjectObservation] = []

    private let log = Logger(subsystem: "LookSeeProto", category: "Detector")

    private var visionModel: VNCoreMLModel?
    private var requests: [VNRequest] = []
    private let visionQueue = DispatchQueue(label: "vision.queue")

    // MARK: Model loading
    override init() {
        super.init()
        do {
            guard
                let url =
                    Bundle.main.url(forResource: "CokeCanDetect", withExtension: "mlmodelc")
                    ?? Bundle.main.url(forResource: "CokeCanDetect", withExtension: "mlmodel")
                    ?? Bundle.main.url(forResource: "CokeCanDetect", withExtension: "mlpackage")
            else {
                throw NSError(domain: "LookSeeProto", code: -1,
                              userInfo: [NSLocalizedDescriptionKey: "Model file CokeCanDetect not found in bundle"])
            }

            let mlModel = try MLModel(contentsOf: url)
            let vModel = try VNCoreMLModel(for: mlModel)
            self.visionModel = vModel

            let request = VNCoreMLRequest(model: vModel) { [weak self] req, _ in
                guard let self = self else { return }
                let obs = (req.results as? [VNRecognizedObjectObservation]) ?? []
                DispatchQueue.main.async {
                    self.latestDetections = obs
                }
            }
            request.imageCropAndScaleOption = .scaleFill
            self.requests = [request]

            isModelLoaded = true
            log.info("CoreML model loaded ✅")
        } catch {
            lastError = error.localizedDescription
            log.error("Model load failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: Capture output handling
    func attach(to session: AVCaptureSession) {
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                Int(kCVPixelFormatType_32BGRA)
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: visionQueue)

        if session.canAddOutput(output) {
            session.addOutput(output)
        }

        if let conn = output.connection(with: .video) {
            // Keep your iOS 16-friendly orientation line; it’s fine to ship with the deprecation warning.
            if conn.isVideoOrientationSupported { conn.videoOrientation = .portrait }
        }
    }
}

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isModelLoaded,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up)
        do {
            try handler.perform(self.requests)
        } catch {
            DispatchQueue.main.async { self.lastError = error.localizedDescription }
        }
    }
}
