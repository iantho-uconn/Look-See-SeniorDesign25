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
    
    // Chrome visibility & Detection State
    @State private var chromeVisible = true
    @State private var chromeFadeTask: Task<Void, Never>?
    @State private var isDetecting = false // Triggers focus mode when scanning
    @State private var showTutorial = false // Triggers Info sheet
    
    var tabCount: Int {
        switch authState.tier {
        case .guest: return 4
        case .authenticated: return 4
        case .business: return 5
        }
    }
    
    private var isScanTab: Bool { currentTab == 0 }
    
    // DYNAMIC TITLE
    private var topBarTitle: String {
        switch currentTab {
        case 0: return "LookSee"
        case 1: return "Map"
        case 2: return authState.tier == .business ? "Record" : "Upload"
        case 3: return authState.tier == .business ? "Upload" : "Offline Archive"
        case 4: return "Offline Archive"
        default: return "LookSee"
        }
    }
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()
                
                TabView(selection: $currentTab) {
                    // Tab 0 — Scan
                    LandmarkScan(
                        onTap: revealChromeThenFade,
                        isDetecting: $isDetecting,
                        isNavVisible: $chromeVisible
                    )
                    .tag(0)
                    
                    // Tab 1 — Map
                    LandmarkMapView()
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                        .tag(1)
                    
                    // Tab 2 — Record (business only)
                    if authState.tier == .business {
                        LandmarkRecord { landmarkId in
                            pendingUploadLandmarkId = landmarkId
                            withAnimation(.easeInOut(duration: 0.25)) { currentTab = 3 }
                        }
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                        .tag(2)
                    }
                    
                    // Tab 3 (or 2) — Upload
                    if authState.tier == .authenticated || authState.tier == .business {
                        Tier2LandmarkRecord(
                            initialLandmarkId: pendingUploadLandmarkId,
                            onInitialLandmarkConsumed: { pendingUploadLandmarkId = nil }
                        )
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                        .tag(authState.tier == .business ? 3 : 2)
                    } else {
                        Color(red: 0.06, green: 0.06, blue: 0.10)
                            .ignoresSafeArea()
                            .tag(authState.tier == .business ? 3 : 2)
                    }
                    
                    // Tab 4 (or 3) — Archive
                    if authState.tier == .authenticated || authState.tier == .business {
                        ArchiveView()
                            .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                            .tag(authState.tier == .business ? 4 : 3)
                    } else {
                        Color(red: 0.06, green: 0.06, blue: 0.10)
                            .ignoresSafeArea()
                            .tag(authState.tier == .business ? 4 : 3)
                    }
                }
                .scrollDismissesKeyboard(.immediately)
                // FIX: Deleted the .defaultScrollAnchor(.bottom) that was hijacking the page
                .ignoresSafeArea()
                .toolbar(.hidden, for: .tabBar)
                .animation(.easeInOut(duration: 0.2), value: currentTab)
                .onChange(of: currentTab) { _, _ in revealChromeThenFade() }
                
                .onChange(of: isDetecting) { _, detecting in
                    if detecting && isScanTab {
                        chromeFadeTask?.cancel()
                        withAnimation(.easeOut(duration: 0.1)) { chromeVisible = false }
                    }
                }
                
                if currentTab == 1 { mapEdgeSwipeZones }
                
                VStack(spacing: 0) {
                    if chromeVisible {
                        topBar.transition(.opacity)
                    }
                    Spacer()
                    if chromeVisible || !isScanTab {
                        bottomBar.transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: 0.3), value: chromeVisible)
                
                if showSignUpPrompt { signUpPromptOverlay }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 30).onEnded { value in
                    guard currentTab != 1 else { return }
                    if abs(value.translation.width) > abs(value.translation.height) {
                        if value.translation.width > 40 && currentTab > 0 {
                            withAnimation(.interactiveSpring(response: 0.35, dampingFraction: 0.85, blendDuration: 0.2)) { currentTab -= 1 }
                        } else if value.translation.width < -40 && currentTab < (tabCount - 1) {
                            withAnimation(.interactiveSpring(response: 0.35, dampingFraction: 0.85, blendDuration: 0.2)) { currentTab += 1 }
                        }
                    }
                }
            )
            .fullScreenCover(isPresented: $showSignUp) {
                NavigationStack {
                    Signup(onSignupSuccess: { _ in showSignUp = false }, onGoToLogin: { showSignUp = false })
                }
            }
            .sheet(isPresented: $showTutorial) {
                tutorialContent
                    .presentationDetents([.fraction(0.35)])
                    .presentationDragIndicator(.visible)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear {
            scheduleChromeFadeIfNeeded()
            UITabBar.appearance().isHidden = true
        }
    }
    
    // MARK: - Edge Swipe Zones (Map Only)
    private var mapEdgeSwipeZones: some View {
        HStack {
            Color.white.opacity(0.001).frame(width: 40).frame(maxHeight: .infinity)
                .highPriorityGesture(DragGesture(minimumDistance: 15).onEnded { value in
                    if value.translation.width > 30 && currentTab > 0 { withAnimation(.easeInOut(duration: 0.2)) { currentTab -= 1 } }
                })
            Spacer()
            Color.white.opacity(0.001).frame(width: 40).frame(maxHeight: .infinity)
                .highPriorityGesture(DragGesture(minimumDistance: 15).onEnded { value in
                    if value.translation.width < -30 && currentTab < (tabCount - 1) { withAnimation(.easeInOut(duration: 0.2)) { currentTab += 1 } }
                })
        }
        .ignoresSafeArea()
    }
    
    // MARK: - Top Bar
    private var topBar: some View {
        HStack(spacing: 0) {
            NavigationLink { Settings().environmentObject(vm) } label: {
                NavButton(icon: "gearshape.fill", label: "Settings")
            }
            Spacer()
            Text(topBarTitle)
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.5), radius: 4)
                .contentShape(Rectangle())
                .highPriorityGesture(
                    TapGesture().onEnded {
                        if authState.tier == .business { showPromotion = true }
                        else { showBusinessAlert = true }
                    }
                )
                .sheet(isPresented: $showPromotion) { PromotionEditor() }
                .alert("Business Account Required", isPresented: $showBusinessAlert) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text("You need a business account to access the Promotion Editor.")
                }
            Spacer()
            Button {
                showTutorial = true
            } label: {
                NavButton(icon: "questionmark.circle.fill", label: "Info")
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 10)
    }
    
    // MARK: - Bottom Bar
    private var bottomBar: some View {
        HStack(spacing: 0) {
            tabButton(title: "Scan", icon: "camera.aperture", tab: 0, locked: false)
            tabButton(title: "Map", icon: "map", tab: 1, locked: false)
            if authState.tier == .business {
                tabButton(title: "Record", icon: "video", tab: 2, locked: false)
            }
            tabButton(title: "Upload", icon: "arrow.up.circle", tab: authState.tier == .business ? 3 : 2, locked: authState.tier == .guest)
            tabButton(title: "Archive", icon: "folder.fill", tab: authState.tier == .business ? 4 : 3, locked: authState.tier == .guest)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Capsule().fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
        .overlay(Capsule().stroke(Color.white.opacity(0.15), lineWidth: 0.5))
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }
    
    // MARK: - Dynamic Tutorial Half-Sheet Content
    private var tutorialContent: some View {
        VStack(spacing: 16) {
            Group {
                switch currentTab {
                case 0:
                    Image(systemName: "viewfinder").font(.system(size: 40))
                    Text("How to Scan").font(.title2.weight(.bold))
                    Text("Point your camera at a landmark. Keep the object well-lit and steady. LookSee will identify it automatically.")
                case 1:
                    Image(systemName: "map").font(.system(size: 40))
                    Text("Explore the Map").font(.title2.weight(.bold))
                    Text("Find valid landmarks around you to scan. Use the search bar or filters to narrow down locations.")
                case 2, 3:
                    Image(systemName: "arrow.up.circle").font(.system(size: 40))
                    Text("Upload Media").font(.title2.weight(.bold))
                    Text("Record a short video or take a photo of a nearby landmark to help improve our recognition models.")
                default:
                    Image(systemName: "folder.fill").font(.system(size: 40))
                    Text("Offline Archive").font(.title2.weight(.bold))
                    Text("This is your offline folder. You can record and save videos here to upload later when you have a better connection.")
                }
            }
            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 32)
            
            Spacer()
        }
        .padding(.top, 24)
        .background(Color(red: 0.11, green: 0.11, blue: 0.16).ignoresSafeArea())
        .environment(\.colorScheme, .dark)
    }
    
    // MARK: - Chrome Auto-Fade
    private func revealChromeThenFade() {
        chromeFadeTask?.cancel()
        chromeVisible = true
        scheduleChromeFadeIfNeeded()
    }
    
    private func scheduleChromeFadeIfNeeded() {
        guard isScanTab else { return }
        chromeFadeTask = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if !isDetecting {
                    withAnimation(.easeOut(duration: 0.3)) { chromeVisible = false }
                }
            }
        }
    }
    
    // MARK: - Sign Up Prompt Overlay
    var signUpPromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
                .onTapGesture {
                    showSignUpPrompt = false
                    withAnimation { currentTab = 0 }
                }
            VStack(spacing: 20) {
                ZStack {
                    Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12)).frame(width: 70, height: 70)
                    Image(systemName: "arrow.up.circle").font(.system(size: 32)).foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                }
                VStack(spacing: 8) {
                    Text("Sign up to upload").font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    Text("Create an account to start contributing landmarks and help improve recognition.").font(.subheadline).foregroundStyle(Color.white.opacity(0.5)).multilineTextAlignment(.center).padding(.horizontal, 8)
                }
                VStack(spacing: 10) {
                    Button {
                        showSignUpPrompt = false; showSignUp = true
                    } label: {
                        HStack(spacing: 8) {
                            Text("Create Account").font(.system(size: 16, weight: .semibold))
                            Image(systemName: "arrow.right").font(.system(size: 14, weight: .semibold))
                        }
                        .foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color(red: 0.22, green: 0.49, blue: 1.00)).cornerRadius(14)
                    }
                    Button {
                        showSignUpPrompt = false; withAnimation(.easeInOut(duration: 0.25)) { currentTab = 0 }
                    } label: {
                        Text("Not now").font(.system(size: 15)).foregroundStyle(Color.white.opacity(0.4)).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.07)).cornerRadius(14)
                    }
                }
            }
            .padding(24).background(Color(red: 0.11, green: 0.11, blue: 0.16)).cornerRadius(24).overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.07), lineWidth: 0.5)).padding(.horizontal, 28)
        }
    }
    
    // MARK: - Tab Button
    @ViewBuilder
    func tabButton(title: String, icon: String, tab: Int, locked: Bool) -> some View {
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: icon).font(.system(size: 20, weight: .medium))
                if locked { Image(systemName: "lock.fill").font(.system(size: 8)).foregroundStyle(Color.white.opacity(0.4)).offset(x: 6, y: -4) }
            }
            Text(title).font(.system(size: 10, weight: .medium))
        }
        .foregroundStyle(locked ? Color.white.opacity(0.25) : currentTab == tab ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.white.opacity(0.6))
        .frame(maxWidth: .infinity).padding(.vertical, 8)
        .background(Group { if currentTab == tab && !locked { RoundedRectangle(cornerRadius: 10).fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.18)) } })
        .contentShape(Rectangle())
        .highPriorityGesture(TapGesture().onEnded {
            if locked { showSignUpPrompt = true }
            else { withAnimation(.easeInOut(duration: 0.25)) { currentTab = tab } }
        })
    }
    
    // MARK: - Nav Button
    private struct NavButton: View {
        let icon: String
        let label: String
        var body: some View {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 24, weight: .medium)).foregroundStyle(.white).shadow(color: .black.opacity(0.5), radius: 4)
                Text(label).font(.system(size: 12, weight: .medium)).foregroundStyle(Color.white.opacity(0.5))
            }
            .frame(width: 56, height: 48).contentShape(Rectangle())
        }
    }
}
