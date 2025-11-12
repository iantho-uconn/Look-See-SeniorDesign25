//
//  CameraPreview.swift
//  LookSeeProto
//

import SwiftUI
import AVFoundation
import Combine

final class CameraSessionCoordinator {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "camera.session.queue")

    init() {
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

        // Attach detector output
        Detector.shared.attach(to: session)

        session.commitConfiguration()

        queue.async { [weak self] in
            self?.session.startRunning()
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    static let shared = CameraSessionCoordinator() // keep alive
    @ObservedObject private var detector = Detector.shared

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = CameraPreview.shared.session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        context.coordinator.startObserving(detector: detector, view: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        private var cancellable: AnyCancellable?

        func startObserving(detector: Detector, view: PreviewView) {
            cancellable = detector.$latestDetections
                .receive(on: DispatchQueue.main)
                .sink { obs in
                    view.draw(observations: obs)
                }
        }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        private let overlayLayer = CAShapeLayer()

        override init(frame: CGRect) {
            super.init(frame: frame)
            overlayLayer.strokeColor = UIColor.systemGreen.cgColor
            overlayLayer.fillColor = UIColor.clear.cgColor
            overlayLayer.lineWidth = 2
            layer.addSublayer(overlayLayer)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            overlayLayer.frame = bounds
        }

        func draw(observations: [VNRecognizedObjectObservation]) {
            // Convert normalized boxes to layer-space rects and draw
            let path = UIBezierPath()
            for obs in observations {
                let norm = obs.boundingBox
                // Convert from normalized (origin bottom-left) to view coords
                let rect = VNImageRectForNormalizedRect(norm, Int(bounds.width), Int(bounds.height))
                path.append(UIBezierPath(rect: rect))
            }
            overlayLayer.path = path.cgPath
        }
    }
}
