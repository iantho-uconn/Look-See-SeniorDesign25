//
//  AppDelegate.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 10/29/26.
//

import SwiftUI
import UserNotifications
import CryptoKit

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    
    private static let cachedTokenKey = "LookSee_CachedAPNsDeviceToken"
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil
    ) -> Bool {
        
        UNUserNotificationCenter.current().delegate = self
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if granted {
                print("✅ Push Notification permission granted.")
                DispatchQueue.main.async {
                    application.registerForRemoteNotifications()
                }
            } else if let error = error {
                print("❌ Push Notification permission denied: \(error.localizedDescription)")
            }
        }
        
        return true
    }
    
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
    
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let tokenParts = deviceToken.map { data in String(format: "%02.2hhx", data) }
        let token = tokenParts.joined()
        
        print("📱 APNs Device Token: \(token)")
        UserDefaults.standard.set(token, forKey: Self.cachedTokenKey)
    }
    
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("❌ Failed to register for remote notifications: \(error.localizedDescription)")
    }
    
    // MARK: - Backend Sync
    
    static func syncStoredTokenIfNeeded(idToken: String) async {
        guard let deviceToken = UserDefaults.standard.string(forKey: cachedTokenKey), !deviceToken.isEmpty else {
            return
        }
        await syncPushTokenToBackend(deviceToken: deviceToken, idToken: idToken)
    }
    
    static func syncPushTokenToBackend(deviceToken: String, idToken: String) async {
        do {
            let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/users/push-token")!
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            
            struct PushTokenPayload: Encodable {
                let deviceToken: String
                let nonce: String
            }
            
            let payload = try JSONEncoder().encode(PushTokenPayload(
                deviceToken: deviceToken,
                nonce: UUID().uuidString
            ))
            request.httpBody = payload
            
            // 🛡️ RESTORED: We MUST send the App Attest signature here so the backend
            // can register the new device key in the freshly cleared DynamoDB table!
            do {
                let assertion = try await AppAttestService.shared.generateAssertion(for: payload)
                if let keyId = AppAttestService.shared.currentKeyId {
                    request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                    request.setValue(assertion, forHTTPHeaderField: "X-LookSee-App-Attest")
                    
                    let hashString = Data(SHA256.hash(data: payload)).base64EncodedString()
                    request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
                } else {
                    print("❌ [Push Token Sync] Hardware signature generated, but currentKeyId is missing.")
                }
            } catch {
                print("⚠️ App Attest failed during push token sync: \(error.localizedDescription)")
            }
            
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) {
                print("✅ Successfully securely synced push token to AWS backend.")
            } else {
                let errorBody = String(data: data, encoding: .utf8) ?? ""
                print("❌ Failed to sync push token. Server responded: \(errorBody)")
            }
        } catch {
            print("❌ Network error syncing push token: \(error.localizedDescription)")
        }
    }
}
