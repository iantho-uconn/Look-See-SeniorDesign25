import SwiftUI
import CoreLocation

struct LandmarkScan: View {
    var onTap: () -> Void = {}
    var onPinch: () -> Void = {}
    
    @Binding var isDetecting: Bool
    @Binding var isNavVisible: Bool // Tells the Ad if the bottom nav is currently on screen
    
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared

    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?

    var body: some View {
        // GeometryReader fixes the iOS 26 UIScreen warning and perfectly aligns taps
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
                        // Tapping the background toggles the navigation menus
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

                // --- THE GREEN BOX TAP TARGET ---
                // If an object is found, this invisible button sits perfectly over the safe zone
                if let bestDetection = detector.detections.first, !infoView.infoView {
                    Rectangle()
                        .fill(Color.white.opacity(0.001)) // Invisible to the eye, but catches taps
                        .frame(width: lockedSafeZone.width, height: lockedSafeZone.height)
                        .position(x: lockedSafeZone.midX, y: lockedSafeZone.midY)
                        .onTapGesture {
                            // Open the PopUp!
                            infoView.landmarkName = bestDetection.displayLabel
                            infoView.landmarkConfidence = bestDetection.confidence * 100
                            infoView.landmarkDescription = bestDetection.landmarkEntry?.shortDescription ?? "Discover more about this location."
                            infoView.promoName = "Checking promotions..."
                            infoView.infoView = true
                            
                            // Bring the navigation back so it's ready when they close the popup
                            if !isNavVisible { onTap() }
                        }
                }

                // --- THE ALWAYS-ON STICKY AD BANNER ---
                if !infoView.infoView {
                    VStack {
                        Spacer()
                        
                        HStack(spacing: 16) {
                            Rectangle()
                                .fill(Color.gray.opacity(0.3))
                                .frame(width: 50, height: 50)
                                .cornerRadius(8)
                                .overlay(
                                    Image(systemName: "building.2.crop.circle")
                                        .font(.title2)
                                        .foregroundStyle(.white.opacity(0.8))
                                )
                            
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Sponsored Ad")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                
                                Text("Visit Local Business")
                                    .font(.system(size: 15, weight: .bold, design: .rounded))
                                    .foregroundStyle(.white)
                            }
                            
                            Spacer()
                        }
                        .padding(12)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                        .environment(\.colorScheme, .dark)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.2), lineWidth: 1))
                        .shadow(color: .black.opacity(0.4), radius: 10, y: 5)
                        .padding(.horizontal, 20)
                        // DYNAMIC PADDING: Slides up cleanly when the bottom nav appears
                        .padding(.bottom, isNavVisible ? 100 : 40)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .onTapGesture {
                            // Tapping the Ad also opens the info popup
                            if let bestDetection = detector.detections.first {
                                infoView.landmarkName = bestDetection.displayLabel
                                infoView.landmarkConfidence = bestDetection.confidence * 100
                                infoView.landmarkDescription = bestDetection.landmarkEntry?.shortDescription ?? "Discover more about this location."
                                infoView.promoName = "Checking promotions..."
                                infoView.infoView = true
                                if !isNavVisible { onTap() }
                            }
                        }
                    }
                }

                if infoView.infoView { PopUp() }

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
            .onAppear { detector.dynamicSafeZone = lockedSafeZone }
            .onChange(of: geo.size) { _, _ in detector.dynamicSafeZone = lockedSafeZone }
        }
    }

    private func showZoomIndicatorThenFade() {
        zoomFadeTask?.cancel()
        zoomIndicatorVisible = true
        zoomFadeTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.25)) { zoomIndicatorVisible = false }
            }
        }
    }
}
