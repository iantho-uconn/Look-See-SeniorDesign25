//
//  PositiveVideoCameraView.swift
//  LookSeeProto
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
            if idx == 1 { return "Capture Video of The Landmark" }
            if idx == 2 { return "Step 2: Second Angle" }
            if idx == 3 { return "Step 3: Third Angle" }
            return "Step \(idx): Fourth Angle"
        case .optional:
            return "Additional Coverage"
        }
    }
    
    var instruction: String {
        switch self {
        case .mandatory(let idx):
            if idx == 1 { return "These videos should be taken from ALL typical places where users may see the landmark" }
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

struct RecordedClip: Identifiable, Equatable {
    var id: String { url.absoluteString }
    let phase: CameraPhase
    let url: URL
    let duration: Int
}

enum CameraFlowState: Equatable {
    case instruction
    case recording
    case reviewingRecent(URL, Int)
    case gallery
}

struct PositiveVideoCameraView: View {
    @StateObject private var cameraService = NegativeVideoCameraService()
    @Environment(\.scenePhase) private var scenePhase
    
    @State private var wasRecordingBeforeBackground = false
    @State private var suppressNextError = false

    @State private var currentPhase: CameraPhase = .mandatory(1)
    @State private var flowState: CameraFlowState = .instruction

    private let expectedAngles: Int = 1
    
    @State private var recordingTimer: Timer?
    @State private var timeElapsed: Int = 0
    
    @State private var recordedClips: [RecordedClip] = []
    @State private var gallerySelection: String = ""
    @State private var isCancelled = false
    
    @State private var isFinishing = false
    
    @State private var zoomLevel: CGFloat = 1.0
    @State private var showZoomIndicator = false
    @State private var zoomFadeTask: Task<Void, Never>?
    
    @State private var showZoomInstruction = false
    @State private var zoomInstructionTask: Task<Void, Never>?

    @State private var isCameraWarmedUp = false

    var isActive: Bool
    @Binding var isNavVisible: Bool
    
    private let completionButtonTitle: String
    private let onDone: ([URL]) -> Void
    private let onCancel: () -> Void
    
    private let maxTotalTimeLimit: Int = 90
    private let uiTargetDuration: Int
    private let minTotalTimeLimit: Int

    init(
        isActive: Bool,
        isNavVisible: Binding<Bool>,
        uiTargetDuration: Int = 30,
        minTotalTimeLimit: Int? = nil,
        completionButtonTitle: String = "Finish Submission",
        onDone: @escaping ([URL]) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.isActive = isActive
        self._isNavVisible = isNavVisible
        self.uiTargetDuration = uiTargetDuration
        self.minTotalTimeLimit = minTotalTimeLimit ?? uiTargetDuration
        self.completionButtonTitle = completionButtonTitle
        self.onDone = onDone
        self.onCancel = onCancel
    }

    private var totalDurationElapsedInt: Int {
        recordedClips.reduce(0) { $0 + $1.duration }
    }

    private var minPhaseTimeLimit: Int {
        if currentPhase.isMandatory {
            if minTotalTimeLimit == 30 {
                return 4
            } else if minTotalTimeLimit == 1 {
                return 1
            } else {
                return minTotalTimeLimit
            }
        } else {
            let deficit = minTotalTimeLimit - totalDurationElapsedInt
            if minTotalTimeLimit == 30 {
                return deficit > 0 ? min(4, deficit) : 1
            } else if minTotalTimeLimit == 1 {
                return 1
            } else {
                return deficit > 0 ? deficit : 1
            }
        }
    }

    private var maxPhaseTimeLimit: Int {
        if currentPhase.isMandatory {
            return maxTotalTimeLimit / expectedAngles
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
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()

            PositiveVideoCameraPreview(session: cameraService.session, zoomLevel: $zoomLevel) {
                showZoomIndicatorThenFade()
            }
            .ignoresSafeArea()
            .opacity((flowState == .gallery || isReviewingRecent) ? 0 : (isCameraWarmedUp ? 1 : 0.001))
            .animation(.easeInOut(duration: 0.4), value: isCameraWarmedUp)
            .zIndex(0)
            
            if case .reviewingRecent(let url, _) = flowState {
                PositiveSafeVideoPlayer(url: url)
                    .equatable()
                    .ignoresSafeArea()
                    .zIndex(1)
            }

            if flowState == .recording && showZoomInstruction {
                VStack {
                    Text("Slowly pan across the landmark while pinching to zoom in and out")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.9))
                        .clipShape(Capsule())
                        .padding(.top, 110)
                    Spacer()
                }
                .ignoresSafeArea(.container, edges: .top)
                .zIndex(4)
                .transition(.move(edge: .top).combined(with: .opacity))
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

            VStack(spacing: 0) {
                topControls
                    .padding(.top, 58)
                
                if flowState == .instruction {
                    instructionTopPrompt
                        .padding(.top, 16)
                }
                
                Spacer()
                
                switch flowState {
                case .instruction:
                    instructionBottomCard
                        .padding(.bottom, 100)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                case .recording:
                    recordingControls
                        .padding(.bottom, 100)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                case .reviewingRecent(let url, let duration):
                    reviewingRecentControls(for: url, recordedDuration: duration)
                        .padding(.bottom, 100)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                case .gallery:
                    EmptyView()
                }
            }
            .ignoresSafeArea(.container, edges: .top)
            .zIndex(2)
            
            if flowState == .gallery {
                galleryView
                    .zIndex(2)
                    .transition(.opacity)
            }

            if let errorMessage = cameraService.errorMessage, !suppressNextError {
                cameraErrorOverlay(message: errorMessage)
                    .zIndex(3)
                    .transition(.opacity)
            }
        }
        .interactiveDismissDisabled()
        .onAppear {
            isNavVisible = (flowState == .instruction)
            cameraService.onVideoRecorded = { url in
                let uniqueURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_positive.mov")
                try? FileManager.default.moveItem(at: url, to: uniqueURL)
                
                if isCancelled {
                    try? FileManager.default.removeItem(at: uniqueURL)
                    onCancel()
                } else if suppressNextError {
                    suppressNextError = false
                    try? FileManager.default.removeItem(at: uniqueURL)
                    DispatchQueue.main.async {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            flowState = .instruction
                        }
                    }
                } else {
                    let recordedDuration = timeElapsed
                    DispatchQueue.main.async {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            flowState = .reviewingRecent(uniqueURL, recordedDuration)
                        }
                    }
                }
            }
            if isActive {
                DispatchQueue.global(qos: .userInitiated).async {
                    self.cameraService.start()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        self.isCameraWarmedUp = true
                    }
                }
            }
        }
        .onDisappear {
            isNavVisible = true
            isCameraWarmedUp = false
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.5) {
                self.cameraService.stop()
            }
            stopTimer()
            zoomFadeTask?.cancel()
            zoomInstructionTask?.cancel()
        }
        .onChange(of: isActive) { _, active in
            if active {
                if !wasRecordingBeforeBackground {
                    DispatchQueue.global(qos: .userInitiated).async {
                        self.cameraService.start()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                            self.isCameraWarmedUp = true
                        }
                    }
                }
            } else {
                DispatchQueue.global(qos: .userInitiated).async { self.cameraService.stop() }
                isCameraWarmedUp = false
            }
        }
        .onChange(of: flowState) { _, state in
            withAnimation(.easeOut(duration: 0.2)) {
                isNavVisible = (state == .instruction)
            }
        }
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .background, .inactive:
                handleAppBackgrounding()
            case .active:
                handleAppForegrounding()
            @unknown default:
                break
            }
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

    private func triggerRecordingInstruction() {
        zoomInstructionTask?.cancel()
        showZoomInstruction = true
        
        zoomInstructionTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.5)) { showZoomInstruction = false }
            }
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
                        
                        recordedClips.removeAll()
                        timeElapsed = 0
                        currentPhase = .mandatory(1)
                        flowState = .instruction
                        onCancel()
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
            
            if flowState != .gallery {
                let progress = currentLiveProgress
                
                HStack(spacing: 6) {
                    Image(systemName: progress.isReady ? "checkmark.circle.fill" : "clock.fill")
                        .foregroundStyle(progress.isReady ? .green : .orange)
                    Text("\(progress.totalDuration)s / \(maxTotalTimeLimit)")
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
    }
    
    private var instructionTopPrompt: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                .padding(.top, 4)
            
            VStack(alignment: .leading, spacing: 6) {
                Text("Capture Positive Media")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text("Follow the on-screen steps to capture the different angles of the landmark. This video should be from a typical place where a user may see the landmark.")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(.white.opacity(0.8))
                    .lineSpacing(2)
            }
            Spacer()
        }
        .padding(20)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .padding(.horizontal, 20)
        .transition(.move(edge: .top).combined(with: .opacity))
    }
    
    private var instructionBottomCard: some View {
        VStack(spacing: 16) {
            Text(currentPhase.title)
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(Color(.white))
            
            Text(currentPhase.instruction)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.9))
                .padding(.horizontal, 10)
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                    flowState = .recording
                }
                cameraService.startRecording()
                startTimer()
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
            
            if !recordedClips.isEmpty {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    gallerySelection = recordedClips.last?.id ?? ""
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { flowState = .gallery }
                } label: {
                    Text("Cancel & View Captured Clips")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(.systemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .padding(.top, 4)
            }
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.2), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 20)
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
    }

    private var galleryView: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            TabView(selection: $gallerySelection) {
                ForEach(recordedClips) { clip in
                    ZStack {
                        PositiveSafeVideoPlayer(url: clip.url)
                            .ignoresSafeArea()
                        
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
                            .padding(.horizontal, 20).padding(.top, 58)
                            Spacer()
                        }
                    }
                    .tag(clip.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .ignoresSafeArea()
            
            VStack {
                HStack {
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        isCancelled = true
                        for clip in recordedClips { try? FileManager.default.removeItem(at: clip.url) }
                        recordedClips.removeAll()
                        timeElapsed = 0
                        currentPhase = .mandatory(1)
                        flowState = .instruction
                        onCancel()
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
                .padding(.horizontal, 20).padding(.top, 58)
                Spacer()
            }
            .ignoresSafeArea(.container, edges: .top)
            
            VStack {
                Spacer()
                galleryBottomControls
            }
        }
        .zIndex(10)
    }
    
    private var galleryBottomControls: some View {
        VStack(spacing: 12) {
            let nextMandatory = nextRequiredPhase()
            let timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt
            
            HStack {
                Text("Total: \(totalDurationElapsedInt)s / \(maxTotalTimeLimit)s")
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(.secondary)
                Spacer()
                if nextMandatory == nil {
                    Image(systemName: totalDurationElapsedInt >= minTotalTimeLimit ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(totalDurationElapsedInt >= minTotalTimeLimit ? .green : .orange)
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
                if timeRemaining > 0 {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        currentPhase = .optional(recordedClips.count + 1)
                        withAnimation(.spring()) { flowState = .instruction }
                    } label: {
                        Text("Add Extra Clip")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(Color(.white))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
                
                if totalDurationElapsedInt >= minTotalTimeLimit {
                    Button {
                        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                        isFinishing = true
                        onDone(recordedClips.map { $0.url })
                    } label: {
                        HStack(spacing: 8) {
                            if isFinishing { ProgressView().tint(.primary) }
                            Text(isFinishing ? "Preparing..." : completionButtonTitle)
                                .font(.system(size: 17, weight: .bold, design: .rounded))
                        }
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(.systemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .disabled(isFinishing)
                } else {
                    Text("Total video must be at least \(minTotalTimeLimit) seconds")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.center)
                        .padding(.vertical, 5)
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

    private func startTimer() {
        timeElapsed = 0
        triggerRecordingInstruction()
        
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
            Button { onCancel() } label: {
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
    
    private func handleAppBackgrounding() {
        guard flowState == .recording else { return }
        wasRecordingBeforeBackground = true
        suppressNextError = true
        stopTimer()
        cameraService.stopRecording()
    }

    private func handleAppForegrounding() {
        guard wasRecordingBeforeBackground else { return }
        wasRecordingBeforeBackground = false
        cameraService.errorMessage = nil
        cameraService.start()
    }
}

private struct PositiveVideoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    @Binding var zoomLevel: CGFloat
    var onZoomChanged: () -> Void

    func makeUIView(context: Context) -> PositiveCameraPreviewUIView {
        let view = PositiveCameraPreviewUIView()
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
    func updateUIView(_ uiView: PositiveCameraPreviewUIView, context: Context) {
        if uiView.previewLayer.session !== session { uiView.previewLayer.session = session }
    }
}

private final class PositiveCameraPreviewUIView: UIView {
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
        device.videoZoomFactor = baseZoomFactor
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
