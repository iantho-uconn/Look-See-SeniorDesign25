//
//  AuthService.swift
//  LookSeeProto
//
//  Created by Sheenan Ahsan on 2/25/26.
//
import Amplify

class AuthService {

   static let shared = AuthService()

   private init() {}
   

   // SIGN UP
   func signUp(username: String, password: String, email: String) async throws -> AuthSignUpResult {
       let options = AuthSignUpRequest.Options(
           userAttributes: [
               AuthUserAttribute(.email, value: email)
           ]
       )
       return try await Amplify.Auth.signUp(
           username: email,
           password: password,
           options: options
       )
   }

   // SIGN IN
   func signIn(username: String, password: String) async throws -> AuthSignInResult {
       return try await Amplify.Auth.signIn(
           username: username,
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
}
