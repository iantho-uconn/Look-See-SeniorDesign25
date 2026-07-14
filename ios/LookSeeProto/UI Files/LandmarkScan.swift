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
    @State private var promotionFetchTask: Task<Void, Never>?

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
        }
    }

    private func openPopup(for bestDetection: Detection) {
        promotionFetchTask?.cancel()

        let landmarkId = bestDetection.landmarkEntry?.landmarkId ?? ""

        infoView.landmarkId = landmarkId
        infoView.landmarkName = bestDetection.displayLabel
        infoView.landmarkConfidence = bestDetection.confidence * 100
        infoView.landmarkDescription = bestDetection.landmarkEntry?.shortDescription ?? "Discover more about this location."
        infoView.landmarkURL = ""

        if landmarkId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
        } else {
            infoView.promoName = "Checking promotions..."
            infoView.promoDescription = ""
            fetchActivePromotion(for: landmarkId)
        }

        infoView.infoView = true
    }

    private func fetchActivePromotion(for landmarkId: String) {
        promotionFetchTask = Task {
            do {
                let promotion = try await ActivePromotionService()
                    .fetchTopActivePromotion(landmarkId: landmarkId)

                guard !Task.isCancelled else {
                    return
                }

                await MainActor.run {
                    guard infoView.landmarkId == landmarkId else {
                        return
                    }

                    if let promotion {
                        infoView.promoName = promotion.name
                        infoView.promoDescription = promotion.description
                    } else {
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
                        return
                    }

                    print("⚠️ Failed to fetch active promotion for \(landmarkId): \(error)")
                    infoView.promoName = "No active promotion"
                    infoView.promoDescription = ""
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
