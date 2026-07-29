//
//  NegativeVideoCameraView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/29/26.
//

import AVFoundation
import SwiftUI
import UIKit
import AVKit

enum NegativeCameraPhase: Equatable {
    case first
    case additional(Int)
    
    var title: String {
        switch self {
        case .first: return "Background Pan"
        case .additional(let index): return "Extra Pan \(index - 1)"
        }
    }
    
    var indexPos: Int {
        switch self {
        case .first: return 0
        case .additional(let x): return x - 1
        }
    }
}

enum NegativeCameraFlowState: Equatable {
    case instruction
    case recording
    case choice
    case preview(URL)
}

struct NegativeVideoCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var cameraService = NegativeVideoCameraService()
    
    @State private var currentPhase: NegativeCameraPhase = .first
    @State private var flowState: NegativeCameraFlowState = .instruction
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    @State private var totalDurationElapsed: Double = 0.0
    @State private var collectedURLs: [URL] = []
    @State private var isCancelled = false

    @State private var zoomLevel: CGFloat = 1.0
    @State private var showZoomIndicator = false
    @State private var zoomFadeTask: Task<Void, Never>?

    private let onDone: (CapturedNegativeVideo) -> Void
    private let maxTotalTimeLimit: Int = 60

    init(onDone: @escaping (CapturedNegativeVideo) -> Void) {
        self.onDone = onDone
    }

    private var totalDurationElapsedInt: Int {
        return Int(totalDurationElapsed)
    }

    private var minPhaseTimeLimit: Int {
        currentPhase == .first ? 10 : 1
    }
    
    private var maxPhaseTimeLimit: Int {
        return maxTotalTimeLimit - totalDurationElapsedInt
    }

    private var isReviewingClip: Bool {
        if case .preview = flowState { return true }
        return false
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            NegativeVideoCameraPreview(session: cameraService.session, zoomLevel: $zoomLevel) {
                showZoomIndicatorThenFade()
            }
            .ignoresSafeArea()
            .opacity((flowState == .choice || isReviewingClip) ? 0 : 1)
            .zIndex(0)
            
            if case .preview(let url) = flowState {
                NegativeSafeVideoPlayer(url: url)
                    .equatable()
                    .ignoresSafeArea()
                    .zIndex(1)
            }

            if showZoomIndicator {
                VStack {
                    Spacer()
                    Text(String(format: "%.1fx", zoomLevel))
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.6))
                        .clipShape(Capsule())
                        .padding(.bottom, 160)
                }
                .zIndex(4)
                .transition(.opacity)
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
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        flowState = .preview(url)
                    }
                }
            }
            cameraService.start()
        }
        .onDisappear {
            cameraService.stop()
            stopTimer()
            zoomFadeTask?.cancel()
        }
    }
    
    private func showZoomIndicatorThenFade() {
        zoomFadeTask?.cancel()
        withAnimation(.easeOut(duration: 0.2)) { showZoomIndicator = true }
        
        zoomFadeTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.3)) { showZoomIndicator = false }
            }
        }
    }
    
    private var instructionCard: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15)).frame(width: 60, height: 60)
                Image(systemName: "video.slash.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
            }
            .padding(.bottom, 4)
            
            Text(currentPhase.title)
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)
            
            Text("Pan the area. Do not include the landmark in the video.")
                .font(.system(size: 15, weight: .medium))
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    flowState = .recording
                    cameraService.startRecording()
                    startTimer()
                }
            } label: {
                Text("Start Recording")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), radius: 8, x: 0, y: 4)
            }
            .padding(.top, 12)
        }
        .padding(30)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 24)
        .padding(.bottom, 60)
        .transition(.scale.combined(with: .opacity))
    }
    
    private var choiceCard: some View {
        VStack(spacing: 20) {
            Text("Add More Background?")
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)
            
            Text("Would you like to add another background pan to further improve recognition?")
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)
            
            VStack(spacing: 12) {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    if currentPhase == .first {
                        currentPhase = .additional(2)
                        flowState = .instruction
                    } else if case .additional(let index) = currentPhase {
                        currentPhase = .additional(index + 1)
                        flowState = .instruction
                    }
                } label: {
                    Text("Yes, Add Clip")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    finishAndStitch()
                } label: {
                    Text("No, Finish Background")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(uiColor: .tertiarySystemFill))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .padding(30)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 24)
        .padding(.bottom, 60)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
    
    private var recordingControls: some View {
        VStack(spacing: 20) {
            if timeElapsed < minPhaseTimeLimit {
                Text("Keep recording for \(minPhaseTimeLimit - timeElapsed)s...")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.6))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
                    .transition(.opacity)
            } else {
                Text("Ready to stop")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.green.opacity(0.8))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
                    .transition(.opacity)
            }
            
            HStack {
                Spacer()
                Button {
                    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                    let durationAdded = Double(timeElapsed)
                    totalDurationElapsed += durationAdded
                    stopTimer()
                    cameraService.stopRecording()
                } label: {
                    ZStack {
                        Circle()
                            .stroke(Color.white.opacity(0.3), lineWidth: 4)
                            .frame(width: 80, height: 80)
                        
                        Circle()
                            .trim(from: 0, to: CGFloat(timeElapsed) / CGFloat(maxPhaseTimeLimit))
                            .stroke(Color.red, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                            .frame(width: 80, height: 80)
                            .rotationEffect(.degrees(-90))
                            .animation(.linear(duration: 1.0), value: timeElapsed)
                        
                        RoundedRectangle(cornerRadius: timeElapsed >= minPhaseTimeLimit ? 8 : 40, style: .continuous)
                            .fill(timeElapsed >= minPhaseTimeLimit ? Color.red : Color.white.opacity(0.8))
                            .frame(width: timeElapsed >= minPhaseTimeLimit ? 32 : 64, height: timeElapsed >= minPhaseTimeLimit ? 32 : 64)
                            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: timeElapsed >= minPhaseTimeLimit)
                    }
                }
                .disabled(timeElapsed < minPhaseTimeLimit)
                Spacer()
            }
        }
        .padding(.bottom, 50)
        .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .bottom, endPoint: .top))
    }
    
    private func previewControls(for url: URL) -> some View {
        HStack(spacing: 16) {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                totalDurationElapsed -= Double(timeElapsed)
                try? FileManager.default.removeItem(at: url)
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { flowState = .instruction }
            } label: {
                Text("Retake")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Color.red.opacity(0.8))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                if collectedURLs.count > currentPhase.indexPos {
                    collectedURLs[currentPhase.indexPos] = url
                } else {
                    collectedURLs.append(url)
                }
                
                let timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt
                
                if timeRemaining >= 3 {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { flowState = .choice }
                } else {
                    finishAndStitch()
                }
            } label: {
                Text("Accept Clip")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 20)
        .padding(.bottom, 40)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private var topControls: some View {
        HStack {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
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
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(.ultraThinMaterial)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
            }
            
            Spacer()
            
            if flowState == .recording {
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color.red)
                        .frame(width: 8, height: 8)
                        .opacity(timeElapsed % 2 == 0 ? 1 : 0.3)
                        .animation(.linear(duration: 0.5), value: timeElapsed)
                    
                    Text("\(String(format: "%02d", timeElapsed)) / \(String(format: "%02d", maxPhaseTimeLimit))")
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
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

    private func finishAndStitch() {
        Task {
            if collectedURLs.count > 1, let stitched = await stitchVideos(urls: collectedURLs) {
                let video = CapturedNegativeVideo(fileURL: stitched)
                onDone(video)
            } else if let first = collectedURLs.first {
                let video = CapturedNegativeVideo(fileURL: first)
                onDone(video)
            }
            dismiss()
        }
    }

    private func stitchVideos(urls: [URL]) async -> URL? {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        var currentTime = CMTime.zero
        for url in urls {
            let asset = AVURLAsset(url: url)
            do {
                guard let assetVideoTrack = try await asset.loadTracks(withMediaType: .video).first else { continue }
                let duration = try await asset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
                videoTrack.preferredTransform = try await assetVideoTrack.load(.preferredTransform)
                currentTime = CMTimeAdd(currentTime, duration)
            } catch { return nil }
        }
        let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_neg_stitched.mov")
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else { return nil }
        exporter.outputURL = outputURL
        exporter.outputFileType = .mov
        await exporter.export()
        return exporter.status == .completed ? outputURL : nil
    }

    private func cameraErrorOverlay(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 42, weight: .light)).foregroundStyle(.orange)
            Text("Camera Unavailable").font(.system(size: 22, weight: .bold, design: .rounded))
            Text(message).font(.system(size: 15)).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button { dismiss() } label: {
                Text("Close")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Color(uiColor: .tertiarySystemFill))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
        .padding(30)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .padding(.horizontal, 40)
    }
}

private struct NegativeVideoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    @Binding var zoomLevel: CGFloat
    var onZoomChanged: () -> Void

    func makeUIView(context: Context) -> NegativeCameraPreviewUIView {
        let view = NegativeCameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onZoom = { newZoom in
            DispatchQueue.main.async {
                self.zoomLevel = newZoom
                self.onZoomChanged()
            }
        }
        return view
    }
    func updateUIView(_ uiView: NegativeCameraPreviewUIView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
    }
}

private final class NegativeCameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    
    private var initialZoom: CGFloat = 1.0
    private var baseZoomFactor: CGFloat = 1.0
    private var isCameraConfigured = false
    var onZoom: ((CGFloat) -> Void)?

    override init(frame: CGRect) { super.init(frame: frame); setupGestures() }
    required init?(coder: NSCoder) { super.init(coder: coder); setupGestures() }
    
    private func setupGestures() {
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:))))
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
    }
    
    private func configureCameraIfNeeded() {
        guard !isCameraConfigured,
              let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }
        
        // 🚀 Detect virtual lenses and map them correctly
        if device.deviceType == .builtInDualWideCamera || device.deviceType == .builtInTripleCamera {
            if let firstSwitch = device.virtualDeviceSwitchOverVideoZoomFactors.first {
                baseZoomFactor = CGFloat(firstSwitch.floatValue)
            } else {
                baseZoomFactor = 2.0
            }
        } else {
            baseZoomFactor = 1.0
        }
        
        try? device.lockForConfiguration()
        device.videoZoomFactor = baseZoomFactor // Start camera at "1.0x" (Wide Lens)
        device.unlockForConfiguration()
        
        isCameraConfigured = true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        configureCameraIfNeeded()
        if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
    }
    
    @objc private func handlePinch(_ pinch: UIPinchGestureRecognizer) {
        guard let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }
        
        if pinch.state == .began {
            initialZoom = device.videoZoomFactor
        }
        
        if pinch.state == .changed || pinch.state == .began {
            let maxAllowedZoom = min(5.0 * baseZoomFactor, device.activeFormat.videoMaxZoomFactor)
            let zoomFactor = min(max(initialZoom * pinch.scale, device.minAvailableVideoZoomFactor), maxAllowedZoom)
            
            try? device.lockForConfiguration()
            device.videoZoomFactor = zoomFactor
            device.unlockForConfiguration()
            
            // 🚀 Display division magic
            let displayZoom = zoomFactor / baseZoomFactor
            onZoom?(displayZoom)
        }
    }
    
    @objc private func handleTap(_ tap: UITapGestureRecognizer) {
        guard let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }
        let point = tap.location(in: self)
        let captureDevicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: point)
        try? device.lockForConfiguration()
        if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.continuousAutoFocus) {
            device.focusPointOfInterest = captureDevicePoint
            device.focusMode = .continuousAutoFocus
        }
        if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.continuousAutoExposure) {
            device.exposurePointOfInterest = captureDevicePoint
            device.exposureMode = .continuousAutoExposure
        }
        device.unlockForConfiguration()
    }
}

private struct NegativeSafeVideoPlayer: UIViewControllerRepresentable, Equatable {
    let url: URL
    static func == (lhs: NegativeSafeVideoPlayer, rhs: NegativeSafeVideoPlayer) -> Bool { return lhs.url == rhs.url }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = AVPlayer(url: url)
        controller.videoGravity = .resizeAspectFill
        if #available(iOS 16.0, *) { controller.allowsVideoFrameAnalysis = false }
        return controller
    }
    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: ()) {
        let player = uiViewController.player
        uiViewController.player = nil
        DispatchQueue.global(qos: .background).async { player?.pause() }
    }
}
