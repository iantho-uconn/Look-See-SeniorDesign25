//
//  AppAttestService.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/21/26.
//

import Foundation
import DeviceCheck
import CryptoKit

enum AppAttestError: Error {
    case notSupported
    case keyGenerationFailed
    case attestationFailed
    case assertionFailed
    case bypassModeActive
}

final class AppAttestService {
    static let shared = AppAttestService()
    private let service = DCAppAttestService.shared
    
    // 🚀 THE FIX: A memory flag to prevent repetitive hardware timeouts
    private var isBypassed = false
    
    /// Generates a unique, cryptographically signed assertion string for the given payload
    func generateAssertion(for payload: Data) async throws -> String {
        
        // If we already know the capability is missing, fail instantly to prevent lag.
        if isBypassed {
            throw AppAttestError.bypassModeActive
        }
        
        // App Attest is strictly bound to iOS hardware capabilities.
        guard service.isSupported else {
            isBypassed = true
            throw AppAttestError.notSupported
        }
        
        do {
            let keyId = try await getOrCreateKeyId()
            let clientDataHash = Data(SHA256.hash(data: payload))
            
            // This prompts the Secure Enclave to sign the hash using the hardware key
            let assertion = try await service.generateAssertion(keyId, clientDataHash: clientDataHash)
            
            // Return the Base64 representation to be attached as an HTTP header
            return assertion.base64EncodedString()
            
        } catch {
            // 🚀 Fail fast on the first error so it doesn't lag out every network request!
            isBypassed = true
            throw error
        }
    }
    
    private func getOrCreateKeyId() async throws -> String {
        // In a production environment, you would store the KeyId in the Secure Keychain.
        // For simplicity, we are relying on UserDefaults.
        if let existingKeyId = UserDefaults.standard.string(forKey: "LookSeeAppAttestKeyId") {
            return existingKeyId
        }
        
        let newKeyId = try await service.generateKey()
        UserDefaults.standard.set(newKeyId, forKey: "LookSeeAppAttestKeyId")
        return newKeyId
    }
}
