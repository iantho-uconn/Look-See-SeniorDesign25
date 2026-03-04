//
//  Buttons.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/25/26.

import SwiftUI

struct Buttons: View {
    @EnvironmentObject var vm: AuthViewModel
    var body: some View {
        NavigationStack {
            ZStack{
                TabView{
                    Tab("Scan", systemImage: "camera.aperture") {LandmarkScan()}
                    Tab("Record", systemImage: "video") {LandmarkRecord()} 
                }
                HStack{
                    Spacer()
                        .frame(width:20)
                    NavigationLink(){ Library(locations: landmarks)
                    } label: {
                        Label("Library", systemImage: "archivebox")
                            .labelStyle(.iconOnly)
                            .scaleEffect(2)
                    }
                    Spacer()
                    NavigationLink(){
                        Settings()
                            .environmentObject(vm)
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                            .labelStyle(.iconOnly)
                            .scaleEffect(2)
                    }
                    Spacer()
                        .frame(width:20)
                }.frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .top)
                .padding()
            }
        }
    }
}

//#Preview {
//    Buttons()
//}
