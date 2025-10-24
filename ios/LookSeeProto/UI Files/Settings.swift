//
//  Settings.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/15/25.
//

import SwiftUI

struct Settings: View {
    @State private var onlineMode = true
    @State private var offlineMode = false
    @State private var popUp = false
    var body: some View {
        VStack{
            HStack{
                Button("Settings", systemImage:"arrow.backward"){
                    //person.crop.circle
                }
                .padding()
                Spacer()
            }
            ScrollView {
                Button(action: {
                    print("Button tapped!")
                }, label: {
                    HStack {
                        Image(systemName: "person.crop.circle")
                            .font(.system(size: 50))
                        VStack{
                            Text("Guest User")
                            Text("guest@looksee.app")
                        }
                        
                        Image(systemName: "chevron.right")
                    }
                })
                .buttonStyle(.bordered)
                
                Divider()
                
                HStack{
                    Text("Recognition Mode")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                Toggle(isOn: $onlineMode){
                    HStack{
                        Image(systemName: "wifi")
                            .padding(5)
                        VStack(alignment: .leading){
                            Text("Online Recognition")
                            Text("More accurate, requires internet")
                                .font(.subheadline)
                                .foregroundStyle(.gray)
                        }
                    }
                }
                .padding()
                
                Toggle(isOn: $offlineMode){
                    HStack{
                        Image(systemName: "wifi.slash")
                            .padding(5)
                        VStack(alignment: .leading){
                            Text("Offline Mode")
                            Text("Works without internet")
                                .font(.subheadline)
                                .foregroundStyle(.gray)
                        }
                        
                    }
                }
                .padding()
                
                Divider()
                
                HStack{
                    Text("App Permissions")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                
                
                Divider()
                
                HStack{
                    Text("Data Management")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                Button(action: {
                    print("Button tapped!")
                },
                    label: {
                        HStack{
                            Image(systemName: "xmark.bin")
                                .padding(5)
                            VStack(alignment: .leading){
                                Text("Clear cache")
                                Text("Free up 124 MB")
                                    .font(.subheadline)
                                    .foregroundStyle(.gray)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                        }
                    }
                )
//                .confirmationDialogue(
//                    "Test",
//                    isPresented: $popUp,
//                    titleVisibility: .visible
//                ) {
//                    Button("Yes", role: .destructive) {}
//                }
                .padding()
                
                Divider()
                
                HStack{
                    Text("Support & Info")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
            }
        }
    }
}

#Preview {
    Settings()
}

