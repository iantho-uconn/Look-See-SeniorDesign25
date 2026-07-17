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

enum CameraPhase: Equatable {
    case front
    case left
    case right
    case back
    case additional(Int)
    
    var title: String {
        switch self {
        case .front: return "Step 1: Front"
        case .left: return "Step 2: Left Side"
        case .right: return "Step 3: Right Side"
        case .back: return "Step 4: Back Side"
        case .additional(let index): return "Step \(index): Extra Coverage"
        }
    }
    
    var instruction: String {
        switch self {
        case .front: return "Pan video across the front of the landmark"
        case .left: return "Move to left side of landmark and pan video across the left side of the landmark"
        case .right: return "Move to right side of landmark and pan video across the right side of the landmark"
        case .back: return "Move to back side of landmark, if unavailable, move to another location around the landmark and pan video across the landmark"
        case .additional: return "Pan across the landmark to capture missing angles or details."
        }
    }

    
    
    var indexPos: Int {
        switch self {
        case .front: return 0
        case .left: return 1
        case .right: return 2
        case .back: return 3
        case .additional(let x): return 3 + (x - 4)
        }
    }
}

enum CameraFlowState: Equatable {
    case instruction
    case recording
    case choice
    case preview(URL)
}

struct PositiveVideoCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var cameraService = NegativeVideoCameraService()
    
    @State private var currentPhase: CameraPhase = .front
    @State private var flowState: CameraFlowState = .instruction
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    @State private var totalDurationElapsed: Double = 0.0
    @State private var collectedURLs: [URL] = []
    @State private var isCancelled = false

    private let onDone: ([URL]) -> Void
    private let maxTotalTimeLimit: Int = 60

    init(onDone: @escaping ([URL]) -> Void) {
        self.onDone = onDone
    }

    private var totalDurationElapsedInt: Int {
        var total = 0
        for i in 0..<min(collectedURLs.count, currentPhase.indexPos) {
            let asset = AVURLAsset(url: collectedURLs[i])
            total += Int(CMTimeGetSeconds(asset.duration))
        }
        return total
    }

    private var minPhaseTimeLimit: Int {
        switch currentPhase {
        case .front: return 7
        case .left: return 4
        case .right: return 4
        default: return 0
        }
    }
    
    private var maxPhaseTimeLimit: Int {
        let remaining = maxTotalTimeLimit - totalDurationElapsedInt
        return min(15, remaining)
    }

    private var isReviewingClip: Bool {
        if case .preview = flowState { return true }
        return false
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            PositiveVideoCameraPreview(session: cameraService.session)
                .ignoresSafeArea()
                .opacity(isReviewingClip ? 0 : 1)
            
            if case .preview(let url) = flowState {
                PositiveSafeVideoPlayer(url: url)
                    .equatable()
                    .ignoresSafeArea()
                    .zIndex(1)
            }

            VStack {
                topControls
                Spacer()
                
                switch flowState {
                case .instruction:
                    instructionCard
                case .recording:
                    recordingControls
                case .choice:
                    choiceCard
                case .preview(let url):
                    previewControls(for: url)
                }
            }
            .zIndex(2)

            if let errorMessage = cameraService.errorMessage {
                cameraErrorOverlay(message: errorMessage)
                    .zIndex(3)
            }
        }
        .interactiveDismissDisabled()
        .onAppear {
            cameraService.onVideoRecorded = { url in
                if isCancelled {
                    try? FileManager.default.removeItem(at: url)
                    dismiss()
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
    
    private var choiceCard: some View {
        VStack(spacing: 20) {
            Text("Add More Coverage?")
                .font(.title3.bold())
                .foregroundStyle(.white)
            
            Text("Would you like to add another video clip to capture extra details of this landmark?")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)
            
            VStack(spacing: 12) {
                Button {
                    if currentPhase == .right {
                        currentPhase = .back
                        flowState = .instruction
                    } else if case .back = currentPhase {
                        currentPhase = .additional(5)
                        flowState = .instruction
                    } else if case .additional(let index) = currentPhase {
                        currentPhase = .additional(index + 1)
                        flowState = .instruction
                    }
                } label: {
                    Text("Yes, Add Clip")
                        .fontWeight(.bold)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                
                Button {
                    onDone(collectedURLs)
                    dismiss()
                } label: {
                    Text("No, Finish Submission")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .foregroundColor(.white)
                .controlSize(.large)
            }
        }
        .padding(30)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32))
        .padding(.horizontal, 24)
        .padding(.bottom, 60)
    }
    
    private var recordingControls: some View {
        VStack(spacing: 12) {
            if timeElapsed < minPhaseTimeLimit {
                Text("Keep recording for \(minPhaseTimeLimit - timeElapsed)s...")
                    .font(.caption.bold())
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.6))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
            } else {
                Text(" ")
                    .font(.caption.bold())
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
            }
            
            HStack {
                Spacer()
                Button {
                    let durationAdded = Double(timeElapsed)
                    totalDurationElapsed += durationAdded
                    stopTimer()
                    cameraService.stopRecording()
                } label: {
                    ZStack {
                        Circle().fill(timeElapsed >= minPhaseTimeLimit ? .white : .white.opacity(0.5)).frame(width: 76, height: 76)
                        Circle().stroke(.black.opacity(0.8), lineWidth: 3).frame(width: 64, height: 64)
                        RoundedRectangle(cornerRadius: 4).fill(timeElapsed >= minPhaseTimeLimit ? .red : .gray).frame(width: 24, height: 24)
                    }
                }
                .disabled(timeElapsed < minPhaseTimeLimit)
                Spacer()
            }
        }
        .padding(.bottom, 40)
        .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .bottom, endPoint: .top))
    }
    
    private func previewControls(for url: URL) -> some View {
        HStack(spacing: 16) {
            Button(role: .destructive) {
                totalDurationElapsed -= Double(timeElapsed)
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
                if collectedURLs.count > currentPhase.indexPos {
                    collectedURLs[currentPhase.indexPos] = url
                } else {
                    collectedURLs.append(url)
                }
                
                let timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt
                var isEligibleForChoice = false
                switch currentPhase {
                case .right, .back, .additional: isEligibleForChoice = true
                default: isEligibleForChoice = false
                }
                
                if isEligibleForChoice && timeRemaining >= 3 {
                    withAnimation(.spring()) { flowState = .choice }
                } else if let nextPhase = getPhaseFromIndex(currentPhase.indexPos + 1), timeRemaining >= 3 {
                    withAnimation(.spring()) {
                        currentPhase = nextPhase
                        flowState = .instruction
                    }
                } else {
                    onDone(collectedURLs)
                    dismiss()
                }
            } label: {
                Text("Accept Clip")
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
                Text("00:\(String(format: "%02d", timeElapsed)) / 00:\(String(format: "%02d", maxPhaseTimeLimit))")
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
            if timeElapsed >= maxPhaseTimeLimit {
                stopTimer()
                cameraService.stopRecording()
            }
        }
    }
    
    private func stopTimer() {
        recordingTimer?.invalidate()
        recordingTimer = nil
    }

    private func getPhaseFromIndex(_ index: Int) -> CameraPhase? {
        if index == 0 { return .front }
        if index == 1 { return .left }
        if index == 2 { return .right }
        if index == 3 { return .back }
        return .additional(index + 1)
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

private struct PositiveSafeVideoPlayer: UIViewControllerRepresentable, Equatable {
    let url: URL

    static func == (lhs: PositiveSafeVideoPlayer, rhs: PositiveSafeVideoPlayer) -> Bool {
        return lhs.url == rhs.url
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = AVPlayer(url: url)
        controller.videoGravity = .resizeAspectFill
        if #available(iOS 16.0, *) {
            controller.allowsVideoFrameAnalysis = false
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: ()) {
        let player = uiViewController.player
        uiViewController.player = nil
        DispatchQueue.global(qos: .background).async {
            player?.pause()
        }
    }
}
