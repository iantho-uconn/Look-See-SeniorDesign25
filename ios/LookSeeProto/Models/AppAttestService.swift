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
    case missingKeyId
}

final class AppAttestService {
    static let shared = AppAttestService()
    private let service = DCAppAttestService.shared
    
    // 🚀 THE FIX: Memory flag to prevent repetitive hardware timeouts
    private var isBypassed = false
    
    // 🚀 THE FIX: Expose the KeyId so UploadService can send it to the backend
    var currentKeyId: String? {
        UserDefaults.standard.string(forKey: "LookSeeAppAttestKeyId")
    }
    
    /// PHASE 1: Generate a key and ask Apple to certify it.
    /// You will call this ONCE when the user logs in, and send the result to your AWS backend.
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
            throw AppAttestError.missingKeyId // Cannot sign if we never attested!
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
