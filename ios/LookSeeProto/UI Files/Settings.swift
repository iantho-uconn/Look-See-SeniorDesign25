//
//  Settings.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/15/25.
//

import SwiftUI
import Foundation

struct Settings: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    @AppStorage("onlineMode") var onlineMode = true
    @AppStorage("permissionCamera") var permissionCamera = true
    @AppStorage("permissionLocation") var permissionLocation = true
    @AppStorage("permissionStorage") var permissionStorage = true
    @State private var modal = false
    @State private var showAlertAll = false
    @State private var showAlertCache = false
    @State private var showAlertSignOut = false
    @State private var cache = 0
    @State private var showModelInfo = false
    @State private var showDeleteModelAlert = false

    var body: some View {
        NavigationStack {
            Form {
                // MARK: - Profile
                if authState.tier == .guest {
                    // Guest profile — tap to go to signup
                    Button {
                        dismiss()
                        authState.didSignOut = true
                    } label: {
                        HStack {
                            Image(systemName: "person.crop.circle")
                                .font(.system(size: 50))
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Guest User")
                                    .foregroundStyle(.primary)
                                Text("Tap here to sign up")
                                    .font(.caption)
                                    .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            }
                        }
                    }
                } else {
                    Button(action: {
                        print("Profile tapped")
                    }, label: {
                        HStack {
                            Image(systemName: "person.crop.circle")
                                .font(.system(size: 50))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(vm.userEmail.isEmpty ? "Loading..." : vm.userEmail)
                                    .foregroundStyle(.primary)
                                if authState.tier == .business {
                                    Text("Business Account")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text("Authenticated User")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    })
                    .task {
                        await vm.fetchUserEmail()
                    }
                }

                Section {
                    Toggle("Online Recognition", isOn: $onlineMode)
                } header: { Text("Recognition Mode") }
                footer: { Text("Keeping Online Recognition on allows the app to be more accurate. Turning it off limits the range of landmark recognition.") }

                // MARK: - Model Management
                Section {
                    Button("Load Model", systemImage: "arrow.down.circle") {
                        // TODO: call your ModelService to load model based on location
                    }
                    Button("Reload Model", systemImage: "arrow.clockwise.circle") {
                        // TODO: call your ModelService to re-download current model
                    }
                    Button("Check for Updates", systemImage: "cloud.circle") {
                        // TODO: ping AWS to check if a newer model is available
                    }
                    Button("Model Info", systemImage: "info.circle") {
                        showModelInfo = true
                    }
                    .sheet(isPresented: $showModelInfo) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Model Info")
                                .font(.headline)
                            Text("Name: —")
                            Text("Version: —")
                            Text("Region: —")
                            Text("Size: —")
                            Text("Last Updated: —")
                        }
                        .padding()
                        .presentationDetents([.medium])
                    }
                    Button("Delete Model", systemImage: "trash", role: .destructive) {
                        showDeleteModelAlert = true
                    }
                    .alert("Delete Model?", isPresented: $showDeleteModelAlert) {
                        Button("Cancel", role: .cancel) {}
                        Button("Delete", role: .destructive) {
                            // TODO: remove downloaded model from local storage
                        }
                    } message: {
                        Text("This will remove the downloaded model from your device. You will need to reload it to use landmark recognition.")
                    }
                } header: { Text("Model Management") }
                footer: { Text("Models are selected based on your current location and downloaded from AWS.") }

                Section {
                    Button("Clear Cache", systemImage: "externaldrive") { showAlertCache = true }
                        .alert("Are you sure? This will delete all temporary data, including images.", isPresented: $showAlertCache) {
                            Button("Cancel", role: .cancel) {}
                            Button("Yes", role: .destructive) {}
                        }
                    Button("Delete All Data",
                           systemImage: "externaldrive.badge.exclamationmark",
                           role: .destructive) { showAlertAll = true }
                        .alert("Are you sure? This will delete all stored data, including stored models and your landmark history.", isPresented: $showAlertAll) {
                            Button("Cancel", role: .cancel) {}
                            Button("Yes", role: .destructive) {}
                        }
                } header: { Text("Data Management") }
                footer: { Text("Current cache size: \(cache) MB") }

                Section("Support & Info") {
                    NavigationLink { Help() } label: {
                        Label("Help & Tutorial", systemImage: "questionmark.circle")
                            .foregroundColor(.blue)
                    }
                    Button("About LookSee", systemImage: "info.circle") {
                        modal = true
                    }
                    .sheet(isPresented: $modal) {
                        Text("Looksee is an application designed to help you identify local landmarks with ease.")
                    }
                }

                // MARK: - Sign Out (hidden for guests)
                if authState.tier != .guest {
                    Section {
                        Button(role: .destructive) {
                            showAlertSignOut = true
                        } label: {
                            Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                        .alert("Are you sure you want to sign out?", isPresented: $showAlertSignOut) {
                            Button("Cancel", role: .cancel) {}
                            Button("Sign Out", role: .destructive) {
                                Task {
                                    await authState.signOut()
                                    vm.isSignedIn = false
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .onChange(of: authState.didSignOut) { _, didSignOut in
                if didSignOut {
                    dismiss()
                }
            }
        }
    }
}

//#Preview {
//    Settings()
//}
