//
//  Buttons.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/25/26.
//
import SwiftUI

struct Buttons: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    @State private var showPromotion = false
    @State private var showBusinessAlert = false
    @State private var showSignUpPrompt = false
    @State private var showSignUp = false
    @State private var currentTab = 0
    @State private var pendingUploadLandmarkId: String?

    // Number of tabs based on tier
    var tabCount: Int {
        switch authState.tier {
        case .guest: return 4           // Scan + Map + blocked Upload + blocked Archive
        case .authenticated: return 4   // Scan + Map + Upload + Archive
        case .business: return 5        // Scan + Map + Record + Upload + Archive
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()

                TabView(selection: $currentTab) {
                    // Tab 0 — Scan (all users)
                    LandmarkScan()
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                        .tag(0)
                    
                    // Tab 1 — Map (all users)
                    LandmarkMapView()
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                        .tag(1)

                    // Tab 2 — Record (business only, hidden for others)
                    if authState.tier == .business {
                        LandmarkRecord { landmarkId in
                            pendingUploadLandmarkId = landmarkId

                            withAnimation(
                                .easeInOut(duration: 0.25)
                            ) {
                                currentTab = 3 // Shifted to Tab 3
                            }
                        }
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                        .tag(2)
                    }

                    // Tab 3 (or 2 for non-business) — Upload
                    Group {
                        if authState.tier == .authenticated || authState.tier == .business {
                            Tier2LandmarkRecord(
                                initialLandmarkId: pendingUploadLandmarkId,
                                onInitialLandmarkConsumed: {
                                    pendingUploadLandmarkId = nil
                                }
                            )
                        } else {
                            // Guest sees a blank page — prompt handled by button tap
                            Color(red: 0.06, green: 0.06, blue: 0.10)
                                .ignoresSafeArea()
                        }
                    }
                    .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                    .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                    .tag(authState.tier == .business ? 3 : 2)
                    
                    // Tab 4 (or 3 for non-business) — Archive
                    Group {
                        if authState.tier == .authenticated || authState.tier == .business {
                            ArchiveView()
                        } else {
                            // Guest sees a blank page
                            Color(red: 0.06, green: 0.06, blue: 0.10)
                                .ignoresSafeArea()
                        }
                    }
                    .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                    .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                    .tag(authState.tier == .business ? 4 : 3)
                }
                .scrollDismissesKeyboard(.immediately)
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
                .ignoresSafeArea()

                VStack(spacing: 0) {
                    // Top nav bar
                    HStack(spacing: 0) {
                        
                        // Invisible spacer replaces the old Library button to maintain perfect center alignment
                        Color.clear
                            .frame(width: 56, height: 48)

                        Spacer()

                        Button {
                            if authState.tier == .business {
                                showPromotion = true
                            } else {
                                showBusinessAlert = true
                            }
                        } label: {
                            Text("LookSee")
                                .font(.system(size: 18, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                        }
                        .sheet(isPresented: $showPromotion) {
                            PromotionEditor()
                        }
                        .alert("Business Account Required", isPresented: $showBusinessAlert) {
                            Button("OK", role: .cancel) {}
                        } message: {
                            Text("You need a business account to access the Promotion Editor.")
                        }

                        Spacer()

                        NavigationLink {
                            Settings().environmentObject(vm)
                        } label: {
                            NavButton(icon: "gearshape", label: "Settings")
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 10)
                    .background(
                        Color(red: 0.06, green: 0.06, blue: 0.10)
                            .opacity(0.95)
                            .ignoresSafeArea(edges: .top)
                    )

                    Spacer()

                    // Bottom tab bar
                    HStack(spacing: 0) {
                        // Scan — always visible
                        tabButton(title: "Scan", icon: "camera.aperture", tab: 0, locked: false)
                        
                        // Map — always visible
                        tabButton(title: "Map", icon: "map", tab: 1, locked: false)

                        // Record — business only
                        if authState.tier == .business {
                            tabButton(title: "Record", icon: "video", tab: 2, locked: false)
                        }

                        // Upload — visible to all, locked for guest
                        tabButton(
                            title: "Upload",
                            icon: "arrow.up.circle",
                            tab: authState.tier == .business ? 3 : 2,
                            locked: authState.tier == .guest
                        )
                        
                        // Archive — visible to all, locked for guest
                        tabButton(
                            title: "Archive",
                            icon: "folder.fill",
                            tab: authState.tier == .business ? 4 : 3,
                            locked: authState.tier == .guest
                        )
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(
                        Color(red: 0.06, green: 0.06, blue: 0.10)
                            .opacity(0.95)
                            .ignoresSafeArea(edges: .bottom)
                    )
                }

                // Sign up prompt overlay for guest tapping Upload/Archive
                if showSignUpPrompt {
                    signUpPromptOverlay
                }
            }
            // Full screen sign up flow for guests
            .fullScreenCover(isPresented: $showSignUp) {
                NavigationStack {
                    Signup(
                        onSignupSuccess: { email in
                            showSignUp = false
                            // After signup they go back to RootView flow naturally
                        },
                        onGoToLogin: {
                            showSignUp = false
                        }
                    )
                }
            }
            // Buttons supplies its own header, so suppress SwiftUI's navigation bar.
            // Otherwise an invisible navigation-bar region can push the custom header down.
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    // MARK: - Sign Up Prompt Overlay
    var signUpPromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture {
                    // Tapping outside dismisses and returns to scan
                    showSignUpPrompt = false
                    withAnimation { currentTab = 0 }
                }

            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                        .frame(width: 70, height: 70)
                    Image(systemName: "arrow.up.circle")
                        .font(.system(size: 32))
                        .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                }

                VStack(spacing: 8) {
                    Text("Sign up to upload")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Text("Create an account to start contributing landmarks and help improve recognition.")
                        .font(.subheadline)
                        .foregroundStyle(Color.white.opacity(0.5))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }

                VStack(spacing: 10) {
                    // Yes — go to sign up
                    Button {
                        showSignUpPrompt = false
                        showSignUp = true
                    } label: {
                        HStack(spacing: 8) {
                            Text("Create Account")
                                .font(.system(size: 16, weight: .semibold))
                            Image(systemName: "arrow.right")
                                .font(.system(size: 14, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .cornerRadius(14)
                    }

                    // No — back to scan
                    Button {
                        showSignUpPrompt = false
                        withAnimation(.easeInOut(duration: 0.25)) { currentTab = 0 }
                    } label: {
                        Text("Not now")
                            .font(.system(size: 15))
                            .foregroundStyle(Color.white.opacity(0.4))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(Color.white.opacity(0.07))
                            .cornerRadius(14)
                    }
                }
            }
            .padding(24)
            .background(Color(red: 0.11, green: 0.11, blue: 0.16))
            .cornerRadius(24)
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.07), lineWidth: 0.5)
            )
            .padding(.horizontal, 28)
        }
    }

    // MARK: - Tab Button
    @ViewBuilder
    func tabButton(title: String, icon: String, tab: Int, locked: Bool) -> some View {
        Button {
            if locked {
                // Guest tapping Upload/Archive — show sign up prompt
                showSignUpPrompt = true
            } else {
                withAnimation(.easeInOut(duration: 0.25)) {
                    currentTab = tab
                }
            }
        } label: {
            VStack(spacing: 4) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                    if locked {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 8))
                            .foregroundStyle(Color.white.opacity(0.4))
                            .offset(x: 6, y: -4)
                    }
                }
                Text(title)
                    .font(.system(size: 10, weight: .medium))
            }
            .foregroundStyle(
                locked
                    ? Color.white.opacity(0.25)
                    : currentTab == tab
                        ? Color(red: 0.22, green: 0.49, blue: 1.00)
                        : Color.white.opacity(0.5)
            )
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                Group {
                    if currentTab == tab && !locked {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                    }
                }
            )
        }
    }
}

// MARK: - Nav Button
private struct NavButton: View {
    let icon: String
    let label: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.5))
        }
        .frame(width: 56, height: 48)
        .contentShape(Rectangle())
    }
}
