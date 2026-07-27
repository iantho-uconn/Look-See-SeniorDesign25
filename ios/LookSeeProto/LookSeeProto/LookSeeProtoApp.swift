//
//  LookSeeProtoApp.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 10/14/25.
//  Modified by Sheenan Ahsan on 02/18/26.

import SwiftUI
import Amplify
import AWSCognitoAuthPlugin
import AWSS3StoragePlugin 

@main
struct LookSeeProtoApp: App {
    @StateObject private var authState = AuthState()
    @StateObject private var authViewModel = AuthViewModel()
    
    init() {
        configureAmplify()
    }
    
    func configureAmplify() {
        do {
            try Amplify.add(plugin: AWSCognitoAuthPlugin())
            try Amplify.add(plugin: AWSS3StoragePlugin()) // 🚀 THE FIX: Initializes Storage
            try Amplify.configure()
            print("✅ Amplify configured")
        } catch {
            print("❌ Failed to configure Amplify:", error)
        }
    }
    
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authState)
                .environmentObject(authViewModel)
        }
    }
}
