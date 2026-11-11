//
//  AppAttestService.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/21/26.
//

import Foundation
import DeviceCheck
import CryptoKit
import Amplify

// 🚀 DEFINED EXACTLY ONCE HERE: Global lock to serialize App Attest operations
public actor AppAttestGlobalLock {
    public static let shared = AppAttestGlobalLock()
    private var isLocked = false
    
    public init() {}
    
    public func acquire() async {
        while isLocked {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        isLocked = true
    }
    
    public func release() {
        isLocked = false
    }
}

enum AppAttestError: Error {
    case notSupported
    case keyGenerationFailed
    case attestationFailed
    case assertionFailed
    case bypassModeActive
    case missingKeyId
}

final class AppAttestService {
    static let shared = AppAttestService()
    private let service = DCAppAttestService.shared
    
    // Memory flag to prevent repetitive hardware timeouts
    private var isBypassed = false
    
    // Prevents ambiguous init() error
    private init() {}
    
    // Expose the KeyId so UploadService can send it to the backend
    var currentKeyId: String? {
        UserDefaults.standard.string(forKey: "LookSeeAppAttestKeyId")
    }
    
    /// Clears local state ONLY when the backend does not recognize the Key ID
    func clearState() {
        UserDefaults.standard.removeObject(forKey: "LookSeeAppAttestKeyId")
        isBypassed = false
        print("🧹 [AppAttest] Local hardware key cleared. Ready for fresh attestation.")
    }
    
    /// Fast-forwards the local Secure Enclave hardware counter for the CURRENT key without wiping it.
    func fastForwardCounter(steps: Int) async {
        guard !isBypassed, service.isSupported, let keyId = currentKeyId, steps > 0 else { return }
        print("⏩ [AppAttest] Fast-forwarding hardware counter by \(steps) steps...")
        for _ in 0..<steps {
            let dummyHash = Data(SHA256.hash(data: Data(UUID().uuidString.utf8)))
            _ = try? await service.generateAssertion(keyId, clientDataHash: dummyHash)
        }
    }
    
    /// Generates a fresh hardware key with Apple and registers it with the AWS backend.
    func reauthenticateAndRegisterDevice(idToken: String) async {
        clearState()
        
        let resolvedUserId = (try? await Amplify.Auth.getCurrentUser().userId) ?? ""
        let challenge = resolvedUserId.isEmpty ? UUID().uuidString : resolvedUserId
        
        do {
            let (keyId, attestation) = try await attestDevice(challenge: challenge)
            print("✅ App Attest Phase 1 Successful! KeyId: \(keyId)")
            
            guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/checkout") else { return }
            
            var bodyPayload: [String: Any] = [
                "purchaseType": "register_device",
                "keyId": keyId,
                "attestation": attestation,
                "challenge": challenge
            ]
            if !resolvedUserId.isEmpty {
                bodyPayload["userId"] = resolvedUserId
            }
            
            guard let bodyData = try? JSONSerialization.data(withJSONObject: bodyPayload) else { return }
            
            let cleanToken = idToken.hasPrefix("Bearer ") ? String(idToken.dropFirst(7)) : idToken
            
            for attempt in 0..<3 {
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.setValue("Bearer \(cleanToken)", forHTTPHeaderField: "Authorization")
                request.httpBody = bodyData
                
                // Attach hardware signature for the registration request using the NEW key
                do {
                    let clientDataHash = Data(SHA256.hash(data: bodyData))
                    let assertion = try await service.generateAssertion(keyId, clientDataHash: clientDataHash)
                    let assertionBase64 = assertion.base64EncodedString()
                    
                    request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                    request.setValue(assertionBase64, forHTTPHeaderField: "X-LookSee-App-Attest")
                    let hashString = clientDataHash.base64EncodedString()
                    request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
                } catch {
                    print("❌ [AppAttest] Failed to generate assertion for registration: \(error.localizedDescription)")
                }
                
                let (data, response) = try await URLSession.shared.data(for: request)
                let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                
                if (200...299).contains(statusCode) {
                    if !resolvedUserId.isEmpty {
                        UserDefaults.standard.set(true, forKey: "AppAttestSyncedV6_\(resolvedUserId)")
                    }
                    print("✅ Backend successfully saved the new App Attest Public Key!")
                    return
                } else if statusCode == 409 && attempt < 2 {
                    print("⏳ Backend busy (409) during key registration. Retrying in 0.5s...")
                    try? await Task.sleep(nanoseconds: 500_000_000)
                } else {
                    let err = String(data: data, encoding: .utf8) ?? ""
                    print("❌ Backend rejected the new App Attest Key. Status: \(statusCode) \(err)")
                    return
                }
            }
        } catch {
            print("❌ App Attest Phase 1 Failed during registration: \(error.localizedDescription)")
        }
    }
    
    /// PHASE 1: Generate a key and ask Apple to certify it.
    func attestDevice(challenge: String) async throws -> (keyId: String, attestationString: String) {
        if isBypassed { throw AppAttestError.bypassModeActive }
        guard service.isSupported else {
            isBypassed = true
            throw AppAttestError.notSupported
        }
        
        do {
            let keyId = try await service.generateKey()
            let clientDataHash = Data(SHA256.hash(data: Data(challenge.utf8)))
            
            // Ask Apple's servers to cryptographically verify this iPhone
            let attestation = try await service.attestKey(keyId, clientDataHash: clientDataHash)
            
            // Save the Key ID locally ONLY after Apple approves it
            UserDefaults.standard.set(keyId, forKey: "LookSeeAppAttestKeyId")
            
            return (keyId, attestation.base64EncodedString())
            
        } catch {
            isBypassed = true
            throw AppAttestError.attestationFailed
        }
    }
    
    /// PHASE 2: Generates a unique, cryptographically signed assertion for video uploads
    func generateAssertion(for payload: Data) async throws -> String {
        if isBypassed { throw AppAttestError.bypassModeActive }
        guard service.isSupported else {
            isBypassed = true
            throw AppAttestError.notSupported
        }
        
        guard let keyId = currentKeyId else {
            throw AppAttestError.missingKeyId
        }
        
        do {
            let clientDataHash = Data(SHA256.hash(data: payload))
            let assertion = try await service.generateAssertion(keyId, clientDataHash: clientDataHash)
            return assertion.base64EncodedString()
        } catch {
            throw AppAttestError.assertionFailed
        }
    }
}
