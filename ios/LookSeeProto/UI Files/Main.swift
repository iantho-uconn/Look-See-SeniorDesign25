//
//  Main.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//
import SwiftUI

struct Main: View {
    @EnvironmentObject private var vm: AuthViewModel
    @EnvironmentObject private var authState: AuthState

    var body: some View {
        Buttons()
            .task {
                await vm.checkSession()
                await authState.resolveTier()
            }
    }
}

#Preview {
    Main()
        .environmentObject(AuthViewModel())
        .environmentObject(AuthState())
}
