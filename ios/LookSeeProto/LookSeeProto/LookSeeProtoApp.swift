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
import Sentry // 🚀 ADDED: Sentry SDK
import GoogleMobileAds
import UserMessagingPlatform

@main
struct LookSeeProtoApp: App {
    @StateObject private var authState = AuthState()
    @StateObject private var authViewModel = AuthViewModel()
    @StateObject private var adConsentManager = AdConsentManager()
    
    // 🚀 THE FIX: In-app language selector storage
    @AppStorage("selectedLanguage") private var selectedLanguage: String = "en"
    
    init() {
        configureAmplify()
        configureSentry() // 🚀 ADDED: Boot up Sentry
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
    
    // 🚀 ADDED: Sentry Configuration
    func configureSentry() {
        SentrySDK.start { options in
            // Your Live Sentry DSN Key
            options.dsn = "https://624e9e0d4b4c1bb5a0de8ceeebb7dcc9@o4512107452301312.ingest.us.sentry.io/4512107464097792"
            
            // Enable performance monitoring
            options.tracesSampleRate = 1.0
            
            // Enable Session Replay (Captures screen video leading up to a bug)
            options.sessionReplay = SentryReplayOptions(sessionSampleRate: 1.0, onErrorSampleRate: 1.0)
            
            // 🛡️ SECURITY: Automatically masks ALL text and images by default.
        }
        print("✅ Sentry configured")
    }
    
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authState)
                .environmentObject(authViewModel)
                .environmentObject(adConsentManager)
                .environment(
                    \.locale,
                    Locale(identifier: selectedLanguage)
                )
                .task {
                    await adConsentManager.start()
                }
        }
    }
}
