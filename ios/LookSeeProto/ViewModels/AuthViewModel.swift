//
//  AuthViewModel.swift
//  LookSeeProto
//

import Foundation
import Combine
import Amplify
import AWSPluginsCore
import CryptoKit

@MainActor
class AuthViewModel: ObservableObject {

    @Published var isSignedIn = false {
        didSet {
            if !isSignedIn { subscriptionStatusUserId = nil }
        }
    }
    @Published var errorMessage = ""
    @Published var userEmail = ""
    @Published var userId = "" {
        didSet {
            if userId != oldValue { subscriptionStatusUserId = nil }
        }
    }

    @Published private var subscriptionStatusUserId: String?
    @Published private var sessionChecksInProgress = 0
    @Published private var hasCheckedSession = false

    var isEligibleForBannerAds: Bool {
        guard hasCheckedSession, sessionChecksInProgress == 0 else { return false }
        guard isSignedIn else { return true }
        return !userId.isEmpty
            && subscriptionStatusUserId == userId
            && !hasActiveSubscription
    }
    
    @Published var requiresNewPassword = false
    
    // TOKEN / SUB / PROFILE TRACKERS
    @Published var tokenBalance: Int = 0
    @Published var activeLandmarksCount: Int = 0
    @Published var hasActiveSubscription: Bool = false
    @Published var stripeSubscriptionId: String = ""
    
    @Published var activePlanCents: Int = 0
    @Published var activePlanYears: Int = 0
    
    // History Trackers
    @Published var tier: String = ""
    @Published var scanHistory: [ScanHistoryItem] = []
    
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
        sessionChecksInProgress += 1
        defer {
            sessionChecksInProgress -= 1
            hasCheckedSession = true
        }
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
        subscriptionStatusUserId = nil
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
                self.tier = ""
                self.scanHistory = []
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
                self.tier = ""
                self.scanHistory = []
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

    private func authorizedJSONRequest(url: URL, body: Data? = nil) async -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let bodyData = body {
            request.httpBody = bodyData
        }
        
        let idToken = await fetchIdToken()
        await request.signWithAppAttest(idToken: idToken)

        return request
    }

    func fetchUserUsageStats() async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/LookSeeGetUserStats") else { return }
        
        let requestedUserId = userId
        let bodyPayload: [String: String] = ["userId": requestedUserId]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return }
        
        let request = await authorizedJSONRequest(url: url, body: bodyData)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    await MainActor.run {
                        guard self.isSignedIn, self.userId == requestedUserId else { return }
                        let fetchedBalance = json["tokenBalance"] as? Int ?? 0
                        let fetchedLandmarks = json["activeLandmarksCount"] as? Int ?? 0
                        let fetchedSub = json["hasActiveSubscription"] as? Bool
                        let fetchedTier = json["tier"] as? String ?? ""
                        let fetchedStripeId = json["stripeSubscriptionId"] as? String ?? ""
                        
                        let fetchedPlanCents = json["activePlanCents"] as? Int ?? 0
                        let fetchedPlanYears = json["activePlanYears"] as? Int ?? 0
                        
                        self.tokenBalance = max(self.tokenBalance, fetchedBalance)
                        self.activeLandmarksCount = fetchedLandmarks
                        self.tier = fetchedTier
                        
                        if let fetchedSub {
                            self.hasActiveSubscription = fetchedSub
                            self.subscriptionStatusUserId = requestedUserId
                        } else {
                            self.hasActiveSubscription = fetchedTier == "business" || !fetchedStripeId.isEmpty
                            self.subscriptionStatusUserId = nil
                        }
                        
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

    func logScanHistory(landmarkId: String, label: String, location: String, latitude: Double, longitude: Double, imageUrl: String) async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/history") else { return }

        let bodyPayload: [String: Any] = [
            "userId": userId,
            "landmarkId": landmarkId,
            "label": label,
            "location": location,
            "latitude": latitude,
            "longitude": longitude,
            "imageUrl": imageUrl
        ]
        
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return }
        let request = await authorizedJSONRequest(url: url, body: bodyData)
        _ = try? await URLSession.shared.data(for: request)
    }

    func fetchScanHistory() async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/history?userId=\(userId)") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                let items = try JSONDecoder().decode([ScanHistoryItem].self, from: data)
                await MainActor.run {
                    self.scanHistory = items
                }
            }
        } catch {
            print("❌ Failed to fetch scan history: \(error)")
        }
    }
    
    func deleteScanHistory(scannedAt: String) async {
        guard !userId.isEmpty else { return }
        
        await MainActor.run {
            self.scanHistory.removeAll { $0.scannedAt == scannedAt }
        }

        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/history") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "userId": userId,
            "scannedAt": scannedAt
        ]

        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        _ = try? await URLSession.shared.data(for: request)
    }

    func cancelSubscription() async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { return false }
        
        let bodyPayload: [String: Any] = [
            "purchaseType": "cancel_subscription",
            "userId": userId,
            "subscriptionId": stripeSubscriptionId
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return false }
        let request = await authorizedJSONRequest(url: url, body: bodyData)
        
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

    private func uploadImageToS3(imageData: Data, role: String) async throws -> String {
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { throw URLError(.badURL) }
        
        let initPayload: [String: Any] = [
            "purchaseType": "init_image_upload",
            "userId": userId,
            "role": role,
            "contentType": "image/jpeg"
        ]
        
        guard let initData = try? JSONSerialization.data(withJSONObject: initPayload) else { throw URLError(.cannotParseResponse) }
        let initRequest = await authorizedJSONRequest(url: url, body: initData)
        
        let (responseData, response) = try await URLSession.shared.data(for: initRequest)
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            print("❌ API Gateway Initialization Failed: \(String(data: responseData, encoding: .utf8) ?? "Unknown Error")")
            throw URLError(.badServerResponse)
        }
        
        guard let json = try JSONSerialization.jsonObject(with: responseData) as? [String: Any],
              let uploadUrlDict = json["uploadUrl"] as? [String: Any],
              let urlString = uploadUrlDict["url"] as? String,
              let fields = uploadUrlDict["fields"] as? [String: String],
              let finalImageUrl = json["finalImageUrl"] as? String else {
            throw URLError(.cannotParseResponse)
        }
        
        guard let s3Url = URL(string: urlString) else { throw URLError(.badURL) }
        var s3Request = URLRequest(url: s3Url)
        s3Request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        s3Request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        for (key, value) in fields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"profile.jpg\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
        body.append(imageData)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        let (s3Data, s3Response) = try await URLSession.shared.upload(for: s3Request, from: body)
        
        guard let s3HttpResp = s3Response as? HTTPURLResponse, (200...299).contains(s3HttpResp.statusCode) else {
            let errorXML = String(data: s3Data, encoding: .utf8) ?? "No XML provided"
            let statusCode = (s3Response as? HTTPURLResponse)?.statusCode ?? 0
            print("❌ S3 UPLOAD DIRECTLY FAILED! Status: \(statusCode)\nXML Error: \(errorXML)")
            throw URLError(.badServerResponse)
        }
        
        print("✅ S3 Image Upload Successful: \(finalImageUrl)")
        return finalImageUrl
    }

    // 🚀 FIXED: Deep logging added to catch the silent Lambda crash
    func updateUserIdentity(newUsername: String, emailToSave: String, profileBase64: String? = nil) async -> (success: Bool, error: String?) {
        guard !userId.isEmpty else { return (false, "User not found") }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { return (false, "Invalid URL") }
        
        var uploadedImageUrl: String? = nil
        
        if let base64 = profileBase64 {
            let cleanBase64 = base64.contains(",") ? String(base64.split(separator: ",")[1]) : base64
            
            if let imageData = Data(base64Encoded: cleanBase64, options: .ignoreUnknownCharacters) {
                do {
                    uploadedImageUrl = try await uploadImageToS3(imageData: imageData, role: "user_profile")
                } catch {
                    print("❌ S3 Method execution failed for profile image.")
                    return (false, "Failed to upload image securely.")
                }
            } else {
                print("❌ ERROR: Could not decode Base64 string into Image Data. The string may be corrupted.")
            }
        }
        
        let bodyPayload: [String: Any] = [
            "purchaseType": "update_user_identity",
            "userId": userId,
            "userEmail": emailToSave,
            "username": newUsername,
            "currentUsername": self.username,
            "profileImageUrl": uploadedImageUrl ?? self.profileImageUrl
        ]
        
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return (false, "Payload Error") }
        let request = await authorizedJSONRequest(url: url, body: bodyData)
        
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
                    print("✅ Lambda Successfully Saved Profile Data!")
                    return (true, nil)
                } else {
                    let err = String(data: data, encoding: .utf8) ?? "Unknown Error"
                    print("❌ LAMBDA REJECTED SAVE (\(httpResponse.statusCode)): \(err)") // <-- This will tell us the exact issue!
                    
                    if err.contains("ERR_USERNAME_TAKEN") {
                        return (false, "That username is already taken.")
                    }
                    return (false, "Server Error: \(err)")
                }
            }
        } catch {
            print("❌ NETWORK CRASH DURING LAMBDA SAVE: \(error.localizedDescription)")
            return (false, error.localizedDescription)
        }
        return (false, "Network error")
    }

    func forceTrainLandmark(landmarkId: String) async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/business/landmarks/\(landmarkId)") else { return false }
        
        let bodyPayload: [String: Any] = [
            "forceTrainEnabled": true
        ]
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return false }
        
        var request = await authorizedJSONRequest(url: url, body: bodyData)
        request.httpMethod = "PATCH"
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    print("✅ Force Train API Success!")
                    return true
                } else {
                    let err = String(data: data, encoding: .utf8) ?? "Unknown Error"
                    print("❌ Force Train Failed (\(httpResponse.statusCode)): \(err)")
                    return false
                }
            }
        } catch {
            print("❌ Force Train Network Error: \(error)")
        }
        return false
    }

    // 🚀 FIXED: Deep logging added to catch the silent Lambda crash
    func updateBusinessProfile(storeName: String, phoneNumber: String, storeWebsite: String, storeAddress: String, storeBio: String, storeLogoUrl: String, storeLogoBase64: String? = nil) async -> Bool {
        guard !userId.isEmpty else { return false }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { return false }
        
        var uploadedLogoUrl: String? = nil
        
        if let base64 = storeLogoBase64 {
            let cleanBase64 = base64.contains(",") ? String(base64.split(separator: ",")[1]) : base64
            
            if let imageData = Data(base64Encoded: cleanBase64, options: .ignoreUnknownCharacters) {
                do {
                    uploadedLogoUrl = try await uploadImageToS3(imageData: imageData, role: "business_logo")
                } catch {
                    print("❌ S3 Method execution failed for business logo.")
                    return false
                }
            } else {
                print("❌ ERROR: Could not decode Business Logo Base64 string into Data.")
            }
        }
        
        let bodyPayload: [String: Any] = [
            "purchaseType": "update_profile",
            "userId": userId,
            "storeName": storeName,
            "phoneNumber": phoneNumber,
            "storeWebsite": storeWebsite,
            "storeAddress": storeAddress,
            "storeBio": storeBio,
            "storeLogoUrl": uploadedLogoUrl ?? storeLogoUrl
        ]
        
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return false }
        let request = await authorizedJSONRequest(url: url, body: bodyData)
        
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
                    print("✅ Lambda Successfully Saved Business Profile Data!")
                    return true
                } else {
                    let errorString = String(data: data, encoding: .utf8) ?? "Unknown Error"
                    print("❌ LAMBDA REJECTED SAVE (\(httpResponse.statusCode)): \(errorString)") // <-- This will tell us the exact issue!
                }
            }
        } catch {
            print("❌ NETWORK CRASH DURING LAMBDA SAVE: \(error.localizedDescription)")
        }
        return false
    }
    
    func initDatabaseRow(emailToSave: String) async {
        guard !userId.isEmpty else { return }
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { return }
        
        let bodyPayload: [String: Any] = [
            "purchaseType": "init_user",
            "userId": userId,
            "userEmail": emailToSave
        ]
        
        guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return }
        let request = await authorizedJSONRequest(url: url, body: bodyData)
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

struct ScanHistoryItem: Identifiable, Decodable {
    var id: String { scannedAt }
    let scannedAt: String
    let landmarkId: String
    let landmarkLabel: String
    let locationString: String
    let latitude: String?
    let longitude: String?
    let imageUrl: String?
    
    var latAsDouble: Double? {
        if let latStr = latitude, let val = Double(latStr) { return val }
        return nil
    }
    
    var lonAsDouble: Double? {
        if let lonStr = longitude, let val = Double(lonStr) { return val }
        return nil
    }
}
