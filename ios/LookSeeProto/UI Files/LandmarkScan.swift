//
//  LandmarkScan.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct LandmarkScan: View {
    var body: some View {
        ZStack {
            LinearGradient(
                gradient: Gradient(colors:[
                    Color(red: 1.0, green: 1.0, blue: 1.00),
                    Color(red: 0.95, green: 0.21, blue: 0.62)
                ]),
                startPoint: .top,
                endPoint: .bottom
            ).edgesIgnoringSafeArea(.all)
            Text("This is the scanning screen.")
        }
    }
}

#Preview {
    LandmarkScan()
}
