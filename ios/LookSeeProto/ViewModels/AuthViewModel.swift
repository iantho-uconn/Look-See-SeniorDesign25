//
//  AuthViewModel.swift
//  LookSeeProto
//

import Foundation
import Combine
import Amplify
import AWSPluginsCore

@MainActor
class AuthViewModel: ObservableObject {

    @Published var isSignedIn = false
    @Published var errorMessage = ""
    @Published var userEmail = ""
    @Published var userId = ""
    
    @Published var requiresNewPassword = false
    
    @Published var tokenBalance: Int = 0
    @Published var activeLandmarksCount: Int = 0
    @Published var maxLandmarksCapacity: Int = 0
    @Published var stripeSubscriptionId: String = ""
    
    func checkSession() async {
        do {
            let session = try await Amplify.Auth.fetchAuthSession()
            isSignedIn = session.isSignedIn
            
            if isSignedIn {
                await fetchUserDetails()
                await fetchUserUsageStats()
            }
        } catch {
            isSignedIn = false
        }
    }
    
    func signIn(username: String, password: String) {
        Task {
            do {
                let result = try await AuthService.shared.signIn(
                    username: username,
                    password: password
                )
                
                if result.isSignedIn {
                    isSignedIn = true
                    requiresNewPassword = false
                    errorMessage = ""
                    await fetchUserDetails()
                    await fetchUserUsageStats()
                } else {
                    switch result.nextStep {
                    case .confirmSignInWithNewPassword:
                        requiresNewPassword = true
                        errorMessage = "Please enter a new permanent password."
                    case .confirmSignUp:
                        errorMessage = "Account not verified. Please check your email for a confirmation code."
                    case .resetPassword:
                        errorMessage = "Password reset required."
                    default:
                        errorMessage = "Additional verification required."
                    }
                    isSignedIn = false
                }
            } catch let error as AuthError {
                errorMessage = friendlyMessage(for: error)
                isSignedIn = false
            } catch {
                errorMessage = "Something went wrong. Please try again."
                isSignedIn = false
            }
        }
    }

    func confirmNewPassword(newPassword: String) {
        Task {
            do {
                let result = try await Amplify.Auth.confirmSignIn(challengeResponse: newPassword)
                
                if result.isSignedIn {
                    isSignedIn = true
                    requiresNewPassword = false
                    errorMessage = ""
                    await fetchUserDetails()
                    await fetchUserUsageStats()
                } else {
                    errorMessage = "Additional steps required to sign in."
                }
            } catch let error as AuthError {
                errorMessage = friendlyMessage(for: error)
            } catch {
                errorMessage = "Failed to update password. Please try again."
            }
        }
    }
    
    func signOut(authState: AuthState) {
        Task {
            await AuthService.shared.signOut()
            await authState.signOut()
            isSignedIn = false
            requiresNewPassword = false
            
            tokenBalance = 0
            activeLandmarksCount = 0
            maxLandmarksCapacity = 0
            stripeSubscriptionId = ""
            userId = ""
            userEmail = ""
        }
    }

    // 🚀 FIXED: Directly fetches userId from the user session, and email from attributes
    func fetchUserDetails() async {
        do {
            // 1. Get the immutable Cognito User ID directly
            if let user = try? await Amplify.Auth.getCurrentUser() {
                self.userId = user.userId
            }
            
            // 2. Fetch the email from the attributes array
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            if let emailAttr = attributes.first(where: { $0.key == .email }) {
                self.userEmail = emailAttr.value
            }
        } catch {
            print("❌ Failed to fetch user details: \(error)")
        }
    }
    
    func fetchUserEmail() async {
        await fetchUserDetails()
    }

    func fetchIdToken() async -> String {
        do {
            let session = try await Amplify.Auth.fetchAuthSession()
            if let tokenProvider = session as? AuthCognitoTokensProvider {
                let tokens = try tokenProvider.getCognitoTokens().get()
                return tokens.idToken
            }
        } catch {
            print("❌ Failed to fetch session token: \(error)")
        }
        return ""
    }
    
    func fetchUserUsageStats() async {
        guard !userId.isEmpty else { return }
        
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats") else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: String] = ["userId": userId]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    await MainActor.run {
                        self.tokenBalance = json["tokenBalance"] as? Int ?? 0
                        self.activeLandmarksCount = json["activeLandmarksCount"] as? Int ?? 0
                        self.maxLandmarksCapacity = json["maxLandmarksCapacity"] as? Int ?? 5
                        self.stripeSubscriptionId = json["stripeSubscriptionId"] as? String ?? ""
                    }
                }
            }
        } catch {
            print("❌ Failed to fetch stats: \(error.localizedDescription)")
        }
    }

    private func friendlyMessage(for error: AuthError) -> String {
        switch error {
        case .notAuthorized: return "Incorrect email or password. Please try again."
        case .service(_, _, let underlyingError):
            let description = underlyingError.map { "\($0)" } ?? ""
            if description.contains("UserNotFound") { return "No account found with that email." }
            if description.contains("UserNotConfirmed") { return "Please verify your email." }
            return "Something went wrong. Please try again."
        default: return "Something went wrong. Please try again."
        }
    }
}
<<<<<<< HEAD
=======

func printCognitoTokens() async {
    do {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard session.isSignedIn else {
            print("❌ No Cognito user is currently signed in.")
            return
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            print("❌ Cognito token provider was unavailable.")
            return
        }

        let tokens = try tokenProvider.getCognitoTokens().get()

        print("""
        
        ==============================
        COGNITO ACCESS TOKEN
        ==============================
        \(tokens.accessToken)

        ==============================
        COGNITO ID TOKEN
        ==============================
        \(tokens.idToken)
        ==============================
        
        """)

    } catch {
        print("❌ Could not retrieve Cognito tokens: \(error)")
    }
}

 
>>>>>>> origin/feature-URLWorkshop
