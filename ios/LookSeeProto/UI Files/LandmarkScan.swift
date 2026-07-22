//
//  LandmarkScan.swift
//  LookSeeProto
//
import SwiftUI
import CoreLocation
struct LandmarkScan: View {
    var onTap: () -> Void = {}
    var onPinch: () -> Void = {}
    
    @Binding var isDetecting: Bool
    @Binding var isNavVisible: Bool
    var isActive: Bool = true
    
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared
    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?
    
    @State private var notificationStack: [Detection] = []
    @State private var isCameraPaused: Bool = false
    var body: some View {
        GeometryReader { geo in
            let lockedSafeZone = CGRect(
                x: geo.size.width * 0.15,
                y: geo.size.height * 0.20,
                width: geo.size.width * 0.70,
                height: geo.size.height * 0.45
            )
            
            ZStack(alignment: .center) {
                let blurAmount = infoView.infoView ? 10.0 : 0.0
                CameraPreview(
                    detector: detector,
                    zoomLevel: $zoomLevel,
                    showSafeZone: .constant(false),
                    safeZoneRect: .constant(lockedSafeZone),
                    onTap: { onTap() },
                    onPinch: onPinch,
                    isAIPaused: $isCameraPaused
                )
                .ignoresSafeArea()
                .blur(radius: blurAmount)
                
                if !isActive {
                    Color.black.ignoresSafeArea()
                }
                // Notification Stack positioned at bottom
                if !infoView.infoView {
                    VStack {
                        Spacer()
                        VStack(spacing: 12) {
                            ForEach(notificationStack) { detection in
                                NotificationPill(detection: detection) {
                                    openPopup(for: detection)
                                    withAnimation {
                                        notificationStack.removeAll { $0.id == detection.id }
                                    }
                                }
                                .transition(.move(edge: .bottom).combined(with: .scale(scale: 0.9)).combined(with: .opacity))
                            }
                        }
                        .padding(.bottom, geo.size.height * 0.24)
                        .padding(.horizontal, 16)
                    }
                    .zIndex(10)
                }
                if isActive {
                    Color.clear
                        .onChange(of: zoomLevel) { _, _ in
                            showZoomIndicatorThenFade()
                            onTap()
                        }
                        .onChange(of: detector.currentLabel) { _, newLabel in
                            withAnimation(.easeOut(duration: 0.1)) {
                                isDetecting = (newLabel != nil && !(newLabel!.isEmpty))
                            }
                        }
                        .onChange(of: detector.newlyDetectedLandmark) { _, newDetection in
                            handleNewDetection(newDetection)
                        }
                }
                // Popup Centered Modal Overlay
                if infoView.infoView {
                    Color.black.opacity(0.4)
                        .ignoresSafeArea()
                        .zIndex(19)
                        .onTapGesture {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                infoView.infoView = false
                            }
                        }
                    
                    PopUp()
                        .zIndex(20)
                        .transition(.scale(scale: 0.85).combined(with: .opacity))
                }
                if !infoView.infoView && zoomIndicatorVisible {
                    VStack {
                        Spacer()
                        Text(String(format: "%.1fx", zoomLevel))
                            .font(.caption.monospacedDigit())
                            .fontWeight(.bold)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
                            .padding(.bottom, 110)
                            .transition(.opacity)
                    }
                    .zIndex(5)
                }
            }
            .animation(.easeOut(duration: 0.25), value: zoomIndicatorVisible)
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: notificationStack)
            .onAppear {
                detector.dynamicSafeZone = lockedSafeZone
                // Explicitly set to false so the green bounding boxes stay visible for your testing
                detector.hideBoundingBoxes = false
                updatePauseState()
            }
            .onChange(of: geo.size) { _, _ in
                detector.dynamicSafeZone = lockedSafeZone
            }
            .onChange(of: isActive) { _, _ in updatePauseState() }
            .onChange(of: infoView.infoView) { _, _ in updatePauseState() }
        }
    }
    
    private func updatePauseState() {
        isCameraPaused = !isActive || infoView.infoView
    }
    
    private func handleNewDetection(_ detection: Detection?) {
        guard let detection = detection else { return }
        
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        
        withAnimation {
            // Insert at index 0 so new notifications push the old ones DOWN
            notificationStack.insert(detection, at: 0)
            
            // Limit increased to 3 popups
            if notificationStack.count > 3 {
                notificationStack.removeLast()
            }
        }
        
        let idToRemove = detection.id
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            withAnimation {
                notificationStack.removeAll { $0.id == idToRemove }
            }
        }
    }
    private func openPopup(for bestDetection: Detection) {
        if let entry = bestDetection.landmarkEntry {
            infoView.presentLandmark(
                entry,
                clusterId: Int(bestDetection.clusterID) ?? 0,
                trainingRunId: bestDetection.modelVersion,
                detectionConfidence: bestDetection.confidence
            )
        }
    }
    private func showZoomIndicatorThenFade() {
        zoomFadeTask?.cancel()
        zoomIndicatorVisible = true
        zoomFadeTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.25)) {
                    zoomIndicatorVisible = false
                }
            }
        }
    }
}
// MARK: - Notification Pill
struct NotificationPill: View {
    let detection: Detection
    let onTap: () -> Void
    
    @State private var isPulsing = false
    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 16) {
                ZStack {
                    LinearGradient(colors: [Color.blue, Color.purple], startPoint: .topLeading, endPoint: .bottomTrailing)
                        .frame(width: 48, height: 48)
                        .clipShape(Circle())
                        .shadow(color: Color.blue.opacity(0.35), radius: 6, x: 0, y: 3)
                    
                    Image(systemName: "viewfinder")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("Landmark Recognized")
                        .font(.system(size: 11, weight: .heavy, design: .rounded))
                        .foregroundStyle(Color.blue)
                        .textCase(.uppercase)
                        .opacity(0.9)
                    Text(detection.displayLabel)
                        .font(.system(size: 19, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .fill(.regularMaterial)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(isPulsing ? Color.blue.opacity(0.5) : Color.white.opacity(0.3), lineWidth: isPulsing ? 1.5 : 1)
            )
            .shadow(color: isPulsing ? Color.blue.opacity(0.15) : .black.opacity(0.15), radius: 18, x: 0, y: 8)
        }
        .buttonStyle(.plain)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                isPulsing = true
            }
        }
    }
}



