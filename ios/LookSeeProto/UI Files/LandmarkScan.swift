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
    @State private var liveInfoFetchTask: Task<Void, Never>?

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
                    isAIPaused: $isCameraPaused,
                    onBoxTap: { detection in
                        // THIS NOW OPENS THE SLIDE-UP SHEET WHEN THE GREEN BOX IS TAPPED!
                        openPopup(for: detection)
                    }
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

                if !isActive {
                    Color.black
                        .ignoresSafeArea()
                        .zIndex(2)
                }

                // PopUp is presented by Buttons at the root level so it
                // always appears above the app chrome.

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
                liveInfoFetchTask?.cancel()
                zoomFadeTask?.cancel()
                isCameraPaused = true
                isDetecting = false
            }
        }
    }

    // MARK: - Internal Methods
    private func openPopup(for detection: Detection) {
        liveInfoFetchTask?.cancel()

        guard let entry = detection.landmarkEntry else {
            infoView.landmarkId = ""
            infoView.landmarkName = detection.displayLabel
            infoView.landmarkConfidence = detection.confidence * 100
            infoView.landmarkDescription = "Discover more about this location."
            infoView.landmarkURL = ""
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
            infoView.infoView = true

            return
        }

        // Open immediately from the local manifest.
        infoView.presentLandmark(
            entry,
            clusterId: Int(detection.clusterID) ?? 0,
            trainingRunId: detection.modelVersion,
            detectionConfidence: detection.confidence
        )

        let landmarkId = entry.landmarkId
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if landmarkId.isEmpty {
            print("⚠️ No landmarkId found on detection. Using manifest fallback only.")
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        } else {
            print("🔎 Fetching live landmark info for landmarkId: \(landmarkId)")
            fetchLiveLandmarkInfo(for: landmarkId)
        }

    }

    private func fetchLiveLandmarkInfo(for landmarkId: String) {
        liveInfoFetchTask?.cancel()

        liveInfoFetchTask = Task {
            do {
                let liveInfo = try await LiveLandmarkInfoService()
                    .fetchLiveInfo(
                        landmarkId: landmarkId,
                        timeoutSeconds: 2.5
                    )

                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        print("ℹ️ Ignoring stale live-info response for \(landmarkId)")
                        return
                    }

                    applyLiveInfo(liveInfo, landmarkId: landmarkId)
                }
            } catch {
                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        print("ℹ️ Ignoring stale live-info error for \(landmarkId)")
                        return
                    }

                    print("⚠️ Live landmark info unavailable for \(landmarkId). Keeping manifest fallback. Error: \(error.localizedDescription)")
                }
            }
        }
    }

    @MainActor
    private func applyLiveInfo(
        _ liveInfo: LiveLandmarkInfoResponse,
        landmarkId: String
    ) {
        let liveLabel = liveInfo.label
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let liveDescription = liveInfo.shortDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let liveWebsiteUrl = liveInfo.websiteUrl?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !liveLabel.isEmpty {
            infoView.landmarkName = liveLabel
        }

        if !liveDescription.isEmpty {
            infoView.landmarkDescription = liveDescription
        }

        infoView.landmarkWebsiteUrl = liveWebsiteUrl

        if !liveWebsiteUrl.isEmpty {
            print("🔗 Live website URL applied for \(landmarkId): \(liveWebsiteUrl)")
        } else {
            print("ℹ️ No live website URL returned for \(landmarkId)")
        }

        if liveInfo.isActive == false {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
            print("ℹ️ Live landmark info says \(landmarkId) is inactive.")
            return
        }

        if let promotion = liveInfo.activePromotion {
            let promoName = promotion.name
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let promoDescription = promotion.description
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let promoImageUrl = promotion.imageUrl?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            if !promoName.isEmpty {
                infoView.promoName = promoName
                infoView.promoDescription = promoDescription
                infoView.promoImageUrl = promoImageUrl

                if !promoImageUrl.isEmpty {
                    print("🖼️ Live promotion image URL applied for \(landmarkId): \(promoImageUrl)")
                }
            } else {
                infoView.promoName = "No active promotion"
                infoView.promoDescription = ""
                infoView.promoImageUrl = ""
            }
        } else {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        }

        print("✅ Live landmark info applied for \(landmarkId)")
    }

    private func updatePauseState() {
        isCameraPaused = !isActive || infoView.infoView

        if !isActive {
            isDetecting = false
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
