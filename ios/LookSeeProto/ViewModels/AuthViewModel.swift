import Foundation
import Combine
import Amplify

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
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
    func signOut() {
        Task {
            await AuthService.shared.signOut()
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
}
