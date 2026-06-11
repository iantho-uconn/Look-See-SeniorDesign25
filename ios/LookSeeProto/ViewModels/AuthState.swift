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
    @Published var didSignOut: Bool = false

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
        do {
            _ = try await Amplify.Auth.fetchAuthSession(options: .forceRefresh())
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            print("🔍 All attributes: \(attributes)")
            if let groupAttr = attributes.first(where: { $0.key.rawValue == "custom:group" }) {
                print("🔍 Found group attribute: \(groupAttr.value)")
                tier = groupAttr.value == "business-users" ? .business : .authenticated
            } else {
                print("⚠️ No group attribute found")
                tier = .authenticated
            }
        } catch {
            print("❌ resolveTier failed: \(error)")
            tier = .authenticated
        }
        isReady = true
    }

    func signOut() async {
       _ = await Amplify.Auth.signOut()
        tier = .guest
        isReady = true
        didSignOut = true
    }
}
