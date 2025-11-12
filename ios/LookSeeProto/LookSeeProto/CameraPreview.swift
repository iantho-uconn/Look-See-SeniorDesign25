//
//  CameraPreview.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 10/14/25.
//


import SwiftUI
import AVFoundation
import Vision   // for VNRecognizedObjectObservation

/// Keep this class alive for the camera session + model inference
final class CameraSessionCoordinator: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "camera.session.queue")

    // Overlay and detector
    private let overlayLayer = CALayer()
    private let detector = Detector()   // from Detector.swift

    // Keep a weak ref to the preview layer to keep overlay sized correctly
    private weak var previewLayer: AVCaptureVideoPreviewLayer?

    override init() {
        super.init()
        session.beginConfiguration()
        session.sessionPreset = .high

        // INPUT: back camera
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        // OUTPUT: video frames for inference
        let videoOut = AVCaptureVideoDataOutput()
        videoOut.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        videoOut.alwaysDiscardsLateVideoFrames = true
        videoOut.setSampleBufferDelegate(self, queue: queue)

        guard session.canAddOutput(videoOut) else {
            session.commitConfiguration()
            return
        }
        session.addOutput(videoOut)

        // Prefer portrait orientation for iPhone
        // After you add videoOut and before commitConfiguration()

        if let conn = videoOut.connection(with: .video), conn.isVideoOrientationSupported {
                    conn.videoOrientation = .portrait
                }



        session.commitConfiguration()

        queue.async { [weak self] in
            self?.session.startRunning()
        }
    }

    /// Called from the SwiftUI view once the preview layer exists
    func attachOverlay(to previewLayer: AVCaptureVideoPreviewLayer) {
        self.previewLayer = previewLayer
        overlayLayer.frame = previewLayer.bounds
        overlayLayer.masksToBounds = true
        previewLayer.addSublayer(overlayLayer)
    }

    /// Keep overlay sized with the preview
    func layoutOverlayIfNeeded() {
        guard let pl = previewLayer else { return }
        overlayLayer.frame = pl.bounds
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {

        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer),
              let detector = detector else { return }

        detector.predict(pixelBuffer: pb) { [weak self] objects in
            DispatchQueue.main.async {
                self?.drawOverlay(objects: objects)
            }
        }
    }

    // MARK: - Overlay rendering
    private func drawOverlay(objects: [VNRecognizedObjectObservation]) {
        overlayLayer.sublayers?.forEach { $0.removeFromSuperlayer() }
        let bounds = overlayLayer.bounds

        for o in objects {
            guard let top = o.labels.first else { continue }

            // Vision bbox is normalized (0-1) with origin at bottom-left
            let r = o.boundingBox
            let rect = CGRect(x: r.minX * bounds.width,
                              y: (1 - r.maxY) * bounds.height,
                              width: r.width * bounds.width,
                              height: r.height * bounds.height)

            let box = CAShapeLayer()
            box.frame = rect
            box.borderWidth = 2
            box.borderColor = UIColor.systemBlue.cgColor

            let text = CATextLayer()
            text.string = "\(top.identifier) \(String(format: "%.2f", top.confidence))"
            text.fontSize = 12
            text.alignmentMode = .left
            text.foregroundColor = UIColor.white.cgColor
            text.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.7).cgColor
            text.contentsScale = UIScreen.main.scale
            text.frame = CGRect(x: 0, y: 0, width: rect.width, height: 18)

            box.addSublayer(text)
            overlayLayer.addSublayer(box)
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    static let shared = CameraSessionCoordinator() // keep alive for the app’s life

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = CameraPreview.shared.session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill

        // Attach overlay on top of the preview
        CameraPreview.shared.attachOverlay(to: view.videoPreviewLayer)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // Keep overlay sized with UI changes (rotation, safe area, etc.)
        CameraPreview.shared.layoutOverlayIfNeeded()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}


/*
 import Foundation
 
 import SwiftUI
 import AVFoundation
 
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
 
 // No outputs needed for live preview only
 session.commitConfiguration()
 
 queue.async { [weak self] in
 self?.session.startRunning()
 }
 }
 }
 
 struct CameraPreview: UIViewRepresentable {
 static let shared = CameraSessionCoordinator() // keep alive
 
 func makeUIView(context: Context) -> PreviewView {
 let view = PreviewView()
 view.videoPreviewLayer.session = CameraPreview.shared.session
 view.videoPreviewLayer.videoGravity = .resizeAspectFill
 return view
 }
 
 func updateUIView(_ uiView: PreviewView, context: Context) {}
 
 final class PreviewView: UIView {
 override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
 var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
 }
 }
 
 */
