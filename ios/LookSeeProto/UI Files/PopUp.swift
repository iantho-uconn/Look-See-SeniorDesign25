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
    var body: some View {
        VStack {
            LandmarkInfo()
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
