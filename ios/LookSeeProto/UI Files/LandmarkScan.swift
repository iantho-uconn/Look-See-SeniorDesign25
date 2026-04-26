import SwiftUI

struct LandmarkScan: View {
    
    @StateObject private var detector = Detector()
    @ObservedObject var infoView = VariableContainer.shared
    @State private var zoomLevel: CGFloat = 1.0  // ← add this
    
    var body: some View {
        ZStack {
            let blurAmount = infoView.infoView ? 5.0 : 0.0
            
            CameraPreview(detector: detector, zoomLevel: $zoomLevel)  // ← pass binding
                .ignoresSafeArea()
                .blur(radius: blurAmount)
            
            if infoView.infoView { PopUp() }
            
            // Zoom indicator — only show when zoomed in and popup is closed
            if !infoView.infoView {
                VStack {
                    Spacer()
                    Text(String(format: "%.1fx", zoomLevel))
                        .font(.caption)
                        .fontWeight(.semibold)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                        .padding(.bottom, 40)
                }
            }
        }
    }
}
