//
//  Buttons.swift
//  LookSeeProto
//

import SwiftUI
import Combine

struct Buttons: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    
    @Environment(\.scenePhase) var scenePhase
    
    @ObservedObject private var infoView = VariableContainer.shared

    @State private var showPromotion = false
    @State private var showBusinessAlert = false
    @State private var showSignUpPrompt = false
    @State private var showSignUp = false
    @State private var currentTab = 0

    @State private var pendingUploadLandmarkId: String?
    @State private var showRecordSheet = false
    
    // Redo logic
    @State private var redoLandmarkId: String?
    @State private var redoLandmarkLabel: String?
    @State private var redoLandmarkDesc: String?
    
    @State private var keepScanCameraAlive = false
    @State private var isRecordSheetAnimating = false

    @State private var chromeVisible = true
    @State private var chromeFadeTask: Task<Void, Never>?
    @State private var isDetecting = false
    @State private var showTutorial = false

    @State private var isReticlePulsing = false

    @State private var dragOffset: CGFloat = 0
    @State private var isDragging: Bool = false
    @GestureState private var isDraggingState: Bool = false
    @GestureState private var isSwipingState: Bool = false

    @State private var hasPreloadedMap = false

    // Generic One-Time Notification State
    @State private var showGenericNotification = false
    @State private var hasShownNotificationThisSession = false
    @State private var showMyLandmarksFromAlert = false
    
    @AppStorage("dismissedGlobalNotifs_v4") private var dismissedNotificationsString: String = ""

    private var isBusinessMode: Bool {
        return vm.hasActiveSubscription
    }

    var tabCount: Int { return 2 }

    private var isScanTab: Bool { currentTab == 0 }
    private var mapTabIndex: Int { 1 }

    private var topBarTitle: String {
        if currentTab == 0 { return "LookSee" }
        return "Map"
    }

    var isActive: Bool = true
    
    var isScanCameraActive: Bool {
        if showRecordSheet || isRecordSheetAnimating || showMyLandmarksFromAlert { return false }
        return true
    }

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
                        .simultaneousGesture(
                            (currentTab != mapTabIndex && !infoView.infoView)
                                ? DragGesture(minimumDistance: 12)
                                    .updating($isSwipingState) { value, state, _ in
                                        if abs(value.translation.width) > abs(value.translation.height) {
                                            state = true
                                        }
                                    }
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

                    // 🚀 THE GENERIC 3-SECOND NOTIFICATION PILL
                    VStack {
                        if showGenericNotification && currentTab == 0 {
                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                withAnimation(.easeOut(duration: 0.3)) { showGenericNotification = false }
                                showMyLandmarksFromAlert = true
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "flag.fill").foregroundColor(.white)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Action Required")
                                            .font(.system(size: 14, weight: .bold, design: .rounded))
                                            .foregroundStyle(.white)
                                        Text("Attention is needed for landmarks.")
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundStyle(.white.opacity(0.9))
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundColor(.white.opacity(0.6))
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                                .background(
                                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                                        .fill(Color.red)
                                        .shadow(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
                                )
                                .padding(.horizontal, 24)
                            }
                            .buttonStyle(.plain)
                            .padding(.top, 70)
                            .transition(.move(edge: .top).combined(with: .opacity))
                        }
                        Spacer()
                    }
                    .zIndex(100)

                    if currentTab == mapTabIndex && !infoView.infoView {
                        mapEdgeSwipeZones(width: pageWidth)
                    }

                    if !infoView.infoView {
                        VStack(spacing: 0) {
                            if chromeVisible {
                                topBar.transition(.opacity)
                            }
                            Spacer()
                            if chromeVisible || !isScanTab {
                                bottomBar.transition(.move(edge: .bottom).combined(with: .opacity))
                            }
                        }
                        .transition(.opacity)
                        .animation(.easeOut(duration: 0.3), value: chromeVisible)
                        .allowsHitTesting(!isSwipingState)
                    }

                    if showSignUpPrompt { signUpPromptOverlay }

                    if infoView.infoView {
                        ZStack {
                            Color.black
                                .opacity(0.45)
                                .ignoresSafeArea()
                                .onTapGesture {
                                    withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) {
                                        infoView.dismissLandmark()
                                    }
                                }

                            PopUp()
                        }
                        .zIndex(100)
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    }
                }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 15)
                        .updating($isDraggingState) { value, state, transaction in
                            state = true
                        }
                )
                .allowsHitTesting(!isDraggingState)
                .onChange(of: infoView.infoView) { _, isShowingPopup in
                    chromeFadeTask?.cancel()

                    if isShowingPopup {
                        withAnimation(.easeOut(duration: 0.15)) {
                            chromeVisible = false
                        }
                    } else {
                        revealChromeThenFade()
                    }
                }
                .animation(
                    .spring(response: 0.35, dampingFraction: 0.82),
                    value: infoView.infoView
                )
            }
            .fullScreenCover(isPresented: $showMyLandmarksFromAlert) {
                LazyView(
                    NavigationStack {
                        BusinessLandmarksView()
                            .toolbar {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button("Close") { showMyLandmarksFromAlert = false }
                                        .fontWeight(.bold)
                                }
                            }
                    }
                )
            }
            .fullScreenCover(isPresented: $showRecordSheet) {
                LandmarkRecord(
                    isNavVisible: .constant(true),
                    isActive: true,
                    existingLandmarkId: redoLandmarkId,
                    existingLabel: redoLandmarkLabel,
                    existingDescription: redoLandmarkDesc,
                    onAddMoreMedia: { landmarkId in
                        pendingUploadLandmarkId = landmarkId
                        showRecordSheet = false
                    }
                )
            }
            .onChange(of: showRecordSheet) { _, isShown in
                if !isShown {
                    isRecordSheetAnimating = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        isRecordSheetAnimating = false
                        redoLandmarkId = nil
                        redoLandmarkLabel = nil
                        redoLandmarkDesc = nil
                    }
                }
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
            updateIdleTimer(for: currentTab, active: isActive)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                hasPreloadedMap = true
            }
            
            checkForGlobalNotifications()
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("CheckGlobalNotifications"))) { _ in
            if currentTab == 0 && !showGenericNotification {
                checkForGlobalNotifications()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("TriggerRedoRecord"))) { notif in
            if let userInfo = notif.userInfo as? [String: String],
               let id = userInfo["id"],
               let label = userInfo["label"],
               let desc = userInfo["description"] {
                self.redoLandmarkId = id
                self.redoLandmarkLabel = label
                self.redoLandmarkDesc = desc
                
                // Gives iOS 0.6 seconds to release the camera lenses before opening Record
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    self.showRecordSheet = true
                }
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                checkForGlobalNotifications()
            }
        }
        .onChange(of: currentTab) { _, newTab in
            revealChromeThenFade()
            updateIdleTimer(for: newTab, active: isActive)
        }
        .onChange(of: isActive) { _, newActive in
            updateIdleTimer(for: currentTab, active: newActive)
        }
    }

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
                isNavVisible: $chromeVisible,
                isActive: isScanCameraActive
            )
        } else if index == mapTabIndex {
            LandmarkMapView()
                .safeAreaInset(edge: .top) { Color.clear.frame(height: 45) }
                .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
                .opacity(hasPreloadedMap ? 1.0 : 0.99)
        } else {
            EmptyView()
        }
    }

    private func clampedDragOffset(_ translation: CGFloat, width: CGFloat) -> CGFloat {
        if currentTab == 0 && translation > 0 { return translation * 0.35 }
        if currentTab == tabCount - 1 && translation < 0 { return translation * 0.35 }
        return translation
    }

    private func commitDrag(translation: CGFloat, predicted: CGFloat, width: CGFloat) {
        let threshold = width * 0.28
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

    private func updateIdleTimer(for tab: Int, active: Bool) {
        let shouldDisableAutoLock = (tab == 0 && active )
        if shouldDisableAutoLock {
            if !UIApplication.shared.isIdleTimerDisabled {
                UIApplication.shared.isIdleTimerDisabled = true
            }
        } else {
            if UIApplication.shared.isIdleTimerDisabled {
                UIApplication.shared.isIdleTimerDisabled = false
            }
        }
    }
    
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

            // 🚀 RESTORED NATIVE NAVIGATION!
            // Wraps Settings in LazyView so your profile picture doesn't eagerly load and blink the camera!
            NavigationLink {
                LazyView(Settings().environmentObject(vm))
                    .navigationTitle("") // Hides the big text at the top, leaves the back button!
            } label: {
                NavButton(icon: "line.3.horizontal", label: "Menu")
            }
            .simultaneousGesture(TapGesture().onEnded {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            })
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 10)
    }

    private var bottomBar: some View {
        HStack(alignment: .center, spacing: 0) {
            
            tabButton(title: "Scan", icon: "viewfinder", tab: 0, locked: false)
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                if !isBusinessMode {
                    showSignUpPrompt = true
                } else {
                    showRecordSheet = true
                }
            } label: {
                VStack(spacing: 4) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "video.fill")
                            .font(.system(size: 22, weight: .medium))
                        if !isBusinessMode {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 10))
                                .offset(x: 12, y: -4)
                        }
                    }
                    Text("Record")
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                }
                .foregroundStyle(Color.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            
            tabButton(title: "Map", icon: "map", tab: mapTabIndex, locked: false)
            
        }
        .padding(.horizontal, 10)
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
                    } else {
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
        .overlay(alignment: .bottom) {
            if currentTab == tab && !locked {
                Capsule()
                    .fill(Color(red: 0.22, green: 0.49, blue: 1.00))
                    .frame(width: 24, height: 3)
                    .offset(y: -2)
            }
        }
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

    // 🚀 Shows ONLY ONCE per session. Automatically disappears after 3 seconds.
    private func checkForGlobalNotifications() {
        guard vm.isSignedIn, !hasShownNotificationThisSession else { return }
        
        Task {
            let silentVM = BusinessLandmarksViewModel()
            await silentVM.loadLandmarks()
            
            let dismissedString = UserDefaults.standard.string(forKey: "dismissedGlobalNotifs_v4") ?? ""
            var dismissedSet = Set(dismissedString.split(separator: ",").map(String.init))
            
            if let needsMediaLandmark = silentVM.landmarks.first(where: { $0.status == "NEEDS_MORE_MEDIA" && !dismissedSet.contains($0.landmarkId) }) {
                
                dismissedSet.insert(needsMediaLandmark.landmarkId)
                UserDefaults.standard.set(dismissedSet.joined(separator: ","), forKey: "dismissedGlobalNotifs_v4")
                
                await MainActor.run {
                    hasShownNotificationThisSession = true
                    
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) {
                        showGenericNotification = true
                        chromeVisible = true
                    }
                    
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                        withAnimation(.easeOut(duration: 0.3)) {
                            showGenericNotification = false
                        }
                    }
                }
            }
        }
    }
}

// 🚀 Stops the profile picture from eagerly preloading!
struct LazyView<Content: View>: View {
    let build: () -> Content
    init(_ build: @autoclosure @escaping () -> Content) {
        self.build = build
    }
    var body: Content {
        build()
    }
}
