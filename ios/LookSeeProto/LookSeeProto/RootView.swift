//
//  RootView.swift
//  LookSeeProto
//
import SwiftUI

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    @State private var appState: AppState = .checkingSession
    @State private var pendingEmail = ""
    @State private var showBusinessPlans = false
    @State private var presentBusinessPlansAfterLoad = false
    @StateObject private var signupPresenter = SettingsPresenter()

    // NEW — coordinates the two concurrent tasks that both need to finish
    // before we're allowed to leave the loading screen.
    @State private var isModelLoadingDone = false
    @State private var isAuthResolutionDone = false

    enum AppState {
        case checkingSession
        case login
        case signup
        case loadingModel
        case main
    }

    var body: some View {
        Group {
            switch appState {
        case .checkingSession:
            // Immediately hand off to loadingModel — the session check now
            // happens concurrently with model loading instead of blocking
            // before it starts.
            ProgressView()
                .task {
                    print("[RootView] Entering loadingModel — auth + model load run concurrently")
                    appState = .loadingModel
                }

        case .login:
            Login(
                vm: vm,
                onSignedIn: {
                    Task {
                        await authState.resolveTier()
                        appState = .loadingModel
                    }
                },
                onGoToSignup: {
                    appState = .signup
                },
                onContinueAsGuest: {
                    authState.tier = .guest
                    appState = .loadingModel
                }
            )

        case .signup:
            Signup(
                onSignupSuccess: { email, wantsBusiness in
                    pendingEmail = email
                    presentBusinessPlansAfterLoad = wantsBusiness
                    appState = .loadingModel
                },
                onGoToLogin: {
                    appState = .login
                }
            )

        case .loadingModel:
            ModelLoadingScreen {
                print("🧠 [RootView] Model loading finished")
                isModelLoadingDone = true
                advanceIfReady()
            }
            .task {
                // Runs concurrently with ModelLoadingScreen's own work.
                await vm.checkSession()
                print("[RootView] checkSession complete — isSignedIn: \(vm.isSignedIn)")
                if vm.isSignedIn {
                    await authState.resolveTier()
                    // Stats determine paid access; Cognito groups may lag expiration.
                    syncVerifiedTier()
                    print(" [RootView] resolveTier complete — tier: \(authState.tier)")
                } else {
                    authState.tier = .guest
                    authState.isReady = true
                }
                isAuthResolutionDone = true
                advanceIfReady()
            }

        case .main:
            Main()
                .environmentObject(vm)
                .environmentObject(authState)
                .toolbarVisibility(.hidden)
                .onChange(of: authState.didSignOut) { _, didSignOut in
                    if didSignOut {
                        authState.didSignOut = false
                        resetLoadingFlags()
                        appState = .loadingModel
                    }
                }
            }
        }
        .onChange(of: vm.currentTier) { _, _ in syncVerifiedTier() }
        .onChange(of: vm.hasVerifiedSubscriptionStatus) { _, _ in syncVerifiedTier() }
        .onChange(of: authState.tier) { _, _ in syncVerifiedTier() }
        .task(id: shouldRefreshSubscription) {
            guard shouldRefreshSubscription else { return }
            // Refresh on foreground entry and every minute while Main is active.
            // Do not run checkSession here: a transient network error must not sign out.
            while !Task.isCancelled {
                if vm.isSignedIn {
                    await vm.fetchUserUsageStats()
                    guard !Task.isCancelled else { return }
                    syncVerifiedTier()
                }
                do {
                    try await Task.sleep(nanoseconds: 60_000_000_000)
                } catch {
                    return
                }
            }
        }
        .sheet(isPresented: $showBusinessPlans) {
            SubscriptionPlans(presenter: signupPresenter)
        }
    }

    private var shouldRefreshSubscription: Bool {
        scenePhase == .active && appState == .main
    }

    private func syncVerifiedTier() {
        // Missing/failed stats must not be interpreted as expiration or logout.
        guard vm.hasVerifiedSubscriptionStatus else { return }
        if authState.tier != vm.currentTier {
            authState.tier = vm.currentTier
        }
        authState.isReady = true
    }

    // Only leave the loading screen once BOTH the model and the auth check
    // have finished — avoids flashing Main before authState.tier is resolved.
    private func advanceIfReady() {
        guard isModelLoadingDone, isAuthResolutionDone else {
            print("[RootView] Waiting — modelDone: \(isModelLoadingDone), authDone: \(isAuthResolutionDone)")
            return
        }
        print("✅ [RootView] Both ready — advancing to .main")
        resetLoadingFlags()
        appState = .main

        if presentBusinessPlansAfterLoad {
            presentBusinessPlansAfterLoad = false
            signupPresenter.subscriptionStartingTab = 0
            DispatchQueue.main.async {
                showBusinessPlans = true
            }
        }
    }

    private func resetLoadingFlags() {
        isModelLoadingDone = false
        isAuthResolutionDone = false
    }
}
