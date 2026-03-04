//
//  LookSeeProtoApp.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 10/14/25.
//  Modified by Sheenan Ahsan on 02/18/26.

import SwiftUI
import Amplify
import AWSCognitoAuthPlugin

@main
struct LookSeeProtoApp: App {
    
    init() {
        configureAmplify()
    }
    
    
    func configureAmplify() {
        do {
            try Amplify.add(plugin: AWSCognitoAuthPlugin())
            try Amplify.configure()
            print("✅ Amplify configured")
        } catch {
            print("❌ Failed to configure Amplify:", error)
        }
    }
    var body: some Scene {
        WindowGroup {
            Main()
        }
    }
}
//struct LookSeeProtoApp: App {
//    var body: some Scene {
//        WindowGroup {
//            ContentView()
//        }
//    }
//}
