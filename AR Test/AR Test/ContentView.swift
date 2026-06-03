//
//  ContentView.swift
//  AR Test
//
//  Created by Christian Barbara on 4/7/26.
//

import SwiftUI
import Combine
import RealityKit

struct ContentView: View {
    @ObservedObject var infoView = Settings.shared

    var body: some View {
        NavigationView {
            ZStack {
                ARViewContainer()
                    .ignoresSafeArea(.all)
                if(infoView.infoView){PopUp()}
                
//                if(!infoView.infoView){
//                    ARViewContainer()
//                        .ignoresSafeArea(.all)
////                        .gesture(TapGesture().targetedToAnyEntity().onEnded{_ in infoView.infoView.toggle()})
//                }
//                else{Help()}
            }
        }
    }
}
