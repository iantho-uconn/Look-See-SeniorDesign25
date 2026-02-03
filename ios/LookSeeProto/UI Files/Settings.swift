//
//  Settings.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/15/25.
//

import SwiftUI
import Foundation

struct Settings: View {
    @AppStorage("onlineMode") var onlineMode = true
    @AppStorage("permissionCamera") var permissionCamera = true
    @AppStorage("permissionLocation") var permissionLocation = true
    @AppStorage("permissionStorage") var permissionStorage = true
    @State private var modal = false
    @State private var showAlertAll = false
    @State private var showAlertCache = false
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
                            systemImage: "externaldrive"){showAlertCache = true}
                        .alert("Are you sure? This will delete all temporary data, including images.", isPresented: $showAlertCache){
                            Button("Cancel", role: .cancel) {}
                            Button("Yes", role: .destructive) {}
                        }
                    Button("Delete All Data",
                           systemImage: "externaldrive.badge.exclamationmark",
                           role: .destructive) {showAlertAll = true}
                        .alert("Are you sure? This will delete all stored data, including stored models and your landmark history.", isPresented: $showAlertAll){
                            Button("Cancel", role: .cancel) {}
                            Button("Yes", role: .destructive) {}
                        }
                    
                } header: {Text("Data Management")}
                footer: {Text("Current cache size: \(cache) MB")}
                
                Section("Support & Info"){
                    NavigationLink(){ Help()
                    } label: {
                        Label("Help & Tutorial", systemImage: "questionmark.circle")
                            .foregroundColor(.blue)
                    }
                    Button("About LookSee",
                           systemImage: "info.circle") {
                        modal = true
                    }
                           .sheet(isPresented: $modal){
                               Text("Looksee is an application designed to help you identify local landmarks with ease.")
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

