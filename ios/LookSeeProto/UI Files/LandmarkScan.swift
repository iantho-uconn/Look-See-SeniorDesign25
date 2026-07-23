import SwiftUI
import CoreLocation

struct LandmarkScan: View {
    var onTap: () -> Void = {}
    var onPinch: () -> Void = {}
    
    @Binding var isDetecting: Bool
    @Binding var isNavVisible: Bool
    
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared

    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?
    @State private var liveInfoFetchTask: Task<Void, Never>?

    var body: some View {
        GeometryReader { geo in
            let lockedSafeZone = CGRect(
                x: geo.size.width * 0.15,
                y: geo.size.height * 0.20,
                width: geo.size.width * 0.70,
                height: geo.size.height * 0.45
            )
            
            ZStack {
                let blurAmount = infoView.infoView ? 5.0 : 0.0

                CameraPreview(
                    detector: detector,
                    zoomLevel: $zoomLevel,
                    showSafeZone: .constant(false),
                    safeZoneRect: .constant(lockedSafeZone),
                    onTap: {
                        onTap()
                    },
                    onPinch: onPinch,
                    isAIPaused: .constant(false)
                )
                .ignoresSafeArea()
                .blur(radius: blurAmount)
                .onChange(of: zoomLevel) { _, _ in
                    showZoomIndicatorThenFade()
                    onTap()
                }
                .onChange(of: detector.currentLabel) { _, newLabel in
                    withAnimation(.easeOut(duration: 0.1)) {
                        isDetecting = (newLabel != nil && !(newLabel!.isEmpty))
                    }
                }

                if let bestDetection = detector.detections.first, !infoView.infoView {
                    Rectangle()
                        .fill(Color.white.opacity(0.001))
                        .frame(width: lockedSafeZone.width, height: lockedSafeZone.height)
                        .position(x: lockedSafeZone.midX, y: lockedSafeZone.midY)
                        .onTapGesture {
                            openPopup(for: bestDetection)

                            if !isNavVisible {
                                onTap()
                            }
                        }
                }

                if infoView.infoView {
                    PopUp()
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
                }
            }
            .animation(.easeOut(duration: 0.25), value: zoomIndicatorVisible)
            .onAppear {
                detector.dynamicSafeZone = lockedSafeZone
            }
            .onChange(of: geo.size) { _, _ in
                detector.dynamicSafeZone = lockedSafeZone
            }
            .onDisappear {
                liveInfoFetchTask?.cancel()
                zoomFadeTask?.cancel()
            }
        }
    }

    private func openPopup(for bestDetection: Detection) {
        liveInfoFetchTask?.cancel()

        let landmarkId = bestDetection.landmarkEntry?.landmarkId ?? ""

        let manifestDescription = bestDetection.landmarkEntry?.shortDescription
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        infoView.landmarkId = landmarkId
        infoView.landmarkName = bestDetection.displayLabel
        infoView.landmarkConfidence = bestDetection.confidence * 100
        infoView.landmarkDescription = manifestDescription.isEmpty
            ? "Discover more about this location."
            : manifestDescription
        infoView.landmarkURL = ""

        infoView.promoName = "No active promotion"
        infoView.promoDescription = ""

        infoView.infoView = true

        if landmarkId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            print("⚠️ No landmarkId found on detection. Using manifest fallback only.")
            return
        }

        print("🔎 Fetching live landmark info for landmarkId: \(landmarkId)")
        fetchLiveLandmarkInfo(for: landmarkId)
    }

    private func fetchLiveLandmarkInfo(for landmarkId: String) {
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

                    let liveLabel = liveInfo.label.trimmingCharacters(in: .whitespacesAndNewlines)
                    let liveDescription = liveInfo.shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)

                    if !liveLabel.isEmpty {
                        infoView.landmarkName = liveLabel
                    }

                    if !liveDescription.isEmpty {
                        infoView.landmarkDescription = liveDescription
                    }

                    if let promotion = liveInfo.activePromotion {
                        let promoName = promotion.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        let promoDescription = promotion.description.trimmingCharacters(in: .whitespacesAndNewlines)

                        if !promoName.isEmpty {
                            infoView.promoName = promoName
                            infoView.promoDescription = promoDescription
                        } else {
                            infoView.promoName = "No active promotion"
                            infoView.promoDescription = ""
                        }
                    } else {
                        infoView.promoName = "No active promotion"
                        infoView.promoDescription = ""
                    }

                    print("✅ Live landmark info applied for \(landmarkId)")
                }
            } catch {
                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        return
                    }

                    print("⚠️ Live landmark info unavailable for \(landmarkId). Keeping manifest fallback. Error: \(error.localizedDescription)")
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
