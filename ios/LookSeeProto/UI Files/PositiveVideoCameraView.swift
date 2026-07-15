//
//  PositiveVideoCameraView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/14/26.
//


import AVFoundation
import SwiftUI
import UIKit
import AVKit

enum CameraPhase: Int, CaseIterable {
    case front = 0, left, right, last
    
    var title: String {
        switch self {
        case .front: return "Step 1: Front"
        case .left: return "Step 2: Left Side"
        case .right: return "Step 3: Right Side"
        case .last: return "Step 4: Details"
        }
    }
    
    var instruction: String {
        switch self {
        case .front: return "Record the very front of the landmark."
        case .left: return "Move over to record the left side."
        case .right: return "Move over to record the right side."
        case .last: return "Capture any remaining angles or details!"
        }
    }
}

enum CameraFlowState: Equatable {
    case instruction
    case recording
    case preview(URL)
}

struct PositiveVideoCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var cameraService = NegativeVideoCameraService()
    
    @State private var currentPhase: CameraPhase = .front
    @State private var flowState: CameraFlowState = .instruction
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    @State private var collectedURLs: [URL] = []
    
    @State private var isCancelled = false
    @State private var isRetaking = false

    private let onDone: ([URL]) -> Void

    init(onDone: @escaping ([URL]) -> Void) {
        self.onDone = onDone
    }

    private var currentPhaseTimeLimit: Int {
        currentPhase == .front ? 6 : 3
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if case .preview = flowState {} else {
                PositiveVideoCameraPreview(session: cameraService.session)
                    .ignoresSafeArea()
            }
            
            if case .preview(let url) = flowState {
                VideoPlayer(player: AVPlayer(url: url))
                    .ignoresSafeArea()
            }

            VStack {
                topControls
                Spacer()
                
                switch flowState {
                case .instruction:
                    instructionCard
                case .recording:
                    recordingControls
                case .preview(let url):
                    previewControls(for: url)
                }
            }

            if let errorMessage = cameraService.errorMessage {
                cameraErrorOverlay(message: errorMessage)
            }
        }
        .interactiveDismissDisabled()
        .onAppear {
            cameraService.onVideoRecorded = { url in
                if isCancelled {
                    try? FileManager.default.removeItem(at: url)
                    dismiss()
                } else if isRetaking {
                    try? FileManager.default.removeItem(at: url)
                    isRetaking = false
                    withAnimation(.spring()) {
                        flowState = .instruction
                    }
                } else {
                    withAnimation(.spring()) {
                        flowState = .preview(url)
                    }
                }
            }
            cameraService.start()
        }
        .onDisappear {
            cameraService.stop()
            stopTimer()
        }
    }
    
    private var instructionCard: some View {
        VStack(spacing: 16) {
            Text(currentPhase.title)
                .font(.subheadline.bold())
                .foregroundStyle(.blue)
                .textCase(.uppercase)
            
            Text(currentPhase.instruction)
                .font(.title3.bold())
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)
            
            Button {
                withAnimation(.spring()) {
                    flowState = .recording
                    cameraService.startRecording()
                    startTimer()
                }
            } label: {
                Text("Start Recording")
                    .fontWeight(.bold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.top, 8)
        }
        .padding(30)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32))
        .padding(.horizontal, 24)
        .padding(.bottom, 60)
        .transition(.scale.combined(with: .opacity))
    }
    
    private var recordingControls: some View {
        HStack {
            Spacer()
            Button {
                isRetaking = true
                stopTimer()
                cameraService.stopRecording()
            } label: {
                ZStack {
                    Circle().fill(.white).frame(width: 76, height: 76)
                    Circle().stroke(.black.opacity(0.8), lineWidth: 3).frame(width: 64, height: 64)
                    RoundedRectangle(cornerRadius: 4).fill(.red).frame(width: 24, height: 24)
                }
            }
            Spacer()
        }
        .padding(.bottom, 40)
        .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .bottom, endPoint: .top))
    }
    
    private func previewControls(for url: URL) -> some View {
        HStack(spacing: 16) {
            Button(role: .destructive) {
                try? FileManager.default.removeItem(at: url)
                withAnimation(.spring()) { flowState = .instruction }
            } label: {
                Text("Retake")
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red.opacity(0.8))
            .controlSize(.large)
            
            Button {
                collectedURLs.append(url)
                if let nextPhase = CameraPhase(rawValue: currentPhase.rawValue + 1) {
                    withAnimation(.spring()) {
                        currentPhase = nextPhase
                        flowState = .instruction
                    }
                } else {
                    onDone(collectedURLs)
                    dismiss()
                }
            } label: {
                Text(currentPhase == .last ? "Finish" : "Accept & Next")
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.blue)
            .controlSize(.large)
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32))
        .padding(.horizontal, 20)
        .padding(.bottom, 40)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private var topControls: some View {
        HStack {
            Button {
                isCancelled = true
                stopTimer()
                if cameraService.isRecording {
                    cameraService.stopRecording()
                } else {
                    for url in collectedURLs { try? FileManager.default.removeItem(at: url) }
                    if case .preview(let currentURL) = flowState { try? FileManager.default.removeItem(at: currentURL) }
                    dismiss()
                }
            } label: {
                Image(systemName: "xmark")
                    .font(.title3.bold())
                    .foregroundStyle(.white)
                    .padding(12)
                    .background(.black.opacity(0.55))
                    .clipShape(Circle())
            }
            
            Spacer()
            
            if flowState == .recording {
                Text("00:\(String(format: "%02d", timeElapsed)) / 00:0\(currentPhaseTimeLimit)")
                    .font(.headline.monospacedDigit())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.red.opacity(0.8))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
            }
        }
        .padding()
    }

    private func startTimer() {
        timeElapsed = 0
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            timeElapsed += 1
            if timeElapsed >= currentPhaseTimeLimit {
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
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .padding()
    }
}

private struct PositiveVideoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PositiveCameraPreviewUIView {
        let view = PositiveCameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PositiveCameraPreviewUIView, context: Context) {
        if uiView.previewLayer.session !== session {
            uiView.previewLayer.session = session
        }
    }
}

private final class PositiveCameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    
    private var initialZoom: CGFloat = 1.0

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupGestures()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupGestures()
    }
    
    private func setupGestures() {
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        addGestureRecognizer(pinch)
        
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(tap)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }
    
    @objc private func handlePinch(_ pinch: UIPinchGestureRecognizer) {
        guard let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }

        if pinch.state == .began {
            initialZoom = device.videoZoomFactor
        }

        if pinch.state == .changed || pinch.state == .began {
            let zoomFactor = min(max(initialZoom * pinch.scale, 1.0), min(5.0, device.activeFormat.videoMaxZoomFactor))
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = zoomFactor
                device.unlockForConfiguration()
            } catch {}
        }
    }
    
    @objc private func handleTap(_ tap: UITapGestureRecognizer) {
        guard let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }
        let point = tap.location(in: self)
        let captureDevicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: point)

        do {
            try device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.continuousAutoFocus) {
                device.focusPointOfInterest = captureDevicePoint
                device.focusMode = .continuousAutoFocus
            }
            if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposurePointOfInterest = captureDevicePoint
                device.exposureMode = .continuousAutoExposure
            }
            device.unlockForConfiguration()
        } catch {}
    }
}
