//
//  LandmarkInfo.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/17/26.
//

import SwiftUI
import Foundation

struct LandmarkInfo : View {
    // Variable to allow the info pop-up to appear
    @ObservedObject var infoView = VariableContainer.shared
    let img = UIImageView()
    var body: some View {
        VStack {
            Text(infoView.landmarkName)
                .font(.title)
            Text("\(infoView.landmarkDescription)")
                .padding()
//                .lineLimit(5)
//            Button("**Report incorrect info**",
//                   systemImage: "flag.fill",
//                   role: .destructive){}
            if infoView.landmarkURL != "" {
                AsyncImage(url: URL(string: infoView.landmarkURL)) { image in
                    image.resizable().aspectRatio(contentMode: .fit)
                } placeholder: {
                    ProgressView()
                }
            }
            
            Divider().padding()
            HStack {
                Text("Confidence")
                Spacer()
                Text("\(infoView.landmarkConfidence, specifier: "%.2f")%")
            }
            HStack {
                Text("Category")
                Spacer()
                Text("\(infoView.landmarkCategory)")
            }
//            // Don't find this necessary
//            HStack {
//                Text("Detection Time")
//                Spacer()
//                Text("\(info.detectionTime, specifier: "%.2f")s")
//            }
        }
        
    }
}

//#Preview {
//    @Previewable @State var info = ScannedLandmark(id: 5, name: "Westminister Building", description: "As of 2025, the building is the eighth-tallest building in New York City, the tenth-tallest completed skyscraper in the United States, and the 59th-tallest completed skyscraper in the world.", url:"example.com", category: "Building", confidence: "1", detectionTime: 0.0)
//    LandmarkInfo(info: $info)
//}
