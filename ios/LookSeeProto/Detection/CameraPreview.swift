//
//  CameraPreview.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import SwiftUI
import AVFoundation
import Vision
import Combine

/// Keeps the AVCaptureSession alive and exposes the video output so Detector can attach.
final class CameraSessionCoordinator {
    let session = AVCaptureSession()
    let videoOutput = AVCaptureVideoDataOutput()

    init() {
        session.beginConfiguration()
        session.sessionPreset = .high

        // Input: back wide camera
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        // Output for frames
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        // Portrait orientation if supported
        if let conn = videoOutput.connection(with: .video) {
            if conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
        }

        session.commitConfiguration()
    }

    func start() {
        guard !session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func stop() {
        guard session.isRunning else { return }
        session.stopRunning()
    }
}

/// CoreAnimation overlay view that draws detection boxes.
final class OverlayView: UIView {
    weak var previewLayer: AVCaptureVideoPreviewLayer?
    var detections: [Detection] = [] { didSet { setNeedsDisplay() } }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        guard let previewLayer = previewLayer else { return }

        ctx.setLineWidth(2)

        for det in detections {
            // Vision bbox is normalized, origin bottom-left.
            let vb = det.bbox

            // Convert to metadata-output normalized rect (origin top-left).
            let metadataRect = CGRect(
                x: vb.origin.x,
                y: 1.0 - vb.origin.y - vb.size.height,
                width: vb.size.width,
                height: vb.size.height
            )

            // Convert to layer/view coordinates (accounts for resizeAspectFill cropping).
            let converted = previewLayer.layerRectConverted(fromMetadataOutputRect: metadataRect)

            // Box
            UIColor.systemGreen.setStroke()
            ctx.stroke(converted)

            // Label
            let label = "\(det.label) \(Int(det.confidence * 100))%"
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 14, weight: .semibold),
                .foregroundColor: UIColor.white
            ]
            let textSize = label.size(withAttributes: attrs)
            let textBg = CGRect(
                x: converted.minX,
                y: max(converted.minY - textSize.height - 4, 0),
                width: textSize.width + 8,
                height: textSize.height + 4
            )
            UIColor.systemGreen.setFill()
            ctx.fill(textBg)
            label.draw(in: textBg.insetBy(dx: 4, dy: 2), withAttributes: attrs)
        }
    }
}
struct CameraPreview: UIViewRepresentable {
    @ObservedObject var detector: Detector

    static let sharedSession = CameraSessionCoordinator()

    func makeUIView(context: Context) -> Preview {
        let view = Preview()
        view.backgroundColor = .black

        // camera layer
        view.videoLayer.session = CameraPreview.sharedSession.session
        view.videoLayer.videoGravity = .resizeAspectFill
        view.overlay.previewLayer = view.videoLayer

        // detector -> overlay binding
        context.coordinator.overlay = view.overlay

        // attach detector to video frames once
        detector.attach(to: CameraPreview.sharedSession.videoOutput)
        CameraPreview.sharedSession.start()

        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        // push latest detections to overlay each update
        uiView.overlay.detections = detector.detections
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator {
        weak var overlay: OverlayView?
    }

    final class Preview: UIView {
        // camera
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        // overlay
        let overlay = OverlayView()

        override init(frame: CGRect) {
            super.init(frame: frame)
            overlay.backgroundColor = .clear
            overlay.isUserInteractionEnabled = false
            addSubview(overlay)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            overlay.frame = bounds
        }
    }
}
