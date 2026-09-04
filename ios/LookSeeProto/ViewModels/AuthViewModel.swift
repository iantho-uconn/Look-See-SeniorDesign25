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
    
    @Published var activePlanCents: Int = 0
    @Published var activePlanYears: Int = 0
    
    // Personal User Identity
    @Published var username: String = ""
    @Published var profileImageUrl: String = ""
    
    // Memory variable to carry the username from Signup to Login
    @Published var pendingUsernameToSave: String = ""

    var currentTier: UserTier {
        guard isSignedIn else { return .guest }
        return hasActiveSubscription ? .business : .authenticated
    }
    
    // Website and Address properties
    @Published var storeName: String = ""
    @Published var phoneNumber: String = ""
    @Published var storeWebsite: String = ""
    @Published var storeAddress: String = ""
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
            _ = await signInAndLoad(username: username, password: password)
        }
    }

    @discardableResult
    func signInAndLoad(username: String, password: String) async -> Bool {
        let normalizedEmail = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        do {
            let result = try await AuthService.shared.signIn(username: normalizedEmail, password: password)
            guard result.isSignedIn else {
                switch result.nextStep {
                case .confirmSignInWithNewPassword:
                    requiresNewPassword = true
                    errorMessage = String(localized: "Please enter a new permanent password.")
                case .confirmSignUp:
                    errorMessage = String(localized: "Account not verified. Please check your email for a confirmation code.")
                case .resetPassword:
                    errorMessage = String(localized: "Password reset required.")
                default:
                    errorMessage = String(localized: "Additional verification required.")
                }
                isSignedIn = false
                return false
            }

            isSignedIn = true
            requiresNewPassword = false
            errorMessage = ""
            userEmail = normalizedEmail

            if let user = try? await Amplify.Auth.getCurrentUser() {
                userId = user.userId
            }

            await initDatabaseRow(emailToSave: normalizedEmail)

            if !pendingUsernameToSave.isEmpty {
                let usernameToSave = pendingUsernameToSave
                let result = await updateUserIdentity(
                    newUsername: usernameToSave,
                    emailToSave: normalizedEmail
                )
                if result.success {
                    pendingUsernameToSave = ""
                }
            }

            await fetchUserDetails()
            await fetchUserUsageStats()
            return true
        } catch let error as AuthError {
            errorMessage = friendlyMessage(for: error)
            isSignedIn = false
            return false
        } catch {
            errorMessage = String(localized: "Something went wrong. Please try again.")
            isSignedIn = false
            return false
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
                    
                    if let user = try? await Amplify.Auth.getCurrentUser() {
                        self.userId = user.userId
                    }
                    
                    await initDatabaseRow(emailToSave: self.userEmail)
                    await fetchUserDetails()
                    await fetchUserUsageStats()
                } else {
                    errorMessage = String(localized: "Additional steps required to sign in.")
                }
            } catch let error as AuthError {
                errorMessage = friendlyMessage(for: error)
            } catch {
                errorMessage = String(localized: "Failed to update password. Please try again.")
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
                self.activePlanCents = 0
                self.activePlanYears = 0
                self.username = ""
                self.profileImageUrl = ""
                self.storeName = ""
                self.phoneNumber = ""
                self.storeWebsite = ""
                self.storeAddress = ""
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
                self.username = ""
                self.profileImageUrl = ""
                self.hasActiveSubscription = false
                self.tokenBalance = 0
                self.activePlanCents = 0
                self.activePlanYears = 0
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

    private func authorizedJSONRequest(url: URL) async -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let idToken = await fetchIdToken()
        if !idToken.isEmpty {
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        }

        return request
    }

    func fetchUserUsageStats() async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats") else { return }
        
        var request = await authorizedJSONRequest(url: url)
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
                        
                        let fetchedPlanCents = json["activePlanCents"] as? Int ?? 0
                        let fetchedPlanYears = json["activePlanYears"] as? Int ?? 0
                        
                        self.tokenBalance = max(self.tokenBalance, fetchedBalance)
                        self.activeLandmarksCount = fetchedLandmarks
                        
                        let isSubscribedOnBackend = fetchedSub || fetchedTier == "business" || !fetchedStripeId.isEmpty
                        self.hasActiveSubscription = isSubscribedOnBackend
                        
                        self.activePlanCents = fetchedPlanCents
                        self.activePlanYears = fetchedPlanYears
                        
                        if !fetchedStripeId.isEmpty {
                            self.stripeSubscriptionId = fetchedStripeId
                        }
                        
                        if let fetchedUsername = json["username"] as? String, !fetchedUsername.isEmpty { self.username = fetchedUsername }
                        if let fetchedProfileImg = json["profileImageUrl"] as? String, !fetchedProfileImg.isEmpty { self.profileImageUrl = fetchedProfileImg }
                        
                        if let fetchedStore = json["storeName"] as? String, !fetchedStore.isEmpty { self.storeName = fetchedStore }
                        if let fetchedPhone = json["phoneNumber"] as? String, !fetchedPhone.isEmpty { self.phoneNumber = fetchedPhone }
                        
                        if let fetchedWebsite = json["storeWebsite"] as? String, !fetchedWebsite.isEmpty { self.storeWebsite = fetchedWebsite }
                        if let fetchedAddress = json["storeAddress"] as? String, !fetchedAddress.isEmpty { self.storeAddress = fetchedAddress }
                        
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
        
        var request = await authorizedJSONRequest(url: url)
        
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
                    self.activePlanCents = 0
                    self.activePlanYears = 0
                    UserDefaults.standard.set(false, forKey: "isFreeTrial_\(self.userEmail)")
                }
                return true
            }
        } catch {
            print("❌ Failed to cancel subscription: \(error)")
        }
        return false
    }

    func updateUserIdentity(newUsername: String, emailToSave: String, profileBase64: String? = nil) async -> (success: Bool, error: String?) {
        guard !userId.isEmpty else { return (false, "User not found") }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return (false, "Invalid URL") }
        
        var request = await authorizedJSONRequest(url: url)
        
        var body: [String: Any] = [
            "purchaseType": "update_user_identity",
            "userId": userId,
            "userEmail": emailToSave, // 🚀 Forced parameter injection
            "username": newUsername,
            "currentUsername": self.username,
            "profileImageUrl": self.profileImageUrl
        ]
        
        if let base64 = profileBase64 {
            body["profileBase64"] = base64
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        await MainActor.run {
                            if let updatedUsername = json["username"] as? String, !updatedUsername.isEmpty {
                                self.username = updatedUsername
                            }
                            if let newImage = json["profileImageUrl"] as? String, !newImage.isEmpty {
                                self.profileImageUrl = newImage
                            }
                        }
                    }
                    return (true, nil)
                } else {
                    let err = String(data: data, encoding: .utf8) ?? "Unknown Error"
                    if err.contains("ERR_USERNAME_TAKEN") {
                        return (false, "That username is already taken.")
                    }
                    return (false, "Server Error: \(err)")
                }
            }
        } catch {
            return (false, error.localizedDescription)
        }
        return (false, "Network error")
    }

    func updateBusinessProfile(storeName: String, phoneNumber: String, storeWebsite: String, storeAddress: String, storeBio: String, storeLogoUrl: String, storeLogoBase64: String? = nil) async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return false }
        
        var request = await authorizedJSONRequest(url: url)
        
        var body: [String: Any] = [
            "purchaseType": "update_profile",
            "userId": userId,
            "storeName": storeName,
            "phoneNumber": phoneNumber,
            "storeWebsite": storeWebsite,
            "storeAddress": storeAddress,
            "storeBio": storeBio,
            "storeLogoUrl": storeLogoUrl
        ]
        
        if let base64 = storeLogoBase64 {
            body["storeLogoBase64"] = base64
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let newLogoUrl = json["logoUrl"] as? String {
                        await MainActor.run {
                            self.storeName = storeName
                            self.phoneNumber = phoneNumber
                            self.storeWebsite = storeWebsite
                            self.storeAddress = storeAddress
                            self.storeBio = storeBio
                            self.storeLogoUrl = newLogoUrl
                        }
                    } else {
                        await MainActor.run {
                            self.storeName = storeName
                            self.phoneNumber = phoneNumber
                            self.storeWebsite = storeWebsite
                            self.storeAddress = storeAddress
                            self.storeBio = storeBio
                            self.storeLogoUrl = storeLogoUrl
                        }
                    }
                    return true
                } else {
                    if let errorString = String(data: data, encoding: .utf8) {
                        print("❌ Backend Rejected Upload (\(httpResponse.statusCode)): \(errorString)")
                    }
                }
            }
        } catch {
            print("❌ Failed to update business profile: \(error)")
        }
        return false
    }
    
    // 🚀 NEW: Signature forces an email string to be passed in
    func initDatabaseRow(emailToSave: String) async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return }
        
        var request = await authorizedJSONRequest(url: url)
        
        let body: [String: Any] = [
            "purchaseType": "init_user",
            "userId": userId,
            "userEmail": emailToSave // 🚀 Forced parameter injection
        ]
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        _ = try? await URLSession.shared.data(for: request)
    }

    private func friendlyMessage(for error: AuthError) -> String {
        switch error {
        case .notAuthorized: return String(localized: "Incorrect email or password. Please try again.")
        case .service(_, _, let underlyingError):
            let description = underlyingError.map { "\($0)" } ?? ""
            if description.contains("UserNotFound") { return String(localized: "No account found with that email.") }
            if description.contains("UserNotConfirmed") { return String(localized: "Please verify your email.") }
            return String(localized: "Something went wrong. Please try again.")
        default: return String(localized: "Something went wrong. Please try again.")
        }
    }
}
