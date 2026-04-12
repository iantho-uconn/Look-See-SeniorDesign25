//
//  PopUp.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/17/26.
//

import SwiftUI

struct PopUp : View {
    // Variable to allow the info pop-up to appear
    @ObservedObject var infoView = VariableContainer.shared
    
    @State var info = ScannedLandmark(id: 5, name: "Westminister Building", description: "As of 2025, the building is the eighth-tallest building in New York City, the tenth-tallest completed skyscraper in the United States, and the 59th-tallest completed skyscraper in the world.", url:"example.com", category: "Building", confidence: "1", detectionTime: 0.0)
    var body: some View {
        VStack {
            LandmarkInfo(info : $info)
                .frame(width: 350)
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                .scaleEffect(infoView.infoView ? 1.0 : 0.0)
                .animation(.bouncy, value:infoView.infoView)
            Button("Exit") {infoView.infoView.toggle()}
                .buttonStyle(.glass)
        }
        
    }
}

#Preview {
    PopUp()
}
