import Foundation
import Combine
import Amplify
import AWSPluginsCore // <-- This is the core AWS module required to unlock the token!

@MainActor
class AuthViewModel: ObservableObject {

    @Published var isSignedIn = false
    @Published var errorMessage = ""
    @Published var userEmail = ""
    
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
                isSignedIn = result.isSignedIn
                errorMessage = ""
            } catch let error as AuthError {
                errorMessage = friendlyMessage(for: error)
            } catch {
                errorMessage = "Something went wrong. Please try again."
            }
        }
    }
    
    // Updated: accepts authState so tier resets cleanly on sign out
    func signOut(authState: AuthState) {
        Task {
            await AuthService.shared.signOut()
            await authState.signOut()   // resets tier to .guest
            isSignedIn = false
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

    // MARK: - NEW TOKEN FETCH METHOD
    func fetchIdToken() async -> String {
        do {
            let session = try await Amplify.Auth.fetchAuthSession()
            
            // AWSPluginsCore provides this specific protocol to expose the tokens
            if let tokenProvider = session as? AuthCognitoTokensProvider {
                let tokens = try tokenProvider.getCognitoTokens().get()
                return tokens.idToken
            }
        } catch {
            print("❌ Failed to fetch session token: \(error)")
        }
        return ""
    }

    // MARK: - Private

    private func friendlyMessage(for error: AuthError) -> String {
        switch error {
        case .notAuthorized:
            return "Incorrect email or password. Please try again."

        case .service(_, _, let underlyingError):
            let description = underlyingError.map { "\($0)" } ?? ""
            if description.contains("UserNotFoundException") || description.contains("UserNotFound") {
                return "No account found with that email. Please check your email or sign up."
            }
            if description.contains("UserNotConfirmedException") {
                return "Please verify your email before signing in. Check your inbox for a confirmation link."
            }
            if description.contains("PasswordResetRequiredException") {
                return "Your password needs to be reset. Please use the forgot password option."
            }
            if description.contains("TooManyRequestsException") || description.contains("LimitExceededException") {
                return "Too many attempts. Please wait a moment and try again."
            }
            return "Something went wrong. Please try again."

        case .validation(_, let description, _, _):
            if description.lowercased().contains("username") || description.lowercased().contains("email") {
                return "Please enter a valid email address."
            }
            if description.lowercased().contains("password") {
                return "Please enter your password."
            }
            return "Please check your details and try again."

//        case .network:
//            return "Network error. Please check your internet connection and try again."

        case .invalidState:
            return "Something went wrong with your session. Please restart the app and try again."

        default:
            return "Something went wrong. Please try again."
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
