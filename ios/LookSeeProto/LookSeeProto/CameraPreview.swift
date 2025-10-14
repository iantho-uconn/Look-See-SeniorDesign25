//
//  CameraPreview.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 10/14/25.
//

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
