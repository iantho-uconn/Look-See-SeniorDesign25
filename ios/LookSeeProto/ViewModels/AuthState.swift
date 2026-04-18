//
//  AuthState.swift
//  LookSeeProto
//

import SwiftUI
import Combine
import Amplify
import AWSCognitoAuthPlugin

enum UserTier {
    case guest, authenticated, business
}

@MainActor
class AuthState: ObservableObject {
    @Published var tier: UserTier = .guest
    @Published var isReady: Bool = false

    func resolveTier() async {
        guard let session = try? await Amplify.Auth.fetchAuthSession() else {
            tier = .guest
            isReady = true
            return
        }

        guard session.isSignedIn else {
            tier = .guest
            isReady = true
            return
        }

        // Use fetchUserAttributes instead of casting the session
        do {
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            if let groupAttr = attributes.first(where: { $0.key.rawValue == "custom:group" }) {
                if groupAttr.value == "business-users" {
                    tier = .business
                } else {
                    tier = .authenticated
                }
            } else {
                tier = .authenticated
            }
        } catch {
            print("❌ Failed to fetch user attributes: \(error)")
            tier = .authenticated
        }

        isReady = true
    }

    func signOut() async {
        await Amplify.Auth.signOut()
        tier = .guest
        isReady = true
    }
}
