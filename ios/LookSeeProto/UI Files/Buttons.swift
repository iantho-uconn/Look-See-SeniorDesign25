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
    
    // Chrome visibility — top/bottom bars fade out on Scan after inactivity,
    // matching Snapchat's camera screen. Always visible on other tabs.
    @State private var chromeVisible = true
    @State private var chromeFadeTask: Task<Void, Never>?
    
    var tabCount: Int {
        switch authState.tier {
        case .guest: return 4
        case .authenticated: return 4
        case .business: return 5
        }
    }
    
    /// Chrome only auto-hides on the Scan tab — every other tab needs its
    /// nav visible to be usable (Map, Upload, Archive all rely on it).
    private var isScanTab: Bool { currentTab == 0 }
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()
                
                TabView(selection: $currentTab) {
                    // Tab 0 — Scan (all users)
                    LandmarkScan(
                        onInteraction: revealChromeThenFade
                    )
                    .tag(0)
                    // No safeAreaInset padding here anymore — the bars now float
                    // transparently over the camera instead of pushing it down,
                    // so Scan gets the full frame edge-to-edge.
                    
                    
                    // Tab 1 — Map (all users)
                    LandmarkMapView()
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 70) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 60) }
                        .tag(1)
                    
                    // Tab 2 — Record (business only, hidden for others)
                    if authState.tier == .business {
                        LandmarkRecord { landmarkId in
                            pendingUploadLandmarkId = landmarkId
                            withAnimation(.easeInOut(duration: 0.25)) {
                                currentTab = 3
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
                .onChange(of: currentTab) { _, _ in
                    // Switching tabs always shows chrome again, then re-starts
                    // the fade timer if we landed back on Scan.
                    revealChromeThenFade()
                }
                
                // Floating top bar — fully transparent, no background plate.
                // Icons get a drop shadow instead, so the camera feed shows
                // through completely, like Snapchat's top row.
                VStack(spacing: 0) {
                    if chromeVisible {
                        topBar
                            .transition(.opacity)
                    }
                    
                    Spacer()
                    
                        bottomBar
                            
                }
                .animation(.easeOut(duration: 0.3), value: chromeVisible)
                
                if showSignUpPrompt {
                    signUpPromptOverlay
                }
            }
            .fullScreenCover(isPresented: $showSignUp) {
                NavigationStack {
                    Signup(
                        onSignupSuccess: { email in
                            showSignUp = false
                        },
                        onGoToLogin: {
                            showSignUp = false
                        }
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear { scheduleChromeFadeIfNeeded() }
    }
    
    // MARK: - Top Bar (transparent, floating)
    
    private var topBar: some View {
        HStack(spacing: 0) {
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
                    .shadow(color: .black.opacity(0.5), radius: 4)
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
        // No background — fully transparent, just floating over the content
    }
    
    
    // MARK: - Bottom Bar (minimal, floating, stays fully visible — no fade)
    
    private var bottomBar: some View {
        HStack(spacing: 0) {
            tabButton(title: "Scan", icon: "camera.aperture", tab: 0, locked: false)
            tabButton(title: "Map", icon: "map", tab: 1, locked: false)
            
            if authState.tier == .business {
                tabButton(title: "Record", icon: "video", tab: 2, locked: false)
            }
            
            tabButton(
                title: "Upload",
                icon: "arrow.up.circle",
                tab: authState.tier == .business ? 3 : 2,
                locked: authState.tier == .guest
            )
            
            tabButton(
                title: "Archive",
                icon: "folder.fill",
                tab: authState.tier == .business ? 4 : 3,
                locked: authState.tier == .guest
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        // single background for the whole row — removes the double-pill artifact
        .background(
            Capsule()
                .fill(Color.black.opacity(0.35))
        )
        .padding(.horizontal, 24)
        .padding(.bottom, 8)
    }
    
    // MARK: - Chrome Auto-Fade
    
    /// Cancels any pending fade, shows the chrome immediately, then schedules
    /// a new fade-out — but only while sitting on the Scan tab.
    private func revealChromeThenFade() {
        chromeFadeTask?.cancel()
        chromeVisible = true
        scheduleChromeFadeIfNeeded()
    }
    
    private func scheduleChromeFadeIfNeeded() {
        guard isScanTab else { return }
        chromeFadeTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000) // 3s
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.3)) { chromeVisible = false }
            }
        }
    }
    
    // MARK: - Sign Up Prompt Overlay
    
    var signUpPromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture {
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
                : Color.white.opacity(0.6)
            )
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            // background now sits flush within the shared capsule, no separate
            // shadow/offset fighting with the row's own background
            .background(
                Group {
                    if currentTab == tab && !locked {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.18))
                    }
                }
            )
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
                    .shadow(color: .black.opacity(0.5), radius: 4)
                Text(label)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.5))
            }
            .frame(width: 56, height: 48)
            .contentShape(Rectangle())
        }
    }
}
