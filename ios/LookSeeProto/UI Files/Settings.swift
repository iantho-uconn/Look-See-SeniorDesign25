//
//  Settings.swift
//  LookSeeProto
//

import SwiftUI
import Foundation
import Combine
import PhotosUI

class SettingsPresenter: ObservableObject {
    @Published var showSubscriptionFlow = false
    @Published var subscriptionStartingTab = 0
    @Published var showLoginSheet = false
    @Published var showSignUpSheet = false
    
    @Published var resumeCheckoutAction: String? = nil
    @Published var savedAddOnIndex: Int = 0
    @Published var savedTokenCount: Int = 0
    @Published var savedTokenCents: Int = 0
    
    @Published var justPurchased: Bool = false
    
}

struct Settings: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState

    @StateObject private var presenter = SettingsPresenter()
    @State private var showCancelAlert = false
    @State private var isCancelling = false

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    private var isFullyLoggedIn: Bool {
        return vm.isSignedIn && !vm.userEmail.isEmpty
    }

    private var dynamicPlanTitle: LocalizedStringKey {
        if !vm.hasActiveSubscription { return "Free Account" }
        if UserDefaults.standard.bool(forKey: "isFreeTrial_\(vm.userEmail)") {
            return "14-Day Free Trial"
        }
        return "Yearly Subscription"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                // 1. PROFILE HEADER
                VStack(spacing: 0) {
                    if !isFullyLoggedIn {
                        HStack(spacing: 16) {
                            Image(systemName: "person.crop.circle.badge.questionmark").font(.system(size: 48, weight: .light)).foregroundStyle(.gray)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Guest User").font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                                Text("Browsing anonymously").font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .task { if !presenter.justPurchased { await vm.checkSession() } }
                    } else {
                        HStack(spacing: 16) {
                            Image(systemName: "person.crop.circle.badge.checkmark").font(.system(size: 48, weight: .light)).foregroundStyle(primaryColor)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: vm.userEmail).font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                                Text(dynamicPlanTitle).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .task {
                            if !presenter.justPurchased {
                                await vm.fetchUserDetails()
                                await vm.fetchUserUsageStats()
                            }
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
                .padding(.horizontal)

                // 2. BUSINESS MANAGEMENT
                if isFullyLoggedIn && vm.hasActiveSubscription {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Management").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
                        if UserDefaults.standard.bool(forKey: "isFreeTrial_\(vm.userEmail)") {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange).font(.title3)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Free Trial Active").font(.system(size: 14, weight: .bold)).foregroundStyle(.primary)
                                    Text("Please subscribe before your 14-day trial ends to prevent your landmarks from being deactivated.").font(.system(size: 13)).foregroundStyle(.secondary)
                                }
                            }
                            .padding(16).background(Color.orange.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
                        }
                        
                        VStack(spacing: 0) {
                            NavigationLink { BusinessLandmarksView() } label: {
                                settingsRow(icon: "building.2.crop.circle.fill", iconBg: primaryColor, title: "Manage My Landmarks", subtitle: "View the landmarks assigned to your account.")
                            }
                            Divider().padding(.leading, 68)
                            Button {
                                presenter.subscriptionStartingTab = 1
                                presenter.showSubscriptionFlow = true
                            } label: {
                                settingsRow(icon: "circle.hexagongrid.fill", iconBg: .orange, title: "Tokens (\(vm.tokenBalance))", subtitle: "Buy tokens to update your inventory.", showDivider: false)
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Management").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        VStack(spacing: 0) {
                            Button {
                                presenter.subscriptionStartingTab = 0
                                presenter.showSubscriptionFlow = true
                            } label: {
                                settingsRow(icon: "lock.fill", iconBg: .gray, title: "Business Tools Locked", subtitle: "Subscribe to a plan to unlock landmarks and tokens.", showDivider: false)
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                    }
                }

                // 3. ACCOUNT (BUSINESS PROFILE & SECURITY)
                if isFullyLoggedIn {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Account").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
                        VStack(spacing: 0) {
                            if vm.hasActiveSubscription {
                                NavigationLink { BusinessProfileView().environmentObject(vm) } label: {
                                    if vm.storeName.isEmpty {
                                        settingsRow(icon: "storefront.fill", iconBg: .blue, title: "Business Profile", subtitle: "Update store name and phone number.")
                                    } else {
                                        settingsRow(icon: "storefront.fill", iconBg: .blue, title: "Business Profile", subtitle: LocalizedStringKey(vm.storeName))
                                    }
                                }
                            } else {
                                Button {
                                    presenter.subscriptionStartingTab = 0
                                    presenter.showSubscriptionFlow = true
                                } label: {
                                    settingsRow(icon: "lock.fill", iconBg: .gray, title: "Business Profile Locked", subtitle: "Subscribe to edit your public store info.", showDivider: false)
                                }
                            }
                            
                            Divider().padding(.leading, 68)
                            NavigationLink { AccountSecurityView().environmentObject(vm) } label: {
                                settingsRow(icon: "person.badge.key.fill", iconBg: .gray, title: "Account & Security", subtitle: "Change your email or password.", showDivider: false)
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                        .padding(.horizontal)
                    }
                }

                // 4. SUBSCRIPTION POPUP / MEMBERSHIP
                if !vm.hasActiveSubscription || !isFullyLoggedIn {
                    guestPromoCard
                } else if isFullyLoggedIn && vm.hasActiveSubscription {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Membership").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
                        VStack(spacing: 16) {
                            HStack {
                                Text("Current Plan").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text(dynamicPlanTitle).foregroundStyle(primaryColor).font(.system(size: 16, weight: .bold, design: .rounded))
                            }
                            
                            HStack {
                                Text("Status").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                if !vm.hasActiveSubscription {
                                    Text("Inactive").foregroundStyle(.red).font(.system(size: 15, weight: .bold))
                                } else {
                                    Text("Active").foregroundStyle(.green).font(.system(size: 15, weight: .bold))
                                }
                            }
                            
                            Divider()
                            
                            HStack(spacing: 12) {
                                Button {
                                    presenter.subscriptionStartingTab = 0
                                    presenter.showSubscriptionFlow = true
                                } label: {
                                    if !vm.hasActiveSubscription {
                                        Text("sign Up").font(.system(size: 15, weight: .bold, design: .rounded)).frame(maxWidth: .infinity).padding(.vertical, 12).background(primaryColor.opacity(0.1)).foregroundStyle(primaryColor).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    } else {
                                        Text("Manage Plan").font(.system(size: 15, weight: .bold, design: .rounded)).frame(maxWidth: .infinity).padding(.vertical, 12).background(primaryColor.opacity(0.1)).foregroundStyle(primaryColor).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    }
                                }
                                
                                Button { showCancelAlert = true } label: {
                                    Text("Cancel").font(.system(size: 15, weight: .bold, design: .rounded)).frame(maxWidth: .infinity).foregroundStyle(.red).padding(.vertical, 12).background(Color(.systemBackground)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).overlay(
                                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                                            // 3. Adaptive stroke color
                                            .stroke(.red.opacity(0.8), lineWidth: 2)
                                    )
                                }
                            }
                        }
                        .padding(20).background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                        .alert("Cancel Subscription?", isPresented: $showCancelAlert) {
                            Button("Keep Plan", role: .cancel) {}
                            Button("Cancel Plan", role: .destructive) {
                                isCancelling = true
                                Task {
                                    await vm.cancelSubscription()
                                    await MainActor.run { isCancelling = false }
                                }
                            }
                        } message: { Text("Your business features will be disabled immediately.") }
                    }
                }

                // 5. OTHER SETTINGS
                VStack(spacing: 0) {
                    NavigationLink { Text("Help & Support Center") } label: { settingsRow(icon: "questionmark.circle.fill", iconBg: .orange, title: "Help & Support", showDivider: true) }
                    NavigationLink { Text("Privacy Policy") } label: { settingsRow(icon: "hand.raised.fill", iconBg: .purple, title: "Privacy Policy", showDivider: true) }
                    NavigationLink { Text("Terms of Service") } label: { settingsRow(icon: "doc.text.fill", iconBg: .green, title: "Terms of Service", showDivider: true) }
                    NavigationLink { DeepSettingsView(isFullyLoggedIn: isFullyLoggedIn).environmentObject(vm) } label: { settingsRow(icon: "gearshape.fill", iconBg: .gray, title: "Settings & Preferences", showDivider: false) }
                }
                .background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                
                Spacer(minLength: 40)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Menu")
        .onChange(of: authState.didSignOut) { _, didSignOut in if didSignOut { dismiss() } }
        .sheet(isPresented: $presenter.showSubscriptionFlow) { SubscriptionPlans(presenter: presenter) }
        .sheet(isPresented: $presenter.showLoginSheet) {
            NavigationStack {
                Login(vm: vm, onSignedIn: {
                    presenter.showLoginSheet = false; dismiss()
                }, onGoToSignup: {
                    presenter.showLoginSheet = false
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
                }, onContinueAsGuest: { presenter.showLoginSheet = false })
            }
        }
        .sheet(isPresented: $presenter.showSignUpSheet) { GuestSignUpView() }
        .onChange(of: isFullyLoggedIn) { _, loggedIn in
            if loggedIn && presenter.resumeCheckoutAction != nil {
                presenter.showLoginSheet = false
                presenter.showSignUpSheet = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                    presenter.showSubscriptionFlow = true
                }
            }
        }
        .overlay {
            if isCancelling {
                ZStack {
                    Color.black.opacity(0.4).ignoresSafeArea()
                    VStack(spacing: 16) {
                        ProgressView().tint(.white).scaleEffect(1.5)
                        Text("Canceling Plan...").font(.headline).foregroundStyle(.white)
                    }.padding(32).background(.ultraThinMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }
    
    private func settingsRow(icon: String, iconBg: Color, title: LocalizedStringKey, subtitle: LocalizedStringKey? = nil, showDivider: Bool = false) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.system(size: 18)).foregroundStyle(.white).frame(width: 36, height: 36).background(iconBg).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary)
                    if let subtitle { Text(subtitle).font(.system(size: 13, weight: .regular)).foregroundStyle(.secondary) }
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .bold)).foregroundStyle(Color(uiColor: .tertiaryLabel))
            }.padding(16)
            if showDivider { Divider().padding(.leading, 68) }
        }
    }
    
    private var guestPromoCard: some View {
        ZStack(alignment: .leading) {
            LinearGradient(colors: [Color(red: 0.08, green: 0.15, blue: 0.35), Color(red: 0.05, green: 0.05, blue: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "waveform.path.ecg").resizable().scaledToFill().foregroundStyle(primaryColor.opacity(0.1)).frame(height: 140).clipped()

            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 16) {
                    ZStack {
                        Circle().fill(primaryColor)
                        Image(systemName: "crown.fill").foregroundStyle(.white).font(.system(size: 20))
                    }.frame(width: 48, height: 48).shadow(color: primaryColor.opacity(0.5), radius: 8, x: 0, y: 4)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Join LookSee").font(.system(size: 18, weight: .bold, design: .rounded)).foregroundStyle(.white)
                        Text("Upload landmarks and manage data. Free trail available.").font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.7))
                    }
                }
                HStack(spacing: 12) {
                    Button { presenter.showSubscriptionFlow = true } label: {
                        Text("Sign up").font(.system(size: 15, weight: .bold, design: .rounded)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }.buttonStyle(.plain)
                    
                    if !isFullyLoggedIn {
                        Button { presenter.showLoginSheet = true } label: {
                            Text("Log In").font(.system(size: 15, weight: .bold, design: .rounded)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(20)
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(LinearGradient(colors: [primaryColor.opacity(0.5), .clear], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1))
        .shadow(color: .black.opacity(0.1), radius: 15, x: 0, y: 5)
        .padding(.horizontal)
    }
}

// MARK: - DeepSettingsView
struct DeepSettingsView: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    
    var isFullyLoggedIn: Bool
    
    @State private var showAlertSignOut = false
    @State private var isReloading = false
    @State private var showReloadSuccess = false
    
    @State private var activeClusterID: String = "None"
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                VStack(alignment: .leading, spacing: 8) {
                    Text("App Language").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                    
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        HStack {
                            Text("App Language").font(.system(size: 16, weight: .semibold)).foregroundStyle(.primary)
                            Spacer()
                            Text("System Settings").font(.system(size: 15)).foregroundStyle(.secondary)
                            Image(systemName: "arrow.up.forward.app").font(.system(size: 14, weight: .semibold)).foregroundStyle(.tertiary)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    }
                    .padding(.horizontal)
                }
                
                VStack(spacing: 6) {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        isReloading = true
                        Task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            await MainActor.run {
                                isReloading = false
                                withAnimation { showReloadSuccess = true }
                                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                                    withAnimation { showReloadSuccess = false }
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 12) {
                            if isReloading {
                                ProgressView().tint(.white)
                                Text("Fetching Clusters...")
                            } else if showReloadSuccess {
                                Image(systemName: "checkmark.circle.fill")
                                Text("Models Reloaded!")
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                Text("Reload Model")
                            }
                        }
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(showReloadSuccess ? Color.green : primaryColor)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .disabled(isReloading)
                    
                    HStack(spacing: 6) {
                        Image(systemName: "cpu")
                        if activeClusterID == "None" {
                            Text("No Cluster Loaded")
                        } else {
                            Text("Active Cluster: \(activeClusterID)")
                        }
                    }
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)
                }
                .padding(.horizontal)
                
                if isFullyLoggedIn {
                    Button { showAlertSignOut = true } label: {
                        HStack(spacing: 12) { Image(systemName: "rectangle.portrait.and.arrow.right"); Text("Sign Out") }.font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 16).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.horizontal)
                    }
                    .alert("Are you sure you want to sign out?", isPresented: $showAlertSignOut) {
                        Button("Cancel", role: .cancel) {}
                        Button("Sign Out", role: .destructive) { Task { await vm.signOut(authState: authState) } }
                    }
                }
            }.padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Settings")
        .task {
            for await release in ModelSelector.shared.$activeRelease.values {
                await MainActor.run {
                    self.activeClusterID = release?.clusterID ?? "None"
                }
            }
        }
    }
}

// MARK: - BusinessProfileView
struct BusinessProfileView: View {
    @EnvironmentObject var vm: AuthViewModel
    @State private var showEditSheet = false


    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                VStack(spacing: 8) {
                    Text("Your Public Merchant Card")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                    
                    Text("This is exactly how your business will appear to users at the bottom of your AR Landmarks.")
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .padding(.top, 16)
                
                let mStoreName = vm.storeName.isEmpty ? String(localized: "Your Store Name") : vm.storeName
                let mBio = vm.storeBio.isEmpty ? String(localized: "Add a short bio about your business here so users know what you do.") : vm.storeBio
                let mPhone = vm.phoneNumber.isEmpty ? String(localized: "No Phone Number") : vm.phoneNumber
                
                MerchantCard(
                    storeName: mStoreName,
                    logoUrl: vm.storeLogoUrl,
                    bio: mBio,
                    phone: mPhone
                )
                .padding(.horizontal)
                
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    showEditSheet = true
                } label: {
                    Text("Edit Profile Details")
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal)
                }
                
                Spacer()
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Business Profile")
        .sheet(isPresented: $showEditSheet) {
            BusinessProfileEditSheet()
                .environmentObject(vm)
        }
    }
}

// MARK: - BusinessProfileEditSheet
struct BusinessProfileEditSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    
    @State private var draftName: String = ""
    @State private var draftPhone: String = ""
    @State private var draftBio: String = ""
    @State private var draftLogoUrl: String = ""
    @State private var isSaving = false
    @FocusState private var IsKeyboard: Bool

    @State private var selectedPhotoItem: PhotosPickerItem? = nil
    @State private var logoUIImage: UIImage? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Basic Info"), footer: Text("Your store name and a short bio describing what you do.")) {
                    TextField("Store Name", text: $draftName)
                        .focused($IsKeyboard)
                    TextField("Short Bio", text: $draftBio, axis: .vertical)
                        .focused($IsKeyboard)
                        .lineLimit(3...5)
                }
                
                Section(header: Text("Store Logo"), footer: Text("Upload a square logo or image from your photo library.")) {
                    HStack(spacing: 16) {
                        ZStack {
                            Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15))
                            
                            if let logoUIImage {
                                Image(uiImage: logoUIImage)
                                    .resizable()
                                    .scaledToFill()
                                    .clipShape(Circle())
                            } else if let url = URL(string: draftLogoUrl), !draftLogoUrl.isEmpty {
                                AsyncImage(url: url) { phase in
                                    if let image = phase.image {
                                        image.resizable().scaledToFill().clipShape(Circle())
                                    } else {
                                        Image(systemName: "storefront.fill")
                                            .font(.title2)
                                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                    }
                                }
                            } else {
                                Image(systemName: "storefront.fill")
                                    .font(.title2)
                                    .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            }
                        }
                        .frame(width: 56, height: 56)
                        
                        VStack(alignment: .leading, spacing: 6) {
                            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                                HStack(spacing: 6) {
                                    Image(systemName: "photo.badge.plus")
                                    if draftLogoUrl.isEmpty && logoUIImage == nil {
                                        Text("Choose Photo")
                                    } else {
                                        Text("Change Logo")
                                    }
                                }
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            }
                            .buttonStyle(.borderless)
                            
                            if !draftLogoUrl.isEmpty || logoUIImage != nil {
                                Button(role: .destructive) {
                                    draftLogoUrl = ""
                                    logoUIImage = nil
                                    selectedPhotoItem = nil
                                } label: {
                                    Text("Remove Logo")
                                        .font(.system(size: 12))
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
                
                Section(header: Text("Contact Info")) {
                    TextField("Phone Number", text: $draftPhone)
                        .focused($IsKeyboard)
                        .keyboardType(.phonePad)
                        .onChange(of: draftPhone) { _, newValue in
                            let filtered = newValue.filter { "0123456789".contains($0) }
                            if filtered.count > 10 { draftPhone = String(filtered.prefix(10)) }
                            else if draftPhone != filtered { draftPhone = filtered }
                        }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                IsKeyboard = false
            }
            .navigationTitle("Edit Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        isSaving = true
                        Task {
                            let base64String = logoUIImage?.jpegData(compressionQuality: 0.4)?.base64EncodedString()
                            
                            let success = await vm.updateBusinessProfile(
                                storeName: draftName,
                                phoneNumber: draftPhone,
                                storeBio: draftBio,
                                storeLogoUrl: draftLogoUrl,
                                storeLogoBase64: base64String
                            )
                            isSaving = false
                            if success { dismiss() }
                        }
                    } label: {
                        if isSaving { ProgressView() }
                        else { Text("Save").bold() }
                    }
                    .disabled(isSaving)
                }
            }
            .onAppear {
                draftName = vm.storeName
                draftPhone = vm.phoneNumber
                draftBio = vm.storeBio
                draftLogoUrl = vm.storeLogoUrl
            }
            .onChange(of: selectedPhotoItem) { _, newItem in
                Task {
                    guard let newItem else { return }
                    if let data = try? await newItem.loadTransferable(type: Data.self),
                       let uiImage = UIImage(data: data) {
                        await MainActor.run {
                            self.logoUIImage = uiImage
                            self.draftLogoUrl = ""
                        }
                    }
                }
            }
        }
    }
}
