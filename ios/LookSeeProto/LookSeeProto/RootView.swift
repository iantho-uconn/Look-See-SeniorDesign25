//
//  RootView.swift
//  LookSeeProto
//
import SwiftUI

struct RootView: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    @State private var appState: AppState = .checkingSession
    @State private var pendingEmail = ""

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
                onSignupSuccess: { email in
                    pendingEmail = email
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
                    print(" [RootView] resolveTier complete — tier: \(authState.tier)")
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
    }

    private func resetLoadingFlags() {
        isModelLoadingDone = false
        isAuthResolutionDone = false
    }
}
