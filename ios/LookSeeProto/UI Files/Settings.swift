//
//  Settings.swift
//  LookSeeProto
//

import SwiftUI
import Foundation
import Combine

class SettingsPresenter: ObservableObject {
    @Published var showSubscriptionFlow = false
    @Published var showLoginSheet = false
    @Published var showSignUpSheet = false
}

struct Settings: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState

    @StateObject private var presenter = SettingsPresenter()
    @State private var showCancelAlert = false

    var body: some View {
        Form {
            Section {
                if authState.tier == .guest {
                    HStack {
                        Image(systemName: "person.crop.circle.badge.questionmark")
                            .font(.system(size: 50))
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Guest User")
                                .foregroundStyle(.primary)
                            Text("Browsing anonymously")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                } else {
                    HStack {
                        Image(systemName: "person.crop.circle.badge.checkmark")
                            .font(.system(size: 50))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(vm.userEmail.isEmpty ? "Loading..." : vm.userEmail)
                                .foregroundStyle(.primary)
                            Text("Business Account")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .task {
                        await vm.fetchUserEmail()
                    }
                }
            }

            if authState.tier == .guest {
                ZStack(alignment: .leading) {
                    LinearGradient(
                        colors: [Color(red: 0.08, green: 0.15, blue: 0.35), Color(red: 0.05, green: 0.05, blue: 0.12)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    
                    Image(systemName: "waveform.path.ecg")
                        .resizable()
                        .scaledToFill()
                        .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.1))
                        .frame(height: 120)
                        .clipped()

                    VStack(alignment: .leading, spacing: 16) {
                        HStack(spacing: 16) {
                            ZStack {
                                Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00))
                                Image(systemName: "crown.fill")
                                    .foregroundStyle(.white)
                                    .font(.system(size: 20))
                            }
                            .frame(width: 48, height: 48)
                            .shadow(color: Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.5), radius: 8, x: 0, y: 4)
                            
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Join LookSee Premium")
                                    .font(.system(size: 18, weight: .bold, design: .rounded))
                                    .foregroundStyle(.white)
                                Text("Upload landmarks and manage data.")
                                    .font(.system(size: 13))
                                    .foregroundStyle(.white.opacity(0.7))
                            }
                        }
                        
                        HStack(spacing: 12) {
                            Button { presenter.showSubscriptionFlow = true } label: {
                                Text("Join Now")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                                    .cornerRadius(10)
                            }
                            .buttonStyle(.plain)
                            
                            Button { presenter.showLoginSheet = true } label: {
                                Text("Log In")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color.white.opacity(0.15))
                                    .cornerRadius(10)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(LinearGradient(colors: [Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.5), .clear], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1)
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .padding(.vertical, 8)
            }

            // MARK: - Account & Security
            if authState.tier != .guest {
                Section {
                    NavigationLink {
                        AccountSecurityView().environmentObject(vm)
                    } label: {
                        Label("Account & Security", systemImage: "person.badge.key")
                    }
                } header: {
                    Text("Account")
                } footer: {
                    Text("Change your email or password.")
                }
            }

            // MARK: - Business Management
            if authState.tier == .business {
                Section {
                    HStack {
                        Text("Current Plan")
                        Spacer()
                        Text("Intermediate").foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("Renewal Date")
                        Spacer()
                        Text("Aug 16, 2026").foregroundStyle(.secondary)
                    }
                    Button("Change Plan") { presenter.showSubscriptionFlow = true }
                        .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                    
                    Button("Cancel Subscription", role: .destructive) { showCancelAlert = true }
                        .alert("Cancel Subscription?", isPresented: $showCancelAlert) {
                            Button("Keep Plan", role: .cancel) {}
                            Button("Cancel Plan", role: .destructive) { withAnimation { authState.tier = .guest } }
                        } message: {
                            Text("Your business features will be disabled immediately.")
                        }
                } header: {
                    Text("Membership")
                }
                
                Section {
                    NavigationLink { BusinessLandmarksView() } label: {
                        Label("Manage My Landmarks", systemImage: "building.2.crop.circle")
                    }
                } header: {
                    Text("Business Management")
                } footer: {
                    Text("View the landmarks assigned to your business account.")
                }
            }

            Section {
                NavigationLink { Text("Help & Support Center") } label: { Label("Help & Support", systemImage: "questionmark.circle") }
                NavigationLink { Text("Privacy Policy") } label: { Label("Privacy Policy", systemImage: "hand.raised") }
                NavigationLink { Text("Terms of Service") } label: { Label("Terms of Service", systemImage: "doc.text") }
            }

            Section {
                NavigationLink { DeepSettingsView() } label: { Label("Settings & Preferences", systemImage: "gearshape") }
            }
        }
        .navigationTitle("Menu")
        .onChange(of: authState.didSignOut) { _, didSignOut in if didSignOut { dismiss() } }
        .sheet(isPresented: $presenter.showSubscriptionFlow) { SubscriptionPlans() }
        .sheet(isPresented: $presenter.showLoginSheet) {
            NavigationStack {
                Login(vm: vm, onSignedIn: {
                    presenter.showLoginSheet = false
                    dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.85) { authState.tier = .business }
                }, onGoToSignup: {
                    presenter.showLoginSheet = false
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
                }, onContinueAsGuest: { presenter.showLoginSheet = false })
            }
        }
        .sheet(isPresented: $presenter.showSignUpSheet) { GuestSignUpView() }
    }
}

struct DeepSettingsView: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState

    @AppStorage("onlineMode") var onlineMode = true
    @AppStorage("permissionCamera") var permissionCamera = true
    @AppStorage("permissionLocation") var permissionLocation = true
    @AppStorage("permissionStorage") var permissionStorage = true

    @ObservedObject var modelLoader = ModelService.shared
    @StateObject private var locationManager = LocationManager()

    @State private var showAlertSignOut = false
    @State private var isReloading = false
    @State private var reloadMessage: String? = nil

    var body: some View {
        Form {
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
                                .frame(width: 20, height: 20)
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
            }

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
            
            Section {
                HStack {
                    Spacer()
                    Text("LookSee v1.0.0")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Settings")
    }

    private var reloadFailed: Bool {
        if case .failed = modelLoader.state { return true }
        if case .none = modelLoader.pullReason, reloadMessage != nil { return true }
        return false
    }

    private func reloadModels() {
        guard !isReloading else { return }
        guard locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude else {
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
                case .none: reloadMessage = "No models found for your area."
                case .single(let reason): reloadMessage = "Loaded \(models[0].name) · \(reason)"
                case .multiple(let reasons): reloadMessage = "Loaded \(models.count) models · \(reasons.first ?? "")"
                }
            case .failed(let error): reloadMessage = "Failed: \(error)"
            default: break
            }
            isReloading = false
        }
    }
}
