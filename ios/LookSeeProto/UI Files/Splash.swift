//
//  Splash.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/8/25.
//

import SwiftUI

struct Splash: View {
    var body: some View {
        NavigationView {
            ZStack{
                LinearGradient(
                    gradient: Gradient(colors:[
                        Color(red: 0.22, green: 0.49, blue: 1.00),
                        Color(red: 0.95, green: 0.21, blue: 0.62)
                    ]),
                    startPoint: .top,
                    endPoint: .bottom
                )
                .edgesIgnoringSafeArea(.all)
                VStack{
                    VStack{
                        Image(systemName: "eye.square.fill")
                            .font(.system(size: 150))
                            .foregroundStyle(.white)
                            .padding()
                        Text("LookSee").bold()
                            .font(.title)
                            .foregroundStyle(.white)
                        Text("Explore landmarks and buildings around you")
                            .foregroundStyle(.white)
                    }
                    .frame(maxHeight: .infinity)
                    NavigationLink(destination: Main().toolbarVisibility(.hidden)) {
                        Text("Get Started")
                            .padding(.horizontal, 140)
                            .padding(.vertical, 20)
                            .background(.white)
                            .foregroundStyle(Color(red: 0.60, green: 0.06, blue: 0.98))
                            .cornerRadius(8)
                            .padding()
                    }
                }
            }
        }
    }
}

#Preview {
    Splash()
}
