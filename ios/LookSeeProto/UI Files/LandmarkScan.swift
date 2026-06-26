import SwiftUI

struct LandmarkScan: View {
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared
    
    @State private var zoomLevel: CGFloat = 1.0
    @State private var showSafeZone: Bool = true
    @State private var isAIPaused: Bool = false
    
    // LOCKED SAFE ZONE: Permanently placed exactly where you want it.
    // Leaves room at the top for labels, and stops before the buttons.
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
                showSafeZone: $showSafeZone,
                safeZoneRect: .constant(lockedSafeZone),
                isAIPaused: $isAIPaused
            )
            .ignoresSafeArea()
            .blur(radius: blurAmount)
            
            if infoView.infoView { PopUp() }
            
            // Centered Bottom HUD Controls (No Overlap)
            if !infoView.infoView {
                VStack(spacing: 16) {
                    Spacer()
                    
                    // Zoom indicator safely below the bounding box
                    Text(String(format: "%.1fx", zoomLevel))
                        .font(.caption.monospacedDigit())
                        .fontWeight(.bold)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
                    
                    // 3 Buttons Layout
                    HStack(spacing: 24) {
                        Button { showSafeZone.toggle() } label: {
                            Image(systemName: "viewfinder")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundStyle(showSafeZone ? Color(red: 0.0, green: 0.8, blue: 1.0) : .white)
                                .frame(width: 54, height: 54)
                                .background(Color.black.opacity(0.6))
                                .clipShape(Circle())
                        }
                        
                        Button {
                            isAIPaused.toggle()
                            detector.isPaused = isAIPaused
                        } label: {
                            Image(systemName: isAIPaused ? "play.fill" : "pause.fill")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundStyle(isAIPaused ? .green : .orange)
                                .frame(width: 64, height: 64)
                                .background(Color.black.opacity(0.6))
                                .clipShape(Circle())
                        }
                        
                        // FIXED TRASH BUG: It now ONLY wipes the screen, it does not unpause the AI.
                        Button {
                            detector.resetEngine()
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundStyle(.red)
                                .frame(width: 54, height: 54)
                                .background(Color.black.opacity(0.6))
                                .clipShape(Circle())
                        }
                    }
                    .padding(.bottom, 40) // Keeps it safely above the tab bar
                }
            }
        }
        .onAppear { detector.dynamicSafeZone = lockedSafeZone }
    }
}
