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
    @State private var showConfirm = false
    @State private var showLogin = false
    @State private var pendingEmail = ""
    @State private var isSignedIn = false

    var body: some View {
        NavigationStack {
            if vm.isSignedIn {
                Buttons()
                    .environmentObject(vm)
            }
            else if showLogin {
                Login(vm: vm, onSignedIn: {
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
        }
    }
}

#Preview {
    Main()
}
