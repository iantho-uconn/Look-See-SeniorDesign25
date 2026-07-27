//
//  Buttons.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/25/26.
//
//  Updated: TabView-based paging replaced with a manual HStack pager so
//  swiping shows a live, finger-following preview of the adjacent page
//  (camera/record/map) instead of only snapping after release.
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

    // Live horizontal offset while a swipe is in progress. 0 when idle.
    @State private var dragOffset: CGFloat = 0

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

    private var recordTabIndex: Int { 1 }

    private var topBarTitle: String {
        if currentTab == 0 { return "LookSee" }
        if currentTab == mapTabIndex { return "Map" }
        return "Record"
    }

    var isActive: Bool = true

    var body: some View {
        NavigationStack {
            GeometryReader { proxy in
                let pageWidth = proxy.size.width

                ZStack {
                    Color(uiColor: .systemBackground)
                        .ignoresSafeArea()

                    pager()
                        .onChange(of: currentTab) { _, _ in revealChromeThenFade() }
                        .onChange(of: isDetecting) { _, detecting in
                            if detecting && isScanTab {
                                chromeFadeTask?.cancel()
                                withAnimation(.easeOut(duration: 0.1)) { chromeVisible = false }
                            }
                        }
                        // Full-width live-tracking swipe — active on any tab
                        // except Map, where the map's own pan/zoom gestures
                        // need the whole surface (edge zones handle that case).
                        .simultaneousGesture(
                            currentTab != mapTabIndex
                                ? DragGesture(minimumDistance: 12)
                                    .onChanged { value in
                                        guard abs(value.translation.width) > abs(value.translation.height) else { return }
                                        dragOffset = clampedDragOffset(value.translation.width, width: pageWidth)
                                    }
                                    .onEnded { value in
                                        guard abs(value.translation.width) > abs(value.translation.height) else {
                                            resetDrag()
                                            return
                                        }
                                        commitDrag(
                                            translation: value.translation.width,
                                            predicted: value.predictedEndTranslation.width,
                                            width: pageWidth
                                        )
                                    }
                                : nil
                        )

                    if currentTab == mapTabIndex {
                        mapEdgeSwipeZones(width: pageWidth)
                    }

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

                    HStack(spacing: 0) {
                        Spacer()
                        Settings()
                            .environmentObject(vm)
                            .frame(width: pageWidth * 0.75)
                            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
                            .offset(x: showSideMenu ? 0 : pageWidth)
                    }
                    .animation(.easeOut(duration: 0.25), value: showSideMenu)
                }
                .simultaneousGesture(
                    // Side-menu swipe-to-dismiss stays independent of paging.
                    DragGesture(minimumDistance: 30).onEnded { value in
                        guard showSideMenu else { return }
                        if value.translation.width > 40 {
                            withAnimation(.easeOut(duration: 0.25)) { showSideMenu = false }
                        }
                    }
                )
            }
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

    // MARK: - Pager

    /// Lays every tab's content side-by-side in one HStack and shifts it by
    /// -currentTab * width, live-adjusted by dragOffset while swiping. This
    /// keeps the adjacent page mounted and visible as it slides in, instead
    /// of only appearing after the gesture ends.
    private func pager() -> some View {
        GeometryReader { proxy in
            let width = proxy.size.width

            HStack(spacing: 0) {
                ForEach(0..<tabCount, id: \.self) { index in
                    pageView(for: index)
                        .frame(width: width)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .offset(x: -CGFloat(currentTab) * width + dragOffset)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .clipped()
            .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func pageView(for index: Int) -> some View {
        if index == 0 {
            LandmarkScan(
                onTap: revealChromeThenFade,
                isDetecting: $isDetecting,
                isNavVisible: $chromeVisible
            )
        } else if isBusinessMode && index == recordTabIndex {
            LandmarkRecord { landmarkId in
                pendingUploadLandmarkId = landmarkId
                withAnimation(.easeInOut(duration: 0.25)) { currentTab = 0 }
            }
            .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
        } else if index == mapTabIndex {
            LandmarkMapView()
                .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
        } else {
            EmptyView()
        }
    }

    // MARK: - Drag handling

    /// Prevents dragging past the first or last tab from producing an
    /// unbounded offset — light rubber-band resistance at the ends.
    private func clampedDragOffset(_ translation: CGFloat, width: CGFloat) -> CGFloat {
        if currentTab == 0 && translation > 0 {
            return translation * 0.35
        }
        if currentTab == tabCount - 1 && translation < 0 {
            return translation * 0.35
        }
        return translation
    }

    private func commitDrag(translation: CGFloat, predicted: CGFloat, width: CGFloat) {
        let threshold = width * 0.28
        // A fast flick should commit even if the finger didn't travel the
        // full threshold distance — use whichever translation is larger.
        let effective = abs(predicted) > abs(translation) ? predicted : translation

        var newTab = currentTab
        if effective < -threshold && currentTab < tabCount - 1 {
            newTab += 1
        } else if effective > threshold && currentTab > 0 {
            newTab -= 1
        }

        if newTab != currentTab {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }

        withAnimation(.interactiveSpring(response: 0.32, dampingFraction: 0.86, blendDuration: 0.15)) {
            currentTab = newTab
            dragOffset = 0
        }
    }

    private func resetDrag() {
        withAnimation(.interactiveSpring(response: 0.32, dampingFraction: 0.86, blendDuration: 0.15)) {
            dragOffset = 0
        }
    }

    /// Narrow live-drag strips on the Map tab, so swiping in/out of the map
    /// works without hijacking the map's own pan/zoom gestures everywhere else.
    private func mapEdgeSwipeZones(width: CGFloat) -> some View {
        HStack {
            Color.white.opacity(0.001)
                .frame(width: 40)
                .frame(maxHeight: .infinity)
                .highPriorityGesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { value in
                            guard value.translation.width > 0, currentTab > 0 else { return }
                            dragOffset = clampedDragOffset(value.translation.width, width: width)
                        }
                        .onEnded { value in
                            commitDrag(
                                translation: value.translation.width,
                                predicted: value.predictedEndTranslation.width,
                                width: width
                            )
                        }
                )
            Spacer()
            Color.white.opacity(0.001)
                .frame(width: 40)
                .frame(maxHeight: .infinity)
                .highPriorityGesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { value in
                            guard value.translation.width < 0, currentTab < tabCount - 1 else { return }
                            dragOffset = clampedDragOffset(value.translation.width, width: width)
                        }
                        .onEnded { value in
                            commitDrag(
                                translation: value.translation.width,
                                predicted: value.predictedEndTranslation.width,
                                width: width
                            )
                        }
                )
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
                tabButton(title: "Record", icon: "video", tab: recordTabIndex, locked: false)
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
