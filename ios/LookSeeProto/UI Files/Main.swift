//
//  Main.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct Main: View {
    var body: some View {
        AuthFlowView()
    }
}
struct AuthFlowView: View {
    @StateObject private var vm = AuthViewModel()
    
    @EnvironmentObject var authState: AuthState
    
    @State private var showConfirm = false
    @State private var showLogin = false
    @State private var pendingEmail = ""
    @State private var isSignedIn = false

    var body: some View {
        NavigationStack {
            if !authState.isReady {
                // Sits here briefly on launch while checkSession + resolveTier run
                ProgressView()
            }
            else if vm.isSignedIn {
                Buttons()
                    .environmentObject(vm)
                    .environmentObject(authState)
            }
            else if showLogin {
                Login(vm: vm, onSignedIn: {
                    Task { await authState.resolveTier() }   // resolve tier right after login
                    vm.isSignedIn = true
                }, onGoToSignup: {
                    showLogin = false
                })
            }
            else if showConfirm {
                ConfirmSignup(email: pendingEmail, onConfirmed: {
                    showLogin = true
                })
            }
            else {
                Signup(onSignupSuccess: { email in
                    pendingEmail = email
                    showConfirm = true
                }, onGoToLogin: {
                    showLogin = true
                })
            }

        }
        .task {
            await vm.checkSession()
            await authState.resolveTier()   // runs once on launch alongside your existing check
        }
    }
}

#Preview {
    Main()
}
