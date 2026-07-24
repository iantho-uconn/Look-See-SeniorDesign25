//
//  Settings.swift
//  LookSeeProto
//


import SwiftUI
import Foundation
import Combine

class SettingsPresenter: ObservableObject {
    @Published var showSubscriptionFlow = false
    @Published var subscriptionStartingTab = 0
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

    // 🚀 STRICT CHECK: If capacity is 0, they have not paid yet.
    private var hasActivePlan: Bool {
        return vm.maxLandmarksCapacity > 0
    }

    private var dynamicPlanTitle: String {
        if authState.tier == .guest {
            return "Guest Account"
        }
        if !hasActivePlan {
            return "No Active Plan"
        }
        switch vm.maxLandmarksCapacity {
        case 1...5: return "Classic Tier"
        case 6...20: return "Intermediate Tier"
        case 21...: return "Advanced Tier"
        default: return "Business Account"
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                // Profile Header
                VStack(spacing: 0) {
                    if authState.tier == .guest {
                        profileHeader(icon: "person.crop.circle.badge.questionmark", iconColor: .gray, title: "Guest User", subtitle: "Browsing anonymously")
                    } else {
                        profileHeader(icon: "person.crop.circle.badge.checkmark", iconColor: primaryColor, title: vm.userEmail.isEmpty ? "Loading..." : vm.userEmail, subtitle: dynamicPlanTitle)
                            .task {
                                await vm.fetchUserDetails()
                                await vm.fetchUserUsageStats()
                            }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
                .padding(.horizontal)

                if !hasActivePlan {
                    guestPromoCard
                }

                if authState.tier != .guest {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Account").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
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

                // Membership Section
                if authState.tier == .business || hasActivePlan {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Membership").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
                        VStack(spacing: 16) {
                            HStack {
                                Text("Current Plan").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text(dynamicPlanTitle).foregroundStyle(primaryColor).font(.system(size: 16, weight: .bold, design: .rounded))
                            }
                            
                            HStack {
                                Text("Landmark Capacity").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text("\(vm.activeLandmarksCount) / \(vm.maxLandmarksCapacity) active").foregroundStyle(.secondary).font(.system(size: 15, weight: .medium, design: .monospaced))
                            }
                            
                            HStack {
                                Text("Status").font(.system(size: 16, weight: .semibold))
                                Spacer()
                                Text(!hasActivePlan ? "Inactive" : "Active").foregroundStyle(!hasActivePlan ? .red : .green).font(.system(size: 15, weight: .bold))
                            }
                            
                            Divider()
                            
                            HStack(spacing: 12) {
                                Button {
                                    presenter.subscriptionStartingTab = 0
                                    presenter.showSubscriptionFlow = true
                                } label: {
                                    Text(!hasActivePlan ? "Subscribe" : "Change Plan")
                                        .font(.system(size: 15, weight: .bold, design: .rounded))
                                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                                        .background(primaryColor.opacity(0.1)).foregroundStyle(primaryColor).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                
                                Button { showCancelAlert = true } label: {
                                    Text("Cancel").font(.system(size: 15, weight: .bold, design: .rounded))
                                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                                        .background(Color.red.opacity(0.1)).foregroundStyle(.red).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                            }
                        }
                        .padding(20).background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                        .alert("Cancel Subscription?", isPresented: $showCancelAlert) {
                            Button("Keep Plan", role: .cancel) {}
                            Button("Cancel Plan", role: .destructive) { withAnimation { authState.tier = .authenticated } }
                        } message: { Text("Your business features will be disabled immediately.") }
                    }
                    
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Management").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                        
                        // 🚀 TOTAL LOCKDOWN: Only shows tools if they actually paid
                        if hasActivePlan {
                            VStack(spacing: 0) {
                                NavigationLink { BusinessLandmarksView() } label: {
                                    settingsRow(icon: "building.2.crop.circle.fill", iconBg: primaryColor, title: "Manage My Landmarks", subtitle: "View the landmarks assigned to your account.")
                                }
                                Divider().padding(.leading, 68)
                                Button {
                                    presenter.subscriptionStartingTab = 1
                                    presenter.showSubscriptionFlow = true
                                } label: {
                                    settingsRow(icon: "circle.hexagongrid.fill", iconBg: .orange, title: "Swap Tokens (\(vm.tokenBalance))", subtitle: "Buy tokens to update your inventory.", showDivider: false)
                                }
                            }
                            .background(Color(uiColor: .secondarySystemGroupedBackground)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2).padding(.horizontal)
                        } else {
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
                }

                // App Links
                VStack(spacing: 0) {
                    NavigationLink { Text("Help & Support Center") } label: { settingsRow(icon: "questionmark.circle.fill", iconBg: .orange, title: "Help & Support", showDivider: true) }
                    NavigationLink { Text("Privacy Policy") } label: { settingsRow(icon: "hand.raised.fill", iconBg: .purple, title: "Privacy Policy", showDivider: true) }
                    NavigationLink { Text("Terms of Service") } label: { settingsRow(icon: "doc.text.fill", iconBg: .green, title: "Terms of Service", showDivider: true) }
                    NavigationLink { DeepSettingsView() } label: { settingsRow(icon: "gearshape.fill", iconBg: .gray, title: "Settings & Preferences", showDivider: false) }
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
        .sheet(isPresented: $presenter.showSubscriptionFlow) { SubscriptionPlans(startingTab: presenter.subscriptionStartingTab) }
        .sheet(isPresented: $presenter.showLoginSheet) {
            NavigationStack {
                Login(vm: vm, onSignedIn: {
                    presenter.showLoginSheet = false; dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.85) {
                        authState.tier = hasActivePlan ? .business : .authenticated
                    }
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
            Image(systemName: icon).font(.system(size: 48, weight: .light)).foregroundStyle(iconColor)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                Text(subtitle).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }
    
    private func settingsRow(icon: String, iconBg: Color, title: String, subtitle: String? = nil, showDivider: Bool = false) -> some View {
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
                        Text("Join LookSee Premium").font(.system(size: 18, weight: .bold, design: .rounded)).foregroundStyle(.white)
                        Text("Upload landmarks and manage data.").font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.7))
                    }
                }
                HStack(spacing: 12) {
                    Button { presenter.showSubscriptionFlow = true } label: {
                        Text("Subscribe").font(.system(size: 15, weight: .bold, design: .rounded)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }.buttonStyle(.plain)
                    
                    if authState.tier == .guest {
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
    @ObservedObject var modelLoader = ModelService.shared
    @StateObject private var locationManager = LocationManager()
    @State private var showAlertSignOut = false
    @State private var isReloading = false
    @State private var reloadMessage: String? = nil
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                if authState.tier != .guest {
                    Button { showAlertSignOut = true } label: {
                        HStack(spacing: 12) { Image(systemName: "rectangle.portrait.and.arrow.right"); Text("Sign Out") }.font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 16).background(Color.red.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.horizontal)
                    }
                    .alert("Are you sure you want to sign out?", isPresented: $showAlertSignOut) {
                        Button("Cancel", role: .cancel) {}
                        Button("Sign Out", role: .destructive) { Task { await authState.signOut(); vm.isSignedIn = false } }
                    }
                }
            }.padding(.top, 16)
        }.background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea()).navigationTitle("Advanced Settings")
    }
}
