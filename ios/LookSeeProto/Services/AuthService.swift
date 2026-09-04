//
//  AuthService.swift
//  LookSeeProto
//
//  Created by Sheenan Ahsan on 2/25/26.
//
import Foundation
import Amplify
import AWSCognitoAuthPlugin
import AWSPluginsCore

class AuthService {

   static let shared = AuthService()

   private init() {}
   

   // SIGN UP
    func signUp(
        username _: String,
        password: String,
        email: String,
        group _: String = "authenticated-users"
    ) async throws -> AuthSignUpResult {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        let options = AuthSignUpRequest.Options(
            userAttributes: [
                AuthUserAttribute(.email, value: normalizedEmail),
                AuthUserAttribute(
                    AuthUserAttributeKey(rawValue: "custom:group"),
                    value: "authenticated-users"
                )
            ]
        )
        return try await Amplify.Auth.signUp(
            username: normalizedEmail,
            password: password,
            options: options
        )
    }

   // SIGN IN
   func signIn(username: String, password: String) async throws -> AuthSignInResult {
       let normalizedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
       return try await Amplify.Auth.signIn(
           username: normalizedUsername,
           password: password
       )
   }

   // CONFIRM CODE
   func confirm(username: String, code: String) async throws -> AuthSignUpResult {
       return try await Amplify.Auth.confirmSignUp(
           for: username,
           confirmationCode: code
       )
   }

   // SIGN OUT
    func signOut() async {
        _ = await Amplify.Auth.signOut()
    }
    
    func fetchIdToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard let cognitoTokenProvider = session as? AuthCognitoTokensProvider else {
            throw AuthError.service(
                "Could not read Cognito session",
                "Session did not provide Cognito tokens",
                nil
            )
        }

        let tokens = try cognitoTokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    // FETCH VERIFIED EMAIL — straight from Cognito's user attributes,
    // not whatever the app happens to have cached locally
    func fetchVerifiedEmail() async throws -> String? {
        let attributes = try await Amplify.Auth.fetchUserAttributes()
        return attributes.first(where: { $0.key == .email })?.value
    }

}
