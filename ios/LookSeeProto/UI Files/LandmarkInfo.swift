import SwiftUI
import Foundation

struct LandmarkInfo: View {
    @ObservedObject var infoView = VariableContainer.shared
    
    var body: some View {
        VStack {
            Text(infoView.landmarkName)
                .font(.title)
            
            Text(infoView.landmarkDescription)
                .padding()
            
            if infoView.landmarkURL != "" {
                AsyncImage(url: URL(string: infoView.landmarkURL)) { image in
                    image.resizable().aspectRatio(contentMode: .fit)
                } placeholder: {
                    ProgressView()
                }
            }
            
            // Promotion section — only shown when an active promo exists
            if infoView.promoName != "No active promotion" && !infoView.promoName.isEmpty {
                Divider().padding()
                
                VStack(alignment: .leading, spacing: 4) {
                    Label("Active Promotion", systemImage: "tag.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(infoView.promoName)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                    if !infoView.promoDescription.isEmpty {
                        Text(infoView.promoDescription)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
                .padding(.horizontal)
            }
            
        }
    }
}
