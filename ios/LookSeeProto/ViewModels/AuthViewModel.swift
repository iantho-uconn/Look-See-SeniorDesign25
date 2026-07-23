import Foundation
import Combine
import Amplify
import AWSPluginsCore

@MainActor
class AuthViewModel: ObservableObject {

    @Published var isSignedIn = false
    @Published var errorMessage = ""
    @Published var userEmail = ""
    
    // NEW: Tracks when a user needs to set a permanent password
    @Published var requiresNewPassword = false
    
    func checkSession() async {
        do {
            let session = try await Amplify.Auth.fetchAuthSession()
            isSignedIn = session.isSignedIn
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
                } else {
                    switch result.nextStep {
                    case .confirmSignInWithNewPassword:
                        // TRIGGER THE NEW PASSWORD UI
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

    // MARK: - NEW: Confirm New Password Function
    func confirmNewPassword(newPassword: String) {
        Task {
            do {
                // Send the new password to AWS
                let result = try await Amplify.Auth.confirmSignIn(challengeResponse: newPassword)
                
                if result.isSignedIn {
                    isSignedIn = true
                    requiresNewPassword = false
                    errorMessage = ""
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
    
    // Updated: accepts authState so tier resets cleanly on sign out

    func signOut(authState: AuthState) {
        Task {
            await AuthService.shared.signOut()
            await authState.signOut()
            isSignedIn = false
            requiresNewPassword = false
        }
    }

    func fetchUserEmail() async {
        do {
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            if let emailAttr = attributes.first(where: { $0.key == .email }) {
                userEmail = emailAttr.value
            }
        } catch {
            print("❌ Failed to fetch user email: \(error)")
        }
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

    private func friendlyMessage(for error: AuthError) -> String {
        // ... (Keep your exact same friendlyMessage switch cases here)
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
/*
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

 */  
