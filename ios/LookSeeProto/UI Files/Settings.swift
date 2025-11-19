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
    @State private var permissionCamera = true
    @State private var permissionLocation = true
    @State private var permissionStorage = true
    @State private var modal = false
    @State private var cache = 0
    var body: some View {
        NavigationStack{
            Form {
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
                    }
                    
                })
                Section{
                    Toggle("Online Recognition", isOn: $onlineMode)
                } header: {Text("Recognition Mode")}
                footer: {Text("Keeping Online Recognition on allows the app to be more accurate. Turning it off limits the range of landmark recognition.")}
                Section("App Permissions"){
                    Toggle("Camera access",
                           systemImage: "camera",
                            isOn: $permissionCamera)
                    Toggle("Location access",
                           systemImage: "mappin",
                           isOn: $permissionLocation)
                    Toggle("Storage access",
                           systemImage: "externaldrive",
                           isOn: $permissionStorage)
                }
                Section{
                    Button("Clear Cache",
                           systemImage: "externaldrive",
                           role: .destructive) {}
                } header: {Text("Data Management")}
                footer: {Text("Current cache size: \(cache) MB")}
                
                Section("Support & Info"){
                    Button("Help & Tutorial",
                           systemImage: "questionmark.circle") {}
                    Button("About Looksee",
                           systemImage: "info.circle") {
                        modal = true
                    }
                           .sheet(isPresented: $modal){
                               Text("Test")
                           }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

#Preview {
    Settings()
}

