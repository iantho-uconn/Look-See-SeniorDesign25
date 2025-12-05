//
//  CameraPreview.swift
//  LookSeeProto
//

import SwiftUI
import AVFoundation
import Vision
import CoreML

final class CameraSessionCoordinator: NSObject {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "camera.session.queue")
    private let videoOut = AVCaptureVideoDataOutput()
    private let previewLayer = AVCaptureVideoPreviewLayer()
    private let overlayLayer = CAShapeLayer()

    // The detector is injected so we can publish results to the UI
    private let detector: Detector

    init(detector: Detector) {
        self.detector = detector
        super.init()

        session.beginConfiguration()
        session.sessionPreset = .high

        // Input: back camera
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        // Output: video frames -> Detector
        videoOut.alwaysDiscardsLateVideoFrames = true
        videoOut.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(videoOut) {
            session.addOutput(videoOut)
        }

        // Orient to portrait (fallback to deprecated API if needed)
        if let conn = videoOut.connection(with: .video) {
            if #available(iOS 17.0, *) {
                if conn.isVideoRotationAngleSupported(90) { try? conn.setVideoRotationAngle(90) }
            } else if conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
        }

        session.commitConfiguration()

        queue.async { [weak self] in self?.session.startRunning() }

        // Overlay layer
        overlayLayer.strokeColor = UIColor.systemGreen.cgColor
        overlayLayer.fillColor = UIColor.clear.cgColor
        overlayLayer.lineWidth = 3
        overlayLayer.lineJoin = .round

        // Subscribe to detector’s observations and redraw boxes
        detector.$observations
            .receive(on: DispatchQueue.main)
            .sink { [weak self] obs in
                self?.render(observations: obs)
            }
            .store(in: &cancellables)
    }

    // Called by PreviewView to attach layers
    func attach(to view: UIView) {
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)

        overlayLayer.frame = view.bounds
        view.layer.addSublayer(overlayLayer)
    }

    // Draw detection boxes
    private func render(observations: [VNRecognizedObjectObservation]) {
        guard let layer = overlayLayer.presentation() ?? overlayLayer else { return }
        let path = UIBezierPath()
        for o in observations {
            let rect = VNImageRectForNormalizedRect(o.boundingBox, Int(layer.bounds.width), Int(layer.bounds.height))
            path.append(UIBezierPath(rect: rect))
        }
        overlayLayer.path = path.cgPath
    }

    // Combine retention
    private var cancellables: Set<AnyCancellable> = []
}

extension CameraSessionCoordinator: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        detector.process(sampleBuffer: sampleBuffer)
    }
}

struct CameraPreview: UIViewRepresentable {
    // Keep the coordinator alive for the app lifetime
    static var shared: CameraSessionCoordinator?

    let detector: Detector

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        if CameraPreview.shared == nil {
            CameraPreview.shared = CameraSessionCoordinator(detector: detector)
        }
        CameraPreview.shared?.attach(to: v)
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
