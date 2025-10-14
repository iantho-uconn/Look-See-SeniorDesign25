import SwiftUI
internal import _LocationEssentials

struct ContentView: View {
    @StateObject private var loc = LocationManager()

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            // Camera fills the screen
            CameraPreview()
                .ignoresSafeArea()

            // Location overlay
            VStack(alignment: .leading, spacing: 6) {
                Text("Location")
                    .font(.headline).bold()
                if let c = loc.lastCoordinate {
                    Text(String(format: "Lat: %.6f\nLon: %.6f", c.latitude, c.longitude))
                        .font(.system(.body, design: .monospaced))
                } else {
                    Text("Requesting…")
                        .font(.system(.body, design: .monospaced))
                }
                if let acc = loc.lastAccuracyMeters {
                    Text(String(format: "± %.0f m", acc))
                        .font(.footnote).opacity(0.8)
                }
            }
            .padding(12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding()
        }
        .onAppear { loc.request() }
    }
}
#Preview {
    ContentView()
}
