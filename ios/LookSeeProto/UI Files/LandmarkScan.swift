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
    @Binding var isNavVisible: Bool // Tells the Ad if the bottom nav is currently on screen
    
    // Defaults to true so existing call sites do not need to pass it.
    var isActive: Bool = true
    
    @StateObject private var detector = Detector()
    @ObservedObject private var infoView = VariableContainer.shared
    
    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?
    @State private var promotionFetchTask: Task<Void, Never>?

    @State private var notificationStack: [Detection] = []
    @State private var isCameraPaused = false

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
                    onTap: onTap,
                    onPinch: onPinch,
                    isAIPaused: $isCameraPaused
                )
                .ignoresSafeArea()
                .blur(radius: blurAmount)
                .onChange(of: zoomLevel) { _, _ in
                    showZoomIndicatorThenFade()
                    onTap()
                }
                .onChange(of: detector.currentLabel) { _, newLabel in
                    withAnimation(.easeOut(duration: 0.1)) {
                        isDetecting = isActive &&
                            newLabel?.trimmingCharacters(
                                in: .whitespacesAndNewlines
                            ).isEmpty == false
                    }
                }
                .onChange(of: detector.newlyDetectedLandmark) { _, newDetection in
                    guard isActive, !infoView.infoView else {
                        return
                    }
                    handleNewDetection(newDetection)
                }

                if !isActive {
                    Color.black
                        .ignoresSafeArea()
                        .zIndex(2)
                }

                // Invisible tap target over the detection safe zone.
                if isActive,
                   let bestDetection = detector.detections.first,
                   !infoView.infoView {
                    Rectangle()
                        .fill(Color.white.opacity(0.001))
                        .frame(
                            width: lockedSafeZone.width,
                            height: lockedSafeZone.height
                        )
                        .position(
                            x: lockedSafeZone.midX,
                            y: lockedSafeZone.midY
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            openPopup(for: bestDetection)
                        }
                        .zIndex(4)
                }

                // Detection notifications.
                if isActive, !infoView.infoView {
                    VStack {
                        Spacer()

                        VStack(spacing: 12) {
                            ForEach(notificationStack) { detection in
                                NotificationPill(detection: detection) {
                                    openPopup(for: detection)

                                    withAnimation {
                                        notificationStack.removeAll {
                                            $0.id == detection.id
                                        }
                                    }
                                }
                                .transition(
                                    .move(edge: .bottom)
                                        .combined(with: .scale(scale: 0.9))
                                        .combined(with: .opacity)
                                )
                            }
                        }
                        .padding(.bottom, geo.size.height * 0.24)
                        .padding(.horizontal, 16)
                    }
                    .zIndex(10)
                }

                // Centered popup overlay.
                if infoView.infoView {
                    Color.black
                        .opacity(0.4)
                        .ignoresSafeArea()
                        .zIndex(19)
                        .onTapGesture {
                            withAnimation(
                                .spring(
                                    response: 0.35,
                                    dampingFraction: 0.8
                                )
                            ) {
                                infoView.infoView = false
                            }
                        }

                    PopUp()
                        .zIndex(20)
                        .transition(
                            .scale(scale: 0.85)
                                .combined(with: .opacity)
                        )
                }

                if isActive,
                   !infoView.infoView,
                   zoomIndicatorVisible {
                    VStack {
                        Spacer()

                        Text(String(format: "%.1fx", zoomLevel))
                            .font(.caption.monospacedDigit())
                            .fontWeight(.bold)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(
                                Color.black.opacity(0.6),
                                in: RoundedRectangle(cornerRadius: 12)
                            )
                            .padding(.bottom, 110)
                            .transition(.opacity)
                    }
                    .zIndex(5)
                }
            }
            .animation(
                .easeOut(duration: 0.25),
                value: zoomIndicatorVisible
            )
            .animation(
                .spring(response: 0.35, dampingFraction: 0.8),
                value: notificationStack
            )
            .onAppear {
                detector.dynamicSafeZone = lockedSafeZone
                // Keep the green detection boxes visible while testing.
                detector.hideBoundingBoxes = false
                updatePauseState()
            }
            .onChange(of: geo.size) { _, _ in
                detector.dynamicSafeZone = lockedSafeZone
            }
            .onChange(of: isActive) { _, _ in
                updatePauseState()
            }
            .onChange(of: infoView.infoView) { _, _ in
                updatePauseState()
            }
            .onDisappear {
                promotionFetchTask?.cancel()
                zoomFadeTask?.cancel()
                isCameraPaused = true
                isDetecting = false
            }
        }
    }

    // MARK: - Internal Methods
    private func openPopup(for detection: Detection) {
        promotionFetchTask?.cancel()

        guard let entry = detection.landmarkEntry else {
            infoView.landmarkId = ""
            infoView.landmarkName = detection.displayLabel
            infoView.landmarkConfidence = detection.confidence * 100
            infoView.landmarkDescription =
                "Discover more about this location."
            infoView.landmarkURL = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.infoView = true

            if !isNavVisible {
                onTap()
            }

            return
        }

        infoView.presentLandmark(
            entry,
            clusterId: Int(detection.clusterID) ?? 0,
            trainingRunId: detection.modelVersion,
            detectionConfidence: detection.confidence
        )

        let landmarkId = entry.landmarkId
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if landmarkId.isEmpty {
            print(
                "⚠️ No landmarkId found on detection. Cannot fetch active promotion."
            )
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
        } else {
            print(
                "🔎 Checking active promotions for landmarkId: \(landmarkId)"
            )
            infoView.promoName = "Checking promotions..."
            infoView.promoDescription = ""
            fetchActivePromotion(for: landmarkId)
        }

        if !isNavVisible {
            onTap()
        }
    }

    private func fetchActivePromotion(for landmarkId: String) {
        promotionFetchTask?.cancel()

        promotionFetchTask = Task {
            do {
                let promotion = try await ActivePromotionService()
                    .fetchTopActivePromotion(landmarkId: landmarkId)

                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        print(
                            "ℹ️ Ignoring stale promotion response for \(landmarkId)"
                        )
                        return
                    }

                    if let promotion {
                        print(
                            "✅ Active promotion found for \(landmarkId): \(promotion.name)"
                        )
                        infoView.promoName = promotion.name
                        infoView.promoDescription = promotion.description
                    } else {
                        print(
                            "ℹ️ No active promotion returned for \(landmarkId)"
                        )
                        infoView.promoName = "No active promotion"
                        infoView.promoDescription = ""
                    }
                }
            } catch {
                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        print(
                            "ℹ️ Ignoring stale promotion error for \(landmarkId)"
                        )
                        return
                    }

                    print(
                        "❌ Failed to fetch active promotion for \(landmarkId): \(error.localizedDescription)"
                    )
                    infoView.promoName = "No active promotion"
                    infoView.promoDescription = ""
                }
            }
        }
    }

    private func updatePauseState() {
        isCameraPaused = !isActive || infoView.infoView

        if !isActive {
            isDetecting = false
        }
    }

    private func handleNewDetection(_ detection: Detection?) {
        guard let detection else {
            return
        }

        // Avoid stacking duplicate notifications for the same detection.
        guard !notificationStack.contains(where: { $0.id == detection.id }) else {
            return
        }

        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()

        withAnimation {
            notificationStack.insert(detection, at: 0)

            if notificationStack.count > 3 {
                notificationStack.removeLast()
            }
        }

        let idToRemove = detection.id

        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            withAnimation {
                notificationStack.removeAll {
                    $0.id == idToRemove
                }
            }
        }
    }

    private func showZoomIndicatorThenFade() {
        zoomFadeTask?.cancel()
        zoomIndicatorVisible = true

        zoomFadeTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)

            guard !Task.isCancelled else {
                return
            }

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
