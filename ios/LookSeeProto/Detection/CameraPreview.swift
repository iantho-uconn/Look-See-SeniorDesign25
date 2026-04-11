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
import UIKit
import AVFoundation


final class OverlayView: UIView {
    weak var previewLayer: AVCaptureVideoPreviewLayer?
    var detections: [Detection] = [] {
        didSet {
            print("🟢 OverlayView received \(detections.count) detections")
            for det in detections {
                print("🔹 \(det.label) \(Int(det.confidence*100))% → \(det.bbox)")
            }
            setNeedsDisplay()
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let previewLayer = previewLayer else { return }

        ctx.clear(rect)
        ctx.setLineWidth(2.0)

        // TODO: Possibly change this for AR support
        for det in detections {
            var bbox = det.bbox

            // Clamp bounding box to view
            bbox.origin.x = max(0, min(bbox.origin.x, bounds.width))
            bbox.origin.y = max(0, min(bbox.origin.y, bounds.height))
            bbox.size.width = max(0, min(bbox.size.width, bounds.width - bbox.origin.x))
            bbox.size.height = max(0, min(bbox.size.height, bounds.height - bbox.origin.y))

            if bbox.width <= 0 || bbox.height <= 0 { continue }

            // Draw bounding box
            UIColor.systemGreen.setStroke()
            ctx.stroke(bbox)

            // Draw label and confidence above the box
            let labelText = "\(det.label) \(Int(det.confidence * 100))%"
            let font = UIFont.systemFont(ofSize: 14, weight: .semibold)
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white
            ]

            let textSize = labelText.size(withAttributes: attributes)

            // Put label inside top-left of box (clamped to view)
            let textX = max(bbox.minX, 0)
            let textY = max(bbox.minY, 0)

            let bgRect = CGRect(
                x: textX,
                y: textY,
                width: textSize.width + 8,
                height: textSize.height + 4
            )

            UIColor.systemGreen.setFill()
            ctx.fill(bgRect)

            labelText.draw(in: bgRect.insetBy(dx: 4, dy: 2), withAttributes: attributes)
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
