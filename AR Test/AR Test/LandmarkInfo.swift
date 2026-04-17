//
//  LandmarkInfo.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/17/26.
//

import SwiftUI

struct LandmarkInfo : View {
    @ObservedObject var infoView = Settings.shared
    @Binding var info : ScannedLandmark
    var body: some View {
        VStack {
            Text(info.name)
                .font(.title)
            Text("\(info.description ?? "No description is available for this landmark.") \n \(info.url ?? "")")
                .padding()
//                .lineLimit(5)
            Button("**Report incorrect info**",
                   systemImage: "flag.fill",
                   role: .destructive){}
            Divider().padding()
            HStack {
                Text("Confidence")
                Spacer()
                Text("\(info.confidence)%")
            }
            HStack {
                Text("Category")
                Spacer()
                Text("\(info.category)")
            }
            HStack {
                Text("Detection Time")
                Spacer()
                Text("\(info.detectionTime, specifier: "%.2f")s")
            }
            
        }
        
    }
}

#Preview {
    @Previewable @State var info = ScannedLandmark(id: 5, name: "Westminister Building", description: "As of 2025, the building is the eighth-tallest building in New York City, the tenth-tallest completed skyscraper in the United States, and the 59th-tallest completed skyscraper in the world.", url:"example.com", category: "Building", confidence: "1", detectionTime: 0.0)
    LandmarkInfo(info: $info)
}
