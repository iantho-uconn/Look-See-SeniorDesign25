//
//  PopUp.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/17/26.
//

import SwiftUI

struct PopUp: View {
    @ObservedObject private var infoView = VariableContainer.shared

    var body: some View {
        VStack {
            LandmarkInfo()
                .frame(width: 350)
                .padding()
                .background(
                    .regularMaterial,
                    in: RoundedRectangle(cornerRadius: 8)
                )
                .scaleEffect(infoView.infoView ? 1.0 : 0.0)
                .animation(.bouncy, value: infoView.infoView)

            Button("Exit") {
                infoView.dismissLandmark()
            }
            .buttonStyle(.glass)
        }
    }
}

#Preview {
    PopUp()
}
