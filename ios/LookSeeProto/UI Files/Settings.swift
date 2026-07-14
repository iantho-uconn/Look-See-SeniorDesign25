//
//  Settings.swift
//  LookSeeProto
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

    @ObservedObject var modelLoader = ModelService.shared
    @StateObject private var locationManager = LocationManager()

    @State private var modal = false
    @State private var showAlertAll = false
    @State private var showAlertCache = false
    @State private var showAlertSignOut = false
    @State private var cache = 0
    @State private var showModelInfo = false
    @State private var showDeleteModelAlert = false
    @State private var isReloading = false
    @State private var reloadMessage: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                // MARK: - Profile
                if authState.tier == .guest {
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
                    Button {
                        print("Profile tapped")
                    } label: {
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
                    }
                    .task {
                        await vm.fetchUserEmail()
                    }
                }

                // MARK: - Account & Security
                if authState.tier != .guest {
                    Section {
                        NavigationLink {
                            AccountSecurityView()
                                .environmentObject(vm)
                        } label: {
                            Label("Account & Security", systemImage: "person.badge.key")
                        }
                    } header: {
                        Text("Account")
                    } footer: {
                        Text("Change your email, add or update a phone number, and change your password.")
                    }
                }

                // MARK: - Business Management
                if authState.tier == .business {
                    Section {
                        NavigationLink {
                            BusinessLandmarksView()
                        } label: {
                            Label("Manage My Landmarks", systemImage: "building.2.crop.circle")
                        }
                    } header: {
                        Text("Business Management")
                    } footer: {
                        Text("View the landmarks assigned to your business account. Remote media uploads will be added in the next phase.")
                    }
                }

                // MARK: - Model Management
                Section {
                    Button {
                        reloadModels()
                    } label: {
                        HStack {
                            Label("Reload Models", systemImage: "arrow.clockwise.circle")

                            Spacer()

                            if isReloading {
                                ProgressView()
                                    .progressViewStyle(.circular)
                                    .scaleEffect(0.85)
                            }
                        }
                    }
                    .disabled(isReloading)

                    if let message = reloadMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: reloadFailed ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                                .foregroundStyle(reloadFailed ? .orange : .green)
                                .font(.footnote)

                            Text(message)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                } header: {
                    Text("Model Management")
                } footer: {
                    Text("Models are selected based on your current location and downloaded from AWS.")
                }

                // MARK: - Sign Out
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

    // MARK: - Helpers
    private var reloadFailed: Bool {
        if case .failed = modelLoader.state {
            return true
        }

        if case .none = modelLoader.pullReason,
           reloadMessage != nil {
            return true
        }

        return false
    }

    private func reloadModels() {
        guard !isReloading else { return }

        guard locationManager.isAuthorized,
              let lat = locationManager.latitude,
              let lon = locationManager.longitude else {
            reloadMessage = "Location unavailable. Enable location access and try again."
            return
        }

        isReloading = true
        reloadMessage = nil

        Task {
            await modelLoader.reloadModels(latitude: lat, longitude: lon)

            switch modelLoader.state {
            case .loaded(let models):
                switch modelLoader.pullReason {
                case .none:
                    reloadMessage = "No models found for your area."
                case .single(let reason):
                    reloadMessage = "Loaded \(models[0].name) · \(reason)"
                case .multiple(let reasons):
                    reloadMessage = "Loaded \(models.count) models · \(reasons.first ?? "")"
                }

            case .failed(let error):
                reloadMessage = "Failed: \(error)"

            default:
                break
            }

            isReloading = false
        }
    }
}
