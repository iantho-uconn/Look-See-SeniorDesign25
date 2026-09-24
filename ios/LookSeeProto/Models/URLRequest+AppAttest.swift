//
//  URLRequest+AppAttest.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/21/26.
//

import Foundation
import CryptoKit

extension URLRequest {
    /// Automatically attaches Cognito Auth and Apple App Attest signatures to the request.
    mutating func signWithAppAttest(idToken: String? = nil) async {
        
        // 1. Ensure basic JSON headers exist
        if self.value(forHTTPHeaderField: "Content-Type") == nil {
            self.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if self.value(forHTTPHeaderField: "Accept") == nil {
            self.setValue("application/json", forHTTPHeaderField: "Accept")
        }
        
        // 2. Attach Cognito Token (if provided)
        if let token = idToken, !token.isEmpty {
            self.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        // 3. Attach App Attest Hardware Signature
        // If there is no body (like a GET request), we hash an empty Data object.
        let payload = self.httpBody ?? Data()
        
        do {
            let assertionToken = try await AppAttestService.shared.generateAssertion(for: payload)
            let payloadHash = Data(SHA256.hash(data: payload)).base64EncodedString()
            
            self.setValue(assertionToken, forHTTPHeaderField: "X-LookSee-App-Attest")
            self.setValue(payloadHash, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
        } catch {
            print("⚠️ App Attest bypassed or failed: \(error.localizedDescription)")
        }
    }
}
