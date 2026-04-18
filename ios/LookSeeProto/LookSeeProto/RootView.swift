//
//  RootView.swift
//  LookSeeProto
//

import SwiftUI

struct RootView: View {
    @EnvironmentObject var vm: AuthViewModel
    @State private var appState: AppState = .login

    enum AppState {
        case login
        case loadingModel
        case main
    }

    var body: some View {
        switch appState {
        case .login:
            Login(
                vm: vm,
                onSignedIn: {
                    appState = .loadingModel
                },
                onGoToSignup: {
                    // TODO: point this at your signup screen
                }
            )
            .task {
                // If already signed in from a previous session, skip login
                await vm.checkSession()
                if vm.isSignedIn {
                    appState = .loadingModel
                }
            }

        case .loadingModel:
            ModelLoadingScreen {
                appState = .main
            }

        case .main:
            Main()
                .environmentObject(vm)
                .toolbarVisibility(.hidden)
        }
    }
}
