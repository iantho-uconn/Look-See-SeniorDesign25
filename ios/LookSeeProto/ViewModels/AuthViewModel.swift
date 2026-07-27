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
    
    // TOKEN / SUB / PROFILE TRACKERS
    @Published var tokenBalance: Int = 0
    @Published var activeLandmarksCount: Int = 0
    @Published var hasActiveSubscription: Bool = false
    @Published var stripeSubscriptionId: String = ""
    
    @Published var storeName: String = ""
    @Published var phoneNumber: String = ""
    @Published var storeBio: String = ""
    @Published var storeLogoUrl: String = ""
    
    func checkSession() async {
        do {
            let session = try await Amplify.Auth.fetchAuthSession()
            isSignedIn = session.isSignedIn
            
            if isSignedIn {
                await fetchUserDetails()
                if isSignedIn {
                    await fetchUserUsageStats()
                }
            }
        } catch {
            isSignedIn = false
        }
    }
    
    func signIn(username: String, password: String) {
        Task {
            do {
                let result = try await AuthService.shared.signIn(username: username, password: password)
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
            
            await MainActor.run {
                self.isSignedIn = false
                self.requiresNewPassword = false
                self.tokenBalance = 0
                self.activeLandmarksCount = 0
                self.hasActiveSubscription = false
                self.stripeSubscriptionId = ""
                self.storeName = ""
                self.phoneNumber = ""
                self.storeBio = ""
                self.storeLogoUrl = ""
                self.userId = ""
                self.userEmail = ""
            }
        }
    }

    func fetchUserDetails() async {
        do {
            if let user = try? await Amplify.Auth.getCurrentUser() {
                self.userId = user.userId
            }
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            if let emailAttr = attributes.first(where: { $0.key == .email }) {
                self.userEmail = emailAttr.value
            }
        } catch {
            print("❌ Failed to fetch user details: \(error)")
            let errString = "\(error)"
            if errString.contains("userNotFound") || errString.contains("NotAuthorizedException") || errString.contains("deleted") {
                _ = await Amplify.Auth.signOut()
                self.isSignedIn = false
                self.userId = ""
                self.userEmail = ""
                self.hasActiveSubscription = false
                self.tokenBalance = 0
                self.stripeSubscriptionId = ""
            }
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
                        let fetchedBalance = json["tokenBalance"] as? Int ?? 0
                        let fetchedLandmarks = json["activeLandmarksCount"] as? Int ?? 0
                        let fetchedSub = json["hasActiveSubscription"] as? Bool ?? false
                        let fetchedTier = json["tier"] as? String ?? ""
                        let fetchedStripeId = json["stripeSubscriptionId"] as? String ?? ""
                        
                        self.tokenBalance = max(self.tokenBalance, fetchedBalance)
                        self.activeLandmarksCount = fetchedLandmarks
                        
                        let isSubscribedOnBackend = fetchedSub || fetchedTier == "business" || !fetchedStripeId.isEmpty
                        self.hasActiveSubscription = self.hasActiveSubscription || isSubscribedOnBackend
                        
                        if !fetchedStripeId.isEmpty {
                            self.stripeSubscriptionId = fetchedStripeId
                        }
                        
                        if let fetchedStore = json["storeName"] as? String, !fetchedStore.isEmpty { self.storeName = fetchedStore }
                        if let fetchedPhone = json["phoneNumber"] as? String, !fetchedPhone.isEmpty { self.phoneNumber = fetchedPhone }
                        if let fetchedBio = json["storeBio"] as? String, !fetchedBio.isEmpty { self.storeBio = fetchedBio }
                        if let fetchedLogo = json["storeLogoUrl"] as? String, !fetchedLogo.isEmpty { self.storeLogoUrl = fetchedLogo }
                    }
                }
            }
        } catch {
            print("❌ Failed to fetch stats: \(error.localizedDescription)")
        }
    }

    func cancelSubscription() async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return false }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = [
            "purchaseType": "cancel_subscription",
            "userId": userId,
            "subscriptionId": stripeSubscriptionId
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                await MainActor.run {
                    self.hasActiveSubscription = false
                    self.stripeSubscriptionId = ""
                    UserDefaults.standard.set(false, forKey: "isFreeTrial_\(self.userEmail)")
                }
                return true
            }
        } catch {
            print("❌ Failed to cancel subscription: \(error)")
        }
        return false
    }

    func updateBusinessProfile(storeName: String, phoneNumber: String, storeBio: String, storeLogoUrl: String, storeLogoBase64: String? = nil) async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return false }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        var body: [String: Any] = [
            "purchaseType": "update_profile",
            "userId": userId,
            "storeName": storeName,
            "phoneNumber": phoneNumber,
            "storeBio": storeBio,
            "storeLogoUrl": storeLogoUrl
        ]
        
        if let base64 = storeLogoBase64 {
            body["storeLogoBase64"] = base64
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let newLogoUrl = json["logoUrl"] as? String {
                    await MainActor.run {
                        self.storeName = storeName
                        self.phoneNumber = phoneNumber
                        self.storeBio = storeBio
                        self.storeLogoUrl = newLogoUrl
                    }
                } else {
                    await MainActor.run {
                        self.storeName = storeName
                        self.phoneNumber = phoneNumber
                        self.storeBio = storeBio
                        self.storeLogoUrl = storeLogoUrl
                    }
                }
                return true
            }
        } catch {
            print("❌ Failed to update business profile: \(error)")
        }
        return false
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
