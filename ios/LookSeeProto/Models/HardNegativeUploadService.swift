//
//  HardNegativeUploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/16/26.
//

import Foundation
import Combine
import CryptoKit
import Amplify
import AWSPluginsCore

@MainActor
final class HardNegativeUploadService: ObservableObject {
    @Published private(set) var status: String = String(localized: "Idle")
    @Published private(set) var progress: Double = 0
    @Published private(set) var isUploading = false

    private let baseURL = URL(string: "https://d11vl3v9w133rh.cloudfront.net")!
    private let counterFastForwardSteps = [5, 15, 35, 75, 150, 300]

    enum UploadError: LocalizedError {
        case noVideo
        case invalidURL
        case invalidResponse
        case badStatus(Int, String)
        case responseCountMismatch
        case missingLocalFile(String)
        case incompleteUpload

        var errorDescription: String? {
            switch self {
            case .noVideo: return String(localized: "No negative video was provided.")
            case .invalidURL: return String(localized: "The upload service returned an invalid URL.")
            case .invalidResponse: return String(localized: "The server returned an invalid response.")
            case .badStatus(let code, let body): return String(localized: "HTTP \(code): \(body)")
            case .responseCountMismatch: return String(localized: "Expected 1 upload URL, but received a different amount.")
            case .missingLocalFile(let filename): return String(localized: "The local video could not be found: \(filename)")
            case .incompleteUpload: return String(localized: "The upload failed to complete successfully.")
            }
        }
    }

    // MARK: - Public upload flow

    func upload(landmarkId: String, idToken: String, video: CapturedNegativeVideo) async throws -> HardNegativeCompleteResponse {
        isUploading = true
        progress = 0
        status = String(localized: "Preparing negative video…")

        defer { isUploading = false }

        do {
            let initResponse = try await initializeUpload(landmarkId: landmarkId, token: idToken, video: video)
            guard let uploadTarget = initResponse.uploads.first, initResponse.uploads.count == 1 else {
                throw UploadError.responseCountMismatch
            }

            progress = 0.1
            status = String(localized: "Uploading negative video to S3…")

            try await uploadVideo(video, to: uploadTarget)

            progress = 0.85
            status = String(localized: "Finalizing negative video…")

            let completeResponse = try await completeUpload(landmarkId: landmarkId, batchId: initResponse.batchId, negativeIds: [uploadTarget.negativeId], token: idToken)

            guard completeResponse.failedCount == 0, completeResponse.processedCount == 1 else {
                throw UploadError.incompleteUpload
            }

            progress = 1
            let backendStatus = completeResponse.processed?.first?.status
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased()

            if backendStatus == "READY" {
                status = String(localized: "Negative video is ready.")
            } else {
                status = String(localized: "Negative video uploaded and is processing.")
            }

            return completeResponse

        } catch {
            status = String(localized: "Negative upload failed: \(error.localizedDescription)")
            print("❌ Hard-negative upload failed:", error)
            throw error
        }
    }

    func reset() {
        status = String(localized: "Idle")
        progress = 0
        isUploading = false
    }

    // MARK: - Init

    private func initializeUpload(
        landmarkId: String,
        token: String,
        video: CapturedNegativeVideo,
        counterRetryCount: Int = 0,
        hasReattested: Bool = false
    ) async throws -> HardNegativeInitResponse {
        await AppAttestGlobalLock.shared.acquire()

        if AppAttestService.shared.currentKeyId == nil && !hasReattested {
            await AppAttestService.shared.reauthenticateAndRegisterDevice(idToken: token)
        }

        let url = baseURL.appendingPathComponent("landmarks").appendingPathComponent(landmarkId).appendingPathComponent("hard-negatives").appendingPathComponent("init")
        
        let body = HardNegativeInitRequest(
            files: [HardNegativeFileRequest(filename: video.filename, contentType: "video/quicktime")],
            nonce: UUID().uuidString
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let cleanToken = token.hasPrefix("Bearer ") ? String(token.dropFirst(7)) : token
        request.setValue("Bearer \(cleanToken)", forHTTPHeaderField: "Authorization")
        
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let payload = try encoder.encode(body)
        request.httpBody = payload

        do {
            let assertion = try await AppAttestService.shared.generateAssertion(for: payload)
            if let keyId = AppAttestService.shared.currentKeyId {
                print("✅ [Upload Init Negative] Hardware signature generated! Attaching headers.")
                request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                request.setValue(assertion, forHTTPHeaderField: "X-LookSee-App-Attest")
                
                let hashString = Data(SHA256.hash(data: payload)).base64EncodedString()
                request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
            } else {
                print("❌ [Upload Init Negative] Hardware signature generated, but currentKeyId is missing from memory.")
            }
        } catch {
            print("❌ [Upload Init Negative] FAILED to generate hardware signature: \(error.localizedDescription)")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        
        // 🛡️ TWO-PATH SELF-HEALING LOGIC
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 403 {
            let bodyString = String(data: data, encoding: .utf8) ?? ""
            
            let isKeyError = bodyString.contains("Key ID mismatch")
                || bodyString.contains("Device not registered")
                || bodyString.contains("signature missing")
                || bodyString.contains("Unrecognized device")
            
            if isKeyError && !hasReattested {
                print("⚠️ [Upload Init Negative] Key ID Mismatch / Unregistered Device. Re-attesting device...")
                await AppAttestService.shared.reauthenticateAndRegisterDevice(idToken: token)
                await AppAttestGlobalLock.shared.release()
                try await Task.sleep(nanoseconds: 250_000_000)
                return try await initializeUpload(
                    landmarkId: landmarkId,
                    token: token,
                    video: video,
                    counterRetryCount: 0,
                    hasReattested: true
                )
            }
            
            let isCounterError = bodyString.contains("Invalid hardware counter")
                || bodyString.contains("Replay attack detected")
            
            if isCounterError && counterRetryCount < counterFastForwardSteps.count {
                let steps = counterFastForwardSteps[counterRetryCount]
                print("⚠️ [Upload Init Negative] Hardware counter behind backend. Fast-forwarding by \(steps)...")
                await AppAttestService.shared.fastForwardCounter(steps: steps)
                await AppAttestGlobalLock.shared.release()
                return try await initializeUpload(
                    landmarkId: landmarkId,
                    token: token,
                    video: video,
                    counterRetryCount: counterRetryCount + 1,
                    hasReattested: hasReattested
                )
            }
        }
        
        await AppAttestGlobalLock.shared.release()
        try validateAPIResponse(response, data: data)

        return try JSONDecoder().decode(HardNegativeInitResponse.self, from: data)
    }

    // MARK: - Presigned S3 uploads

    private func uploadVideo(_ video: CapturedNegativeVideo, to target: HardNegativeUploadTarget) async throws {
        guard FileManager.default.fileExists(atPath: video.fileURL.path) else {
            throw UploadError.missingLocalFile(video.filename)
        }
        guard let url = URL(string: target.uploadUrl.url) else {
            throw UploadError.invalidURL
        }

        let videoData = try Data(contentsOf: video.fileURL)
        let boundary = "Boundary-\(UUID().uuidString)"

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        
        for (key, value) in target.uploadUrl.fields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }

        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(video.filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(target.contentType)\r\n\r\n".data(using: .utf8)!)
        body.append(videoData)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        let (responseData, response) = try await URLSession.shared.upload(for: request, from: body)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorString = String(data: responseData, encoding: .utf8) ?? ""
            print("❌ S3 POST failed for \(video.filename). Msg: \(errorString)")
            throw UploadError.badStatus(httpResponse.statusCode, "S3 Upload failed")
        }
    }

    // MARK: - Complete

    private func completeUpload(
        landmarkId: String,
        batchId: String,
        negativeIds: [String],
        token: String,
        counterRetryCount: Int = 0,
        hasReattested: Bool = false
    ) async throws -> HardNegativeCompleteResponse {
        await AppAttestGlobalLock.shared.acquire()
        
        let url = baseURL.appendingPathComponent("landmarks").appendingPathComponent(landmarkId).appendingPathComponent("hard-negatives").appendingPathComponent("complete")
        
        let body = HardNegativeCompleteRequest(batchId: batchId, negativeIds: negativeIds, nonce: UUID().uuidString)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let cleanToken = token.hasPrefix("Bearer ") ? String(token.dropFirst(7)) : token
        request.setValue("Bearer \(cleanToken)", forHTTPHeaderField: "Authorization")
        
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let payload = try encoder.encode(body)
        request.httpBody = payload

        do {
            let assertion = try await AppAttestService.shared.generateAssertion(for: payload)
            if let keyId = AppAttestService.shared.currentKeyId {
                print("✅ [Upload Complete Negative] Hardware signature generated! Attaching headers.")
                request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                request.setValue(assertion, forHTTPHeaderField: "X-LookSee-App-Attest")
                let hashString = Data(SHA256.hash(data: payload)).base64EncodedString()
                request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
            }
        } catch {
            print("❌ [Upload Complete Negative] FAILED to generate hardware signature: \(error.localizedDescription)")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        
        // 🛡️ TWO-PATH SELF-HEALING LOGIC
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 403 {
            let bodyString = String(data: data, encoding: .utf8) ?? ""
            
            let isKeyError = bodyString.contains("Key ID mismatch")
                || bodyString.contains("Device not registered")
                || bodyString.contains("signature missing")
                || bodyString.contains("Unrecognized device")
            
            if isKeyError && !hasReattested {
                print("⚠️ [Upload Complete Negative] Key ID Mismatch / Unregistered Device. Re-attesting device...")
                await AppAttestService.shared.reauthenticateAndRegisterDevice(idToken: token)
                await AppAttestGlobalLock.shared.release()
                try await Task.sleep(nanoseconds: 250_000_000)
                return try await completeUpload(
                    landmarkId: landmarkId,
                    batchId: batchId,
                    negativeIds: negativeIds,
                    token: token,
                    counterRetryCount: 0,
                    hasReattested: true
                )
            }
            
            let isCounterError = bodyString.contains("Invalid hardware counter")
                || bodyString.contains("Replay attack detected")
            
            if isCounterError && counterRetryCount < counterFastForwardSteps.count {
                let steps = counterFastForwardSteps[counterRetryCount]
                print("⚠️ [Upload Complete Negative] Hardware counter behind backend. Fast-forwarding by \(steps)...")
                await AppAttestService.shared.fastForwardCounter(steps: steps)
                await AppAttestGlobalLock.shared.release()
                return try await completeUpload(
                    landmarkId: landmarkId,
                    batchId: batchId,
                    negativeIds: negativeIds,
                    token: token,
                    counterRetryCount: counterRetryCount + 1,
                    hasReattested: hasReattested
                )
            }
        }
        
        await AppAttestGlobalLock.shared.release()
        try validateAPIResponse(response, data: data)

        return try JSONDecoder().decode(HardNegativeCompleteResponse.self, from: data)
    }

    private func validateAPIResponse(_ response: URLResponse, data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else { throw UploadError.invalidResponse }
        guard (200...299).contains(httpResponse.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw UploadError.badStatus(httpResponse.statusCode, body)
        }
    }
}

// MARK: - Init models

private struct HardNegativeInitRequest: Encodable {
    let files: [HardNegativeFileRequest]
    let nonce: String
}

private struct HardNegativeFileRequest: Encodable {
    let filename: String
    let contentType: String
}

private struct HardNegativeInitResponse: Decodable {
    let message: String
    let batchId: String
    let landmarkId: String
    let landmarkLabel: String?
    let landmarkFolder: String?
    let expiresInSeconds: Int?
    let uploads: [HardNegativeUploadTarget]
}

private struct HardNegativeUploadTarget: Decodable {
    let negativeId: String
    let uploadUrl: S3PresignedPost
    let sourceBucket: String?
    let sourceKey: String
    let contentType: String
}

// MARK: - Complete models

private struct HardNegativeCompleteRequest: Encodable {
    let batchId: String
    let negativeIds: [String]
    let nonce: String
}

struct HardNegativeCompleteResponse: Decodable {
    let message: String?
    let landmarkId: String
    let batchId: String
    let processedCount: Int
    let failedCount: Int
    let processed: [HardNegativeProcessedItem]?
}

struct HardNegativeProcessedItem: Decodable {
    let negativeId: String
    let status: String
}
