//
//  NegativeVideoCameraView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/29/26.
//

import AVFoundation
import SwiftUI
import UIKit

struct NegativeVideoCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var cameraService = NegativeVideoCameraService()
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    @State private var isCancelled = false

    private let onDone: (CapturedNegativeVideo) -> Void

    init(onDone: @escaping (CapturedNegativeVideo) -> Void) {
        self.onDone = onDone
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            NegativeVideoCameraPreview(session: cameraService.session)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                topControls
                Spacer()
                instructions
                bottomControls
            }

            if let errorMessage = cameraService.errorMessage {
                cameraErrorOverlay(message: errorMessage)
            }
        }
        .interactiveDismissDisabled(cameraService.isRecording)
        .onAppear {
            cameraService.onVideoRecorded = { url in
                if isCancelled {
                    try? FileManager.default.removeItem(at: url)
                } else {
                    let video = CapturedNegativeVideo(fileURL: url)
                    onDone(video)
                }
                dismiss()
            }
            cameraService.start()
        }
        .onDisappear {
            cameraService.stop()
            stopTimer()
        }
    }

    private var topControls: some View {
        HStack {
            // Cancel is now ALWAYS visible
            Button {
                if cameraService.isRecording {
                    isCancelled = true
                    stopTimer()
                    cameraService.stopRecording()
                } else {
                    dismiss()
                }
            } label: {
                Text(cameraService.isRecording ? "Stop & Cancel" : "Cancel")
                    .fontWeight(.semibold)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(cameraService.isRecording ? .red.opacity(0.8) : .black.opacity(0.55))
                    .clipShape(Capsule())
            }
            
            Spacer()
            
            if cameraService.isRecording {
                Text("00:\(String(format: "%02d", timeElapsed)) / 00:10")
                    .font(.headline.monospacedDigit())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.55))
                    .clipShape(Capsule())
            }
        }
        .foregroundStyle(.white)
        .padding()
    }

    private var instructions: some View {
        VStack(spacing: 5) {
            Text(cameraService.isRecording ? "Recording..." : "Capture Negative Backgrounds")
                .font(.headline)
            Text(cameraService.isRecording ? "Keep panning smoothly." : "Pan around the room slowly. Do NOT include the landmark in the frame.")
                .font(.footnote)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
        .padding(.bottom, 30)
    }

    private var bottomControls: some View {
        HStack {
            Spacer()
            Button {
                if !cameraService.isRecording {
                    isCancelled = false
                    cameraService.startRecording()
                    startTimer()
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(cameraService.isRecording ? .red : .white)
                        .frame(width: 76, height: 76)

                    Circle()
                        .stroke(.black.opacity(0.8), lineWidth: 3)
                        .frame(width: 64, height: 64)
                    
                    if cameraService.isRecording {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(.white)
                            .frame(width: 24, height: 24)
                    }
                }
            }
            .disabled(cameraService.isRecording)
            .opacity(cameraService.isRecording ? 0.5 : 1.0)
            Spacer()
        }
        .padding(.bottom, 40)
        .background(
            LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .bottom, endPoint: .top)
        )
    }

    private func startTimer() {
        timeElapsed = 0
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            timeElapsed += 1
            if timeElapsed >= 10 {
                stopTimer()
                cameraService.stopRecording()
            }
        }
    }
    
    private func stopTimer() {
        recordingTimer?.invalidate()
        recordingTimer = nil
    }

    private func cameraErrorOverlay(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill").font(.system(size: 42))
            Text("Camera Unavailable").font(.title2.bold())
            Text(message).multilineTextAlignment(.center)
            Button("Close") { dismiss() }.buttonStyle(.bordered)
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.9))
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .padding()
    }
}

// MARK: - Camera preview
private struct NegativeVideoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewUIView {
        let view = CameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: CameraPreviewUIView, context: Context) {
        if uiView.previewLayer.session !== session {
            uiView.previewLayer.session = session
        }
    }
}

private final class CameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    override func layoutSubviews() {
        super.layoutSubviews()
        if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }
}
