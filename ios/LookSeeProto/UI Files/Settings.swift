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

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                // MARK: - Profile Header Card
                VStack(spacing: 0) {
                    if authState.tier == .guest {
                        profileHeader(icon: "person.crop.circle.badge.questionmark", iconColor: .gray, title: "Guest User", subtitle: "Browsing anonymously")
                    } else {
                        profileHeader(icon: "person.crop.circle.badge.checkmark", iconColor: primaryColor, title: vm.userEmail.isEmpty ? "Loading..." : vm.userEmail, subtitle: "Business Account")
                            .task { await vm.fetchUserEmail() }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
                .padding(.horizontal)

                // MARK: - Guest Promo Card
                if authState.tier == .guest {
                    guestPromoCard
                }

                // MARK: - Account & Security
                if authState.tier != .guest {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Account")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .padding(.horizontal, 20)
                        
                        VStack(spacing: 0) {
                            NavigationLink { AccountSecurityView().environmentObject(vm) } label: {
                                settingsRow(icon: "person.badge.key.fill", iconBg: .blue, title: "Account & Security", subtitle: "Change your email or password.")
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                        .padding(.horizontal)
                    }
                }

                // MARK: - Business Management
                if authState.tier == .business {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Membership")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .padding(.horizontal, 20)
                        
                        VStack(spacing: 16) {
                            HStack {
                                Text("Current Plan").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text("Intermediate").foregroundStyle(.secondary).font(.system(size: 16, weight: .medium))
                            }
                            HStack {
                                Text("Renewal Date").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text("Aug 16, 2026").foregroundStyle(.secondary).font(.system(size: 16, weight: .medium))
                            }
                            Divider()
                            
                            HStack(spacing: 12) {
                                Button { presenter.showSubscriptionFlow = true } label: {
                                    Text("Change Plan")
                                        .font(.system(size: 15, weight: .bold, design: .rounded))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                        .background(primaryColor.opacity(0.1))
                                        .foregroundStyle(primaryColor)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                
                                Button { showCancelAlert = true } label: {
                                    Text("Cancel")
                                        .font(.system(size: 15, weight: .bold, design: .rounded))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                        .background(Color.red.opacity(0.1))
                                        .foregroundStyle(.red)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                            }
                        }
                        .padding(20)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                        .padding(.horizontal)
                        .alert("Cancel Subscription?", isPresented: $showCancelAlert) {
                            Button("Keep Plan", role: .cancel) {}
                            Button("Cancel Plan", role: .destructive) { withAnimation { authState.tier = .guest } }
                        } message: { Text("Your business features will be disabled immediately.") }
                    }
                    
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Management")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .padding(.horizontal, 20)
                        
                        VStack(spacing: 0) {
                            NavigationLink { BusinessLandmarksView() } label: {
                                settingsRow(icon: "building.2.crop.circle.fill", iconBg: primaryColor, title: "Manage My Landmarks", subtitle: "View the landmarks assigned to your account.")
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                        .padding(.horizontal)
                    }
                }

                // MARK: - App Links
                VStack(spacing: 0) {
                    NavigationLink { Text("Help & Support Center") } label: {
                        settingsRow(icon: "questionmark.circle.fill", iconBg: .orange, title: "Help & Support", showDivider: true)
                    }
                    NavigationLink { Text("Privacy Policy") } label: {
                        settingsRow(icon: "hand.raised.fill", iconBg: .purple, title: "Privacy Policy", showDivider: true)
                    }
                    NavigationLink { Text("Terms of Service") } label: {
                        settingsRow(icon: "doc.text.fill", iconBg: .green, title: "Terms of Service", showDivider: true)
                    }
                    NavigationLink { DeepSettingsView() } label: {
                        settingsRow(icon: "gearshape.fill", iconBg: .gray, title: "Settings & Preferences", showDivider: false)
                    }
                }
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                .padding(.horizontal)
                
                Spacer(minLength: 40)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
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
    
    private func profileHeader(icon: String, iconColor: Color, title: String, subtitle: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 48, weight: .light))
                .foregroundStyle(iconColor)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }
    
    private func settingsRow(icon: String, iconBg: Color, title: String, subtitle: String? = nil, showDivider: Bool = false) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(iconBg)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(.system(size: 13, weight: .regular)).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .padding(16)
            
            if showDivider {
                Divider().padding(.leading, 68)
            }
        }
    }
    
    private var guestPromoCard: some View {
        ZStack(alignment: .leading) {
            LinearGradient(
                colors: [Color(red: 0.08, green: 0.15, blue: 0.35), Color(red: 0.05, green: 0.05, blue: 0.12)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            
            Image(systemName: "waveform.path.ecg")
                .resizable()
                .scaledToFill()
                .foregroundStyle(primaryColor.opacity(0.1))
                .frame(height: 140)
                .clipped()

            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 16) {
                    ZStack {
                        Circle().fill(primaryColor)
                        Image(systemName: "crown.fill")
                            .foregroundStyle(.white)
                            .font(.system(size: 20))
                    }
                    .frame(width: 48, height: 48)
                    .shadow(color: primaryColor.opacity(0.5), radius: 8, x: 0, y: 4)
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Join LookSee Premium")
                            .font(.system(size: 18, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                        Text("Upload landmarks and manage data.")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
                
                HStack(spacing: 12) {
                    Button { presenter.showSubscriptionFlow = true } label: {
                        Text("Join Now")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(primaryColor)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    
                    Button { presenter.showLoginSheet = true } label: {
                        Text("Log In")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(Color.white.opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(20)
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(LinearGradient(colors: [primaryColor.opacity(0.5), .clear], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1))
        .shadow(color: .black.opacity(0.1), radius: 15, x: 0, y: 5)
        .padding(.horizontal)
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
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("System Models")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.horizontal, 20)
                    
                    VStack(spacing: 0) {
                        Button { reloadModels() } label: {
                            HStack(spacing: 16) {
                                Image(systemName: "arrow.clockwise.circle.fill")
                                    .font(.system(size: 18))
                                    .foregroundStyle(.white)
                                    .frame(width: 36, height: 36)
                                    .background(primaryColor)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                
                                Text("Reload Models").font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary)
                                Spacer()
                                if isReloading { ProgressView().tint(primaryColor) }
                            }
                            .padding(16)
                        }
                        .disabled(isReloading)
                        
                        if let message = reloadMessage {
                            Divider().padding(.leading, 68)
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: reloadFailed ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                                    .foregroundStyle(reloadFailed ? .orange : .green)
                                Text(message).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(16)
                            .background(Color(uiColor: .tertiarySystemGroupedBackground))
                        }
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)
                }

                if authState.tier != .guest {
                    Button { showAlertSignOut = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                            Text("Sign Out")
                        }
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color.red.opacity(0.1))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal)
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
                
                Text("LookSee v1.0.0")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 20)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
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
