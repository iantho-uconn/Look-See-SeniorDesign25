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

    enum AppState {
        case checkingSession
        case login
        case signup
        case confirmSignup
        case loadingModel
        case main
    }

    var body: some View {
        switch appState {
        case .checkingSession:
            // Blank/loading state while we determine if the user already
            // has a valid session, so returning users never see the Login
            // screen flash before being redirected.
            ProgressView()
                .task {
                    await vm.checkSession()
                    if vm.isSignedIn {
                        await authState.resolveTier()
                        appState = .loadingModel
                    } else {
                        appState = .login
                    }
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
                    appState = .confirmSignup
                },
                onGoToLogin: {
                    appState = .login
                }
            )

        case .confirmSignup:
            ConfirmSignup(email: pendingEmail, onConfirmed: {
                appState = .login
            })

        case .loadingModel:
            ModelLoadingScreen {
                appState = .main
            }

        case .main:
            Main()
                .environmentObject(vm)
                .environmentObject(authState)
                .toolbarVisibility(.hidden)
                .onChange(of: authState.didSignOut) { _, didSignOut in
                    if didSignOut {
                        authState.didSignOut = false
                        appState = .login
                    }
                }
        }
    }
}
