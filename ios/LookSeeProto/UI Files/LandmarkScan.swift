import SwiftUI

struct LandmarkScan: View {
    var onTap: () -> Void = {}
    var onPinch: () -> Void = {}
    
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared

    @State private var zoomLevel: CGFloat = 1.0
    @State private var zoomIndicatorVisible = false
    @State private var zoomFadeTask: Task<Void, Never>?

    let lockedSafeZone = CGRect(
        x: UIScreen.main.bounds.width * 0.15,
        y: UIScreen.main.bounds.height * 0.20,
        width: UIScreen.main.bounds.width * 0.70,
        height: UIScreen.main.bounds.height * 0.45
    )

    var body: some View {
        ZStack {
            let blurAmount = infoView.infoView ? 5.0 : 0.0

            CameraPreview(
                detector: detector,
                zoomLevel: $zoomLevel,
                showSafeZone: .constant(false),
                safeZoneRect: .constant(lockedSafeZone),
                onTap: onTap,
                onPinch: onPinch,
                isAIPaused: .constant(false)
            )
            .ignoresSafeArea()
            .blur(radius: blurAmount)
            .onChange(of: zoomLevel) { _, _ in
                showZoomIndicatorThenFade()
                onTap() // Reset chrome timer when zoom changes
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
                        .background(
                            Color.black.opacity(0.6),
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                        .padding(.bottom, 110)
                        .transition(.opacity)
                }
            }
        }
        .contentShape(Rectangle())
        .animation(.easeOut(duration: 0.25), value: zoomIndicatorVisible)
        .onAppear {
            detector.dynamicSafeZone = lockedSafeZone
        }
    }

    private func showZoomIndicatorThenFade() {
        zoomFadeTask?.cancel()
        zoomIndicatorVisible = true

        zoomFadeTask = Task {
            try? await Task.sleep(for: .seconds(1.2))

            guard !Task.isCancelled else { return }

            await MainActor.run {
                withAnimation(.easeOut(duration: 0.25)) {
                    zoomIndicatorVisible = false
                }
            }
        }
    }
}
