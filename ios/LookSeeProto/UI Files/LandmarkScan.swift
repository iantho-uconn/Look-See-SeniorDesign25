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

    // Use the lowest values previously available in the sliders.
    @StateObject private var detector: Detector = {
        let detector = Detector()
        detector.confidenceThreshold = 0.1
        detector.ThresholdRangemultiplier = 0.1
        return detector
    }()

    @ObservedObject private var infoView = VariableContainer.shared
    @EnvironmentObject var vm: AuthViewModel

    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?
    @State private var liveInfoFetchTask: Task<Void, Never>?

    @State private var isCameraPaused = false
    @State private var isWarmingUp = true

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

                // Hide the physical lens snapping during camera warmup.
                if isWarmingUp {
                    Color.black
                        .ignoresSafeArea()
                        .zIndex(8)
                        .transition(.opacity)
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
            .onAppear {
                detector.dynamicSafeZone = lockedSafeZone
                detector.hideBoundingBoxes = false
                updatePauseState()

                // Mask the camera snapping effect during initialization.
                isWarmingUp = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) {
                    withAnimation(.easeOut(duration: 0.3)) {
                        isWarmingUp = false
                    }
                }
            }
            .onChange(of: geo.size) { _, _ in
                detector.dynamicSafeZone = lockedSafeZone
            }
            .onChange(of: isActive) { _, active in
                updatePauseState()

                if active {
                    isWarmingUp = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) {
                        withAnimation(.easeOut(duration: 0.3)) {
                            isWarmingUp = false
                        }
                    }
                }
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

    private func getCityName(
        latitude: Double,
        longitude: Double
    ) async -> String? {
        let location = CLLocation(
            latitude: latitude,
            longitude: longitude
        )
        let geocoder = CLGeocoder()

        do {
            let placemarks = try await geocoder.reverseGeocodeLocation(location)

            if let place = placemarks.first {
                let city = place.locality ?? ""
                let state = place.administrativeArea ?? ""

                if !city.isEmpty && !state.isEmpty {
                    return "\(city), \(state)"
                }

                return city.isEmpty ? state : city
            }
        } catch {
            print("Geocoding error: \(error)")
        }

        return nil
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

        let landmarkId = entry.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)

        // 🚀 NEW: Block the popup if the landmark was explicitly marked inactive!
        if let currentInfo = VariableContainer.shared.allLandmarks.first(where: { $0.id == landmarkId }) {
            if currentInfo.isActive == false {
                print("🚫 Blocked popup for inactive landmark: \(landmarkId)")
                return
            }
        }

        infoView.presentLandmark(
            entry,
            clusterId: Int(detection.clusterID) ?? 0,
            trainingRunId: detection.modelVersion,
            detectionConfidence: detection.confidence
        )

        if vm.tier == "business" || vm.hasActiveSubscription {
            let lat = entry.latitude ?? 0.0
            let lon = entry.longitude ?? 0.0
            let displayLabel = detection.displayLabel
            let lId = entry.landmarkId
            let cachedImg = infoView.merchantLogoUrl

            Task {
                let cityString = await getCityName(
                    latitude: lat,
                    longitude: lon
                ) ?? "Unknown Location"

                await vm.logScanHistory(
                    landmarkId: lId,
                    label: displayLabel,
                    location: cityString,
                    latitude: lat,
                    longitude: lon,
                    imageUrl: cachedImg
                )
            }
        }

        let landmarkId = entry.landmarkId.trimmingCharacters(
            in: .whitespacesAndNewlines
        )

        if landmarkId.isEmpty {
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        } else {
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

                guard !Task.isCancelled else { return }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else { return }
                    applyLiveInfo(liveInfo, landmarkId: landmarkId)
                }
            } catch {
                guard !Task.isCancelled else { return }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else { return }
                    print("⚠️ Live landmark info unavailable. Keeping manifest fallback.")
                }
            }
        }
    }

    @MainActor
    private func applyLiveInfo(
        _ liveInfo: LiveLandmarkInfoResponse,
        landmarkId: String
    ) {
        let liveLabel = liveInfo.label.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let liveDescription = liveInfo.shortDescription.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let liveWebsiteUrl = liveInfo.websiteUrl?.trimmingCharacters(
            in: .whitespacesAndNewlines
        ) ?? ""

        if !liveLabel.isEmpty {
            infoView.landmarkName = liveLabel
        }

        if !liveDescription.isEmpty {
            infoView.landmarkDescription = liveDescription
        }

        infoView.landmarkWebsiteUrl = liveWebsiteUrl

        // 🚀 If the backend confirms it is inactive, immediately close the popup
        if liveInfo.isActive == false {
            infoView.infoView = false
            return
        }

        if let promotion = liveInfo.activePromotion {
            let promoName = promotion.name.trimmingCharacters(
                in: .whitespacesAndNewlines
            )
            let promoDescription = promotion.description.trimmingCharacters(
                in: .whitespacesAndNewlines
            )
            let promoImageUrl = promotion.imageUrl?.trimmingCharacters(
                in: .whitespacesAndNewlines
            ) ?? ""

            if !promoName.isEmpty {
                infoView.promoName = promoName
                infoView.promoDescription = promoDescription
                infoView.promoImageUrl = promoImageUrl
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
    }

    private func updatePauseState() {
        isCameraPaused = !isActive

        if !isActive {
            isDetecting = false
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
