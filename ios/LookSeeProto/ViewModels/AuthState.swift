//
//  AuthState.swift
//  LookSeeProto
//
import SwiftUI
import Combine
import Foundation
import Amplify
import AWSCognitoAuthPlugin
import AWSPluginsCore

enum UserTier: Equatable {
    case guest, authenticated, business
}

@MainActor
class AuthState: ObservableObject {
    @Published var tier: UserTier = .guest
    @Published var isReady: Bool = false
    @Published var didSignOut: Bool = false

    func resolveTier() async {
        isReady = false

        do {
            let session = try await Amplify.Auth.fetchAuthSession(options: .forceRefresh())
            guard session.isSignedIn else {
                tier = .guest
                isReady = true
                return
            }

            guard let tokenProvider = session as? AuthCognitoTokensProvider else {
                tier = .authenticated
                isReady = true
                return
            }

            let tokens = try tokenProvider.getCognitoTokens().get()
            let groups = Self.cognitoGroups(from: tokens.idToken)

            if groups.contains("business-users") || groups.contains("admins") {
                tier = .business
            } else {
                tier = .authenticated
            }

            didSignOut = false
        } catch {
            print("❌ resolveTier failed: \(error)")
            let session = try? await Amplify.Auth.fetchAuthSession()
            tier = session?.isSignedIn == true ? .authenticated : .guest
        }
        isReady = true
    }

    private static func cognitoGroups(from idToken: String) -> Set<String> {
        let tokenParts = idToken.split(separator: ".")
        guard tokenParts.count > 1 else { return [] }

        var payload = String(tokenParts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        let padding = (4 - payload.count % 4) % 4
        payload += String(repeating: "=", count: padding)

        guard let data = Data(base64Encoded: payload),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return []
        }

        if let groups = json["cognito:groups"] as? [String] {
            return Set(groups)
        }

        if let group = json["cognito:groups"] as? String {
            return [group]
        }

        return []
    }

    func signOut() async {
       _ = await Amplify.Auth.signOut()
        tier = .guest
        isReady = true
        didSignOut = true
    }
}
