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
    case mandatory(Int)
    case optional(Int)
    
    var isMandatory: Bool {
        if case .mandatory = self { return true }
        return false
    }
    
    var title: String {
        switch self {
        case .mandatory(let idx):
            if idx == 1 { return "Step 1: Front" }
            if idx == 2 { return "Step 2: Second Angle" }
            if idx == 3 { return "Step 3: Third Angle" }
            return "Step \(idx): Fourth Angle"
        case .optional:
            return "Extra Coverage"
        }
    }
    
    var instruction: String {
        switch self {
        case .mandatory(let idx):
            if idx == 1 { return "Pan video across the front of the landmark." }
            return "Move to a different side or angle and pan across the landmark."
        case .optional:
            return "Pan across to capture missing details.\n\nTip: Have you tried standing farther back to get the whole object?"
        }
    }
    
    var indexPos: Int {
        switch self {
        case .mandatory(let idx): return idx - 1
        case .optional(let idx): return idx - 1
        }
    }
}

// Struct to track clips for the gallery
struct RecordedClip: Identifiable, Equatable {
    var id: String { url.absoluteString }
    let phase: CameraPhase
    let url: URL
    let duration: Int
}

enum CameraFlowState: Equatable {
    case angleSelection
    case instruction
    case recording
    case reviewingRecent(URL, Int)
    case gallery
}

struct PositiveVideoCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var cameraService = NegativeVideoCameraService()
    
    @State private var currentPhase: CameraPhase = .mandatory(1)
    @State private var flowState: CameraFlowState = .angleSelection
    @State private var expectedAngles: Int = 1
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    
    @State private var recordedClips: [RecordedClip] = []
    @State private var gallerySelection: String = ""
    @State private var isCancelled = false

    private let onDone: ([URL]) -> Void
    private let maxTotalTimeLimit: Int = 60
    private let minTotalTimeLimit: Int = 15

    init(onDone: @escaping ([URL]) -> Void) {
        self.onDone = onDone
    }

    private var totalDurationElapsedInt: Int {
        recordedClips.reduce(0) { $0 + $1.duration }
    }

    // Dynamic minimum limits based on whether it's an extra clip or not
    private var minPhaseTimeLimit: Int {
        if currentPhase.isMandatory {
            return 6
        } else {
            // Optional clips only have a minimum if the 15s global total wasn't reached yet
            let deficit = minTotalTimeLimit - totalDurationElapsedInt
            // We return a minimum of 1s so AVFoundation doesn't crash on a 0-length file
            return max(1, deficit)
        }
    }

    private var maxPhaseTimeLimit: Int {
        if currentPhase.isMandatory {
            return 60 / expectedAngles
        } else {
            return maxTotalTimeLimit - totalDurationElapsedInt
        }
    }

    private var isReviewingRecent: Bool {
        if case .reviewingRecent = flowState { return true }
        return false
    }
    
    private var currentLiveProgress: (totalDuration: Int, isReady: Bool) {
        let currentLiveDuration: Int
        let isCurrentClipValidMandatory: Bool
        
        if flowState == .recording {
            currentLiveDuration = timeElapsed
            isCurrentClipValidMandatory = currentPhase.isMandatory && timeElapsed >= minPhaseTimeLimit
        } else if case .reviewingRecent(_, let dur) = flowState {
            currentLiveDuration = dur
            isCurrentClipValidMandatory = currentPhase.isMandatory && dur >= minPhaseTimeLimit
        } else {
            currentLiveDuration = 0
            isCurrentClipValidMandatory = false
        }
        
        let total = totalDurationElapsedInt + currentLiveDuration
        let capturedMandatoryCount = recordedClips.filter { $0.phase.isMandatory }.count
        let effectiveMandatoryCount = capturedMandatoryCount + (isCurrentClipValidMandatory ? 1 : 0)
        
        let isReady = (total >= minTotalTimeLimit) && (effectiveMandatoryCount >= expectedAngles)
        
        return (total, isReady)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // The IF statement is GONE! The camera is now truly always alive,
            // we just fade it out smoothly. This completely fixes the black flash.
            PositiveVideoCameraPreview(session: cameraService.session)
                .ignoresSafeArea()
                .opacity((flowState == .gallery || isReviewingRecent) ? 0 : 1)
                .zIndex(0)
            
            if case .reviewingRecent(let url, _) = flowState {
                PositiveSafeVideoPlayer(url: url)
                    .equatable()
                    .ignoresSafeArea()
                    .zIndex(1)
            }

            VStack {
                topControls
                Spacer()
                
                switch flowState {
                case .angleSelection:
                    angleSelectionCard
                case .instruction:
                    instructionCard
                case .recording:
                    recordingControls
                case .reviewingRecent(let url, let duration):
                    reviewingRecentControls(for: url, recordedDuration: duration)
                case .gallery:
                    EmptyView()
                }
            }
            .zIndex(2)
            
            if flowState == .gallery {
                galleryView
                    .zIndex(2)
            }

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
                    let recordedDuration = timeElapsed
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        flowState = .reviewingRecent(url, recordedDuration)
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
    
    private var topControls: some View {
        HStack {
            if flowState != .gallery {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    isCancelled = true
                    stopTimer()
                    if cameraService.isRecording {
                        cameraService.stopRecording()
                    } else {
                        for clip in recordedClips { try? FileManager.default.removeItem(at: clip.url) }
                        if case .reviewingRecent(let currentURL, _) = flowState { try? FileManager.default.removeItem(at: currentURL) }
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
            }
            
            Spacer()
            
            if flowState != .angleSelection && flowState != .gallery {
                let progress = currentLiveProgress
                
                HStack(spacing: 6) {
                    Image(systemName: progress.isReady ? "checkmark.circle.fill" : "clock.fill")
                        .foregroundStyle(progress.isReady ? .green : .orange)
                    Text("\(progress.totalDuration)s / 60s")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial)
                .clipShape(Capsule())
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }
    
    private var angleSelectionCard: some View {
        VStack(spacing: 24) {
            Text("How many angles?")
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)

            Text("How many distinct sides or perspectives does this landmark have?")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 16) {
                ForEach([1, 2, 3, 4], id: \.self) { count in
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        expectedAngles = count
                        currentPhase = .mandatory(1)
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { flowState = .instruction }
                    } label: {
                        Text(count == 4 ? "4+" : "\(count)")
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .frame(width: 60, height: 60)
                            .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .foregroundStyle(.white)
                            .clipShape(Circle())
                    }
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
    
    private var instructionCard: some View {
        VStack(spacing: 16) {
            Text(currentPhase.title)
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                .textCase(.uppercase)
                .tracking(1.2)
            
            Text(currentPhase.instruction)
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)
                .padding(.horizontal, 8)
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    flowState = .recording
                    cameraService.startRecording()
                    startTimer()
                }
            } label: {
                // Text is now perfectly clean without the max seconds listed
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
            
            if !recordedClips.isEmpty {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    gallerySelection = recordedClips.last?.id ?? ""
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { flowState = .gallery }
                } label: {
                    Text("Cancel & View Captured Clips")
                        .font(.system(size: 15, weight: .semibold, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 4)
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
    
    private func reviewingRecentControls(for url: URL, recordedDuration: Int) -> some View {
        HStack(spacing: 16) {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
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
                let newClip = RecordedClip(phase: currentPhase, url: url, duration: recordedDuration)
                recordedClips.append(newClip)
                gallerySelection = newClip.id
                
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    flowState = .gallery
                }
            } label: {
                Text("Accept")
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

    // MARK: - The Swipeable Gallery UI
    
    private var galleryView: some View {
        ZStack {
            TabView(selection: $gallerySelection) {
                ForEach(recordedClips) { clip in
                    ZStack {
                        PositiveSafeVideoPlayer(url: clip.url)
                            .ignoresSafeArea()
                        
                        // Top Right Delete Button
                        VStack {
                            HStack {
                                Spacer()
                                Button {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    deleteClip(clip)
                                } label: {
                                    Image(systemName: "trash.fill")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(.white)
                                        .frame(width: 32, height: 32)
                                        .background(.black.opacity(0.6))
                                        .clipShape(Circle())
                                }
                            }
                            .padding(.horizontal, 20).padding(.top, 16)
                            Spacer()
                        }
                    }
                    .tag(clip.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .ignoresSafeArea()
            
            // Global Cancel Button (Top Left)
            VStack {
                HStack {
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        isCancelled = true
                        for clip in recordedClips { try? FileManager.default.removeItem(at: clip.url) }
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(.ultraThinMaterial)
                            .clipShape(Circle())
                    }
                    Spacer()
                }
                .padding(.horizontal, 20).padding(.top, 10)
                Spacer()
            }
            
            // Bottom Gallery Controls
            VStack {
                Spacer()
                galleryBottomControls
            }
        }
        .transition(.opacity)
    }
    
    private var galleryBottomControls: some View {
        VStack(spacing: 12) {
            let nextMandatory = nextRequiredPhase()
            let timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt
            
            HStack {
                Text("Total: \(totalDurationElapsedInt)s / 60s")
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(.secondary)
                Spacer()
                if nextMandatory == nil {
                    Image(systemName: totalDurationElapsedInt >= minTotalTimeLimit ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(totalDurationElapsedInt >= minTotalTimeLimit ? .green : .orange)
                } else {
                    Text("\(expectedAngles - recordedClips.filter({$0.phase.isMandatory}).count) angles left")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundStyle(.orange)
                }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)

            if let next = nextMandatory {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    currentPhase = next
                    withAnimation(.spring()) { flowState = .instruction }
                } label: {
                    Text("Record Next Angle")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            } else {
                if totalDurationElapsedInt >= minTotalTimeLimit {
                    Button {
                        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                        onDone(recordedClips.map { $0.url })
                        dismiss()
                    } label: {
                        Text("Finish Submission")
                            .font(.system(size: 17, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color.green)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                } else {
                    Text("Must reach 15s total minimum")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(uiColor: .tertiarySystemFill))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                
                if timeRemaining > 0 {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        currentPhase = .optional(recordedClips.count + 1)
                        withAnimation(.spring()) { flowState = .instruction }
                    } label: {
                        Text("Add Extra Clip")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
            }
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 20)
        .padding(.bottom, 40)
    }

    private func nextRequiredPhase() -> CameraPhase? {
        for i in 0..<expectedAngles {
            if !recordedClips.contains(where: { $0.phase.indexPos == i && $0.phase.isMandatory }) {
                return .mandatory(i + 1)
            }
        }
        return nil
    }

    private func deleteClip(_ clip: RecordedClip) {
        if let idx = recordedClips.firstIndex(of: clip) {
            recordedClips.remove(at: idx)
            try? FileManager.default.removeItem(at: clip.url)
        }
        
        if recordedClips.isEmpty {
            currentPhase = nextRequiredPhase() ?? .mandatory(1)
            withAnimation(.spring()) { flowState = .instruction }
        }
    }

    // MARK: - Standard Camera Timers

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

private struct PositiveVideoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PositiveCameraPreviewUIView {
        let view = PositiveCameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ uiView: PositiveCameraPreviewUIView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
    }
}

private final class PositiveCameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    private var initialZoom: CGFloat = 1.0
    override init(frame: CGRect) { super.init(frame: frame); setupGestures() }
    required init?(coder: NSCoder) { super.init(coder: coder); setupGestures() }
    
    private func setupGestures() {
        addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:))))
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
    }
    
    @objc private func handlePinch(_ pinch: UIPinchGestureRecognizer) {
        guard let device = previewLayer.session?.inputs.compactMap({ $0 as? AVCaptureDeviceInput }).first?.device else { return }
        if pinch.state == .began { initialZoom = device.videoZoomFactor }
        if pinch.state == .changed || pinch.state == .began {
            let zoomFactor = min(max(initialZoom * pinch.scale, 1.0), min(5.0, device.activeFormat.videoMaxZoomFactor))
            try? device.lockForConfiguration()
            device.videoZoomFactor = zoomFactor
            device.unlockForConfiguration()
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

private struct PositiveSafeVideoPlayer: UIViewControllerRepresentable, Equatable {
    let url: URL
    static func == (lhs: PositiveSafeVideoPlayer, rhs: PositiveSafeVideoPlayer) -> Bool { return lhs.url == rhs.url }
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
