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
import Sentry
import GoogleMobileAds
import UserMessagingPlatform

@main
struct LookSeeProtoApp: App {
    @StateObject private var authState = AuthState()
    @StateObject private var authViewModel = AuthViewModel()
    @StateObject private var adConsentManager = AdConsentManager()
    
    @AppStorage("selectedLanguage") private var selectedLanguage: String = "en"
    
    init() {
        configureAmplify()
        configureSentry()
    }
    
    func configureAmplify() {
        do {
            try Amplify.add(plugin: AWSCognitoAuthPlugin())
            try Amplify.add(plugin: AWSS3StoragePlugin())
            try Amplify.configure()
            print("✅ Amplify configured")
        } catch {
            print("❌ Failed to configure Amplify:", error)
        }
    }
    
    func configureSentry() {
        SentrySDK.start { options in
            // 🚀 THE FIX: Dynamically read the DSN from Info.plist
            if let sentryDSNKey = Bundle.main.object(forInfoDictionaryKey: "SENTRY_DSN") as? String, !sentryDSNKey.isEmpty {
                // Re-attach the https:// prefix that we stripped from the xcconfig file
                options.dsn = "https://" + sentryDSNKey
            } else {
                print("⚠️ WARNING: SENTRY_DSN not found in Info.plist")
            }
            
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
