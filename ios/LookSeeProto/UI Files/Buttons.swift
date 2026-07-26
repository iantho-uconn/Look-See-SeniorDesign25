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
    
    @State private var chromeVisible = true
    @State private var chromeFadeTask: Task<Void, Never>?
    @State private var isDetecting = false
    @State private var showTutorial = false
    
    @State private var showSideMenu = false
    @State private var isReticlePulsing = false
    
    // 🚀 THE FIX: Completely ignores Cognito authState.tier.
    // If they haven't actually paid (or aren't legacy), they get NOTHING.
    private var isBusinessMode: Bool {
        return vm.hasActiveSubscription
    }
    
    var tabCount: Int {
        return isBusinessMode ? 3 : 2
    }
    
    private var isScanTab: Bool { currentTab == 0 }
    
    private var mapTabIndex: Int {
        return tabCount - 1
    }
    
    private var topBarTitle: String {
        if currentTab == 0 { return "LookSee" }
        if currentTab == mapTabIndex { return "Map" }
        return "Record"
    }
    
    var isActive: Bool = true
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(uiColor: .systemBackground)
                    .ignoresSafeArea()
                
                TabView(selection: $currentTab) {
                    LandmarkScan(
                        onTap: revealChromeThenFade,
                        isDetecting: $isDetecting,
                        isNavVisible: $chromeVisible
                    )
                    .tag(0)
                    
                    if isBusinessMode {
                        LandmarkRecord { landmarkId in
                            pendingUploadLandmarkId = landmarkId
                            withAnimation(.easeInOut(duration: 0.25)) { currentTab = 0 }
                        }
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                        .tag(1)
                    }
                    
                    LandmarkMapView()
                        .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                        .tag(mapTabIndex)
                }
                .scrollDismissesKeyboard(.immediately)
                .ignoresSafeArea()
                .toolbar(.hidden, for: .tabBar)
                .animation(.spring(response: 0.35, dampingFraction: 0.8), value: currentTab)
                .onChange(of: currentTab) { _, _ in revealChromeThenFade() }
                
                .onChange(of: isDetecting) { _, detecting in
                    if detecting && isScanTab {
                        chromeFadeTask?.cancel()
                        withAnimation(.easeOut(duration: 0.1)) { chromeVisible = false }
                    }
                }
                
                if currentTab == mapTabIndex { mapEdgeSwipeZones }
                
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
                
                if showSideMenu {
                    Color.black.opacity(0.4)
                        .ignoresSafeArea()
                        .onTapGesture {
                            withAnimation(.easeOut(duration: 0.25)) { showSideMenu = false }
                        }
                        .transition(.opacity)
                }
                
                GeometryReader { proxy in
                    HStack(spacing: 0) {
                        Spacer()
                        Settings()
                            .environmentObject(vm)
                            .frame(width: proxy.size.width * 0.75)
                            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
                            .offset(x: showSideMenu ? 0 : proxy.size.width)
                    }
                }
                .animation(.easeOut(duration: 0.25), value: showSideMenu)
                
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 30).onEnded { value in
                    if showSideMenu {
                        if value.translation.width > 40 {
                            withAnimation(.easeOut(duration: 0.25)) { showSideMenu = false }
                        }
                        return
                    }
                    
                    guard currentTab != mapTabIndex else { return }
                    if abs(value.translation.width) > abs(value.translation.height) {
                        if value.translation.width > 40 && currentTab > 0 {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            withAnimation(.interactiveSpring(response: 0.35, dampingFraction: 0.85, blendDuration: 0.2)) { currentTab -= 1 }
                        } else if value.translation.width < -40 && currentTab < (tabCount - 1) {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
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
                    .presentationDetents([.fraction(0.40)])
                    .presentationDragIndicator(.visible)
                    .presentationBackground(.ultraThinMaterial)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear {
            scheduleChromeFadeIfNeeded()
            viewfinderTimingReset()
            UITabBar.appearance().isHidden = true
        }
    }
    
    private var mapEdgeSwipeZones: some View {
        HStack {
            Color.white.opacity(0.001).frame(width: 40).frame(maxHeight: .infinity)
                .highPriorityGesture(DragGesture(minimumDistance: 15).onEnded { value in
                    if value.translation.width > 30 && currentTab > 0 {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { currentTab -= 1 }
                    }
                })
            Spacer()
            Color.white.opacity(0.001).frame(width: 40).frame(maxHeight: .infinity)
                .highPriorityGesture(DragGesture(minimumDistance: 15).onEnded { value in
                    if value.translation.width < -30 && currentTab < (tabCount - 1) {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { currentTab += 1 }
                    }
                })
        }
        .ignoresSafeArea()
    }
    
    private var topBar: some View {
        HStack(spacing: 0) {
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                showTutorial = true
            } label: {
                NavButton(icon: "info.circle", label: "Info")
            }
            
            Spacer()
            
            Text(topBarTitle)
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.8), radius: 4, x: 0, y: 2)
                .contentShape(Rectangle())
                .highPriorityGesture(
                    TapGesture().onEnded {
                        if isBusinessMode { showPromotion = true }
                        else { showBusinessAlert = true }
                    }
                )
                .sheet(isPresented: $showPromotion) { PromotionEditor() }
                .alert("Premium Account Required", isPresented: $showBusinessAlert) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text("You need an active subscription to access the Promotion Editor.")
                }
            Spacer()
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                withAnimation(.easeOut(duration: 0.25)) {
                    showSideMenu = true
                }
            } label: {
                NavButton(icon: "line.3.horizontal", label: "Menu")
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 10)
    }
    
    private var bottomBar: some View {
        HStack(spacing: 0) {
            tabButton(title: "Scan", icon: "camera.aperture", tab: 0, locked: false)
            if isBusinessMode {
                tabButton(title: "Record", icon: "video", tab: 1, locked: false)
            }
            tabButton(title: "Map", icon: "map", tab: mapTabIndex, locked: false)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Capsule().fill(.ultraThinMaterial))
        .overlay(Capsule().stroke(Color(uiColor: .separator).opacity(0.5), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }
    
    private var tutorialContent: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            
            VStack(spacing: 24) {
                Group {
                    if currentTab == 0 {
                        Image(systemName: "viewfinder")
                            .font(.system(size: 70, weight: .ultraLight))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .scaleEffect(isReticlePulsing ? 1.15 : 0.95)
                            .opacity(isReticlePulsing ? 1.0 : 0.4)
                        
                        VStack(spacing: 8) {
                            Text("How to Scan")
                                .font(.system(size: 24, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                            Text("Point your camera at a landmark. Keep the object well-lit and steady. LookSee will identify it automatically.")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                    } else if currentTab == mapTabIndex {
                        Image(systemName: "map.fill")
                            .font(.system(size: 60))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .shadow(color: Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.5), radius: 10, x: 0, y: 5)
                        
                        VStack(spacing: 8) {
                            Text("Explore the Map")
                                .font(.system(size: 24, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                            Text("Find valid landmarks around you to scan. Use the search bar or filters to narrow down locations.")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        Image(systemName: "video.fill")
                            .font(.system(size: 60))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .shadow(color: Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.5), radius: 10, x: 0, y: 5)
                        
                        VStack(spacing: 8) {
                            Text("Record Landmark")
                                .font(.system(size: 24, weight: .bold, design: .rounded))
                                .foregroundStyle(.primary)
                            Text("Record a short video of a nearby landmark to help improve our recognition models.")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                
                Spacer()
            }
            .padding(.top, 32)
        }
    }
    
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
    
    private func viewfinderTimingReset() {
        withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
            isReticlePulsing = true
        }
    }
    
    var signUpPromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.4).ignoresSafeArea()
                .onTapGesture {
                    showSignUpPrompt = false
                    withAnimation { currentTab = 0 }
                }
            VStack(spacing: 24) {
                ZStack {
                    Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15)).frame(width: 70, height: 70)
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)).foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                }
                VStack(spacing: 8) {
                    Text("Sign up to upload")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("Create an account to start contributing landmarks and help improve recognition.")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                VStack(spacing: 12) {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        showSignUpPrompt = false; showSignUp = true
                    } label: {
                        HStack(spacing: 8) {
                            Text("Create Account").font(.system(size: 17, weight: .bold, design: .rounded))
                            Image(systemName: "arrow.right").font(.system(size: 15, weight: .bold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        showSignUpPrompt = false
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { currentTab = 0 }
                    } label: {
                        Text("Not now")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color(uiColor: .secondarySystemFill))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
            }
            .padding(30)
            .background(Color(uiColor: .systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color(uiColor: .separator).opacity(0.5), lineWidth: 0.5))
            .padding(.horizontal, 24)
        }
    }
    
    @ViewBuilder
    func tabButton(title: String, icon: String, tab: Int, locked: Bool) -> some View {
        VStack(spacing: 4) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: icon).font(.system(size: 22, weight: .medium))
                if locked { Image(systemName: "lock.fill").font(.system(size: 10)).foregroundStyle(.secondary).offset(x: 8, y: -4) }
            }
            Text(title).font(.system(size: 11, weight: .bold, design: .rounded))
        }
        .foregroundStyle(locked ? Color(uiColor: .quaternaryLabel) : currentTab == tab ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.secondary)
        .frame(maxWidth: .infinity).padding(.vertical, 10)
        .background(Group { if currentTab == tab && !locked { RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15)) } })
        .contentShape(Rectangle())
        .highPriorityGesture(TapGesture().onEnded {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if locked { showSignUpPrompt = true }
            else { withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { currentTab = tab } }
        })
    }
    
    private struct NavButton: View {
        let icon: String
        let label: String
        var body: some View {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.8), radius: 4, x: 0, y: 2)
                Text(label)
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.8), radius: 4, x: 0, y: 2)
            }
            .frame(width: 56, height: 48).contentShape(Rectangle())
        }
    }
}
