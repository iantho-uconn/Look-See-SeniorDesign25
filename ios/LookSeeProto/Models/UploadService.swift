//
//  UploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//

import Foundation
import UIKit
import Combine
import Amplify
import AWSPluginsCore
import CryptoKit

// MARK: - User-facing upload stage

enum PositiveUploadStage: Equatable {
    case idle
    case validating
    case preparingUpload
    case uploadingMedia
    case finalizing
    case complete
    case failed

    var systemImage: String {
        switch self {
        case .idle: return "arrow.up.circle"
        case .validating: return "checklist"
        case .preparingUpload: return "lock.shield"
        case .uploadingMedia: return "icloud.and.arrow.up"
        case .finalizing: return "gearshape.2"
        case .complete: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        }
    }
}

// MARK: - Upload service

@MainActor
final class UploadService: ObservableObject {

    @Published private(set) var status: String = String(localized: "Continue landmark entries below")

    @Published private(set) var detail: String =
        String(localized: "Your landmark media has not been uploaded yet.")

    @Published private(set) var progress: Double = 0

    @Published private(set) var isUploading: Bool = false

    @Published private(set) var stage: PositiveUploadStage = .idle

    // MARK: API configuration
    private let baseURL = URL(string: "https://d11vl3v9w133rh.cloudfront.net")!
    private let apiTimeout: TimeInterval = 60
    
    private let mediaUploadTimeout: TimeInterval = 900
    
    private let minimumCombinedVideoDuration: Double = 1.0

    // MARK: Errors
    enum UploadError: LocalizedError {
        case uploadAlreadyInProgress
        case missingLabel
        case notSignedIn
        case tokensUnavailable
        case noMediaSelected
        case multipleMediaSelected
        case invalidURL
        case invalidResponse
        case missingImageData
        case badStatus(Int, String)

        var errorDescription: String? {
            switch self {
            case .uploadAlreadyInProgress: return String(localized: "An upload is already in progress.")
            case .missingLabel: return String(localized: "Please enter a landmark label before uploading.")
            case .notSignedIn: return String(localized: "You must be signed in before uploading.")
            case .tokensUnavailable: return String(localized: "We could not access your secure login token. Please sign out, sign back in, and try again.")
            case .noMediaSelected: return String(localized: "Please record one or more videos (totaling at least 15 seconds) or take one landmark photo.")
            case .multipleMediaSelected: return String(localized: "Please select either video(s) or a photo, not both.")
            case .invalidURL: return String(localized: "The server returned an invalid upload link. Please try again.")
            case .invalidResponse: return String(localized: "The server returned an unexpected response. Please try again.")
            case .missingImageData: return String(localized: "The selected photo could not be prepared for upload.")
            case .badStatus(let code, let msg):
                if code == 401 || code == 403 { return String(localized: "Backend Error (403): \(msg)") }
                if code == 404 { return String(localized: "The upload service could not find the requested resource.") }
                if code == 408 { return String(localized: "The upload request timed out. Please try again.") }
                if code == 413 { return String(localized: "The selected media file is too large to upload.") }
                if code == 429 { return String(localized: "Too many upload attempts were made. Please wait a moment and try again.") }
                if code >= 500 { return String(localized: "The LookSee service is temporarily unavailable. Please try again shortly.") }
                return String(localized: "The upload could not be completed. Server error \(code).")
            }
        }
    }

    // MARK: - Main upload flow

    func upload(
        idToken: String,
        label: String,
        landmarkId: String? = nil,
        landmarkLabel: String? = nil,
        shortDescription: String?,
        userDescription: String?,
        latitude: Double?,
        longitude: Double?,
        horizontalAccuracy: Double?,
        videoURLs: [URL],
        image: UIImage?
    ) async throws -> PositiveSubmissionResult {

        guard !isUploading else { throw UploadError.uploadAlreadyInProgress }

        isUploading = true
        defer { isUploading = false }

        do {
            updateStage(
                .validating,
                progress: 0.05,
                status: String(localized: "Checking your landmark details"),
                detail: String(localized: "Making sure the required information and media are ready.")
            )

            let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedLabel.isEmpty else { throw UploadError.missingLabel }

            let hasVideo = !videoURLs.isEmpty
            let hasImage = image != nil

            guard hasVideo || hasImage else { throw UploadError.noMediaSelected }
            guard !(hasVideo && hasImage) else { throw UploadError.multipleMediaSelected }

            let trimmedShortDescription = shortDescription?.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedUserDescription = userDescription?.trimmingCharacters(in: .whitespacesAndNewlines)

            let normalizedShortDescription = (trimmedShortDescription?.isEmpty == false) ? trimmedShortDescription : nil
            let normalizedUserDescription = (trimmedUserDescription?.isEmpty == false) ? trimmedUserDescription : nil

            if let image {
                let mediaKind = MediaKind.photo
                let filename = "photo.jpg"
                let contentType = "image/jpeg"

                let initRequest = InitSubmissionRequest(label: trimmedLabel, landmarkId: landmarkId, mediaKind: mediaKind, filename: filename, contentType: contentType, nonce: UUID().uuidString)

                updateStage(
                    .preparingUpload,
                    progress: 0.12,
                    status: String(localized: "Preparing a secure upload"),
                    detail: String(localized: "This should only take a moment.")
                )

                let initResponse = try await initSubmission(initRequest, token: idToken)

                updateStage(
                    .uploadingMedia,
                    progress: 0.20,
                    status: String(localized: "Uploading your landmark photo"),
                    detail: String(localized: "Keep LookSee open while your photo is uploaded.")
                )

                try await postToS3(presignedPost: initResponse.uploadUrl, contentType: contentType, videoURL: nil, image: image)

                updateStage(
                    .finalizing,
                    progress: 0.88,
                    status: String(localized: "Saving your landmark"),
                    detail: String(localized: "Your media is uploaded. We’re attaching its information and location.")
                )

                let completeRequest = CompleteSubmissionRequest(submissionId: initResponse.submissionId, s3Key: initResponse.s3Key, label: trimmedLabel, landmarkId: landmarkId, landmarkLabel: landmarkLabel, mediaKind: mediaKind, shortDescription: normalizedShortDescription, userDescription: normalizedUserDescription, latitude: latitude, longitude: longitude, horizontalAccuracy: horizontalAccuracy, nonce: UUID().uuidString)

                try await completeSubmission(completeRequest, token: idToken)

                updateStage(
                    .complete,
                    progress: 1,
                    status: String(localized: "Landmark media uploaded"),
                    detail: String(localized: "Your positive landmark media was saved successfully.")
                )

                return PositiveSubmissionResult(submissionId: initResponse.submissionId, landmarkId: landmarkId, mediaKind: mediaKind, s3Key: initResponse.s3Key)
            }

            updateStage(
                .preparingUpload,
                progress: 0.08,
                status: String(localized: "Combining your clips"),
                detail: String(localized: "Stitching \(videoURLs.count) clip(s) into one video.")
            )

            let mergedURL = try await VideoMerger.mergeAndValidate(clipURLs: videoURLs, minimumDuration: minimumCombinedVideoDuration)

            defer { if !videoURLs.contains(mergedURL) { try? FileManager.default.removeItem(at: mergedURL) } }

            let mediaKind = MediaKind.video
            let filename = "\(label).mov"
            let contentType = "video/quicktime"

            let initRequest = InitSubmissionRequest(label: trimmedLabel, landmarkId: landmarkId, mediaKind: mediaKind, filename: filename, contentType: contentType, nonce: UUID().uuidString)

            updateStage(
                .preparingUpload,
                progress: 0.15,
                status: String(localized: "Preparing a secure upload"),
                detail: String(localized: "This should only take a moment.")
            )

            let initResponse = try await initSubmission(initRequest, token: idToken)

            updateStage(
                .uploadingMedia,
                progress: 0.20,
                status: String(localized: "Uploading your landmark video"),
                detail: String(localized: "Videos can take a little longer. Keep LookSee open until the upload finishes.")
            )

            try await postToS3(presignedPost: initResponse.uploadUrl, contentType: contentType, videoURL: mergedURL, image: nil)

            updateStage(
                .finalizing,
                progress: 0.88,
                status: String(localized: "Saving your landmark"),
                detail: String(localized: "Your media is uploaded. We’re attaching its information and location.")
            )

            let completeRequest = CompleteSubmissionRequest(submissionId: initResponse.submissionId, s3Key: initResponse.s3Key, label: trimmedLabel, landmarkId: landmarkId, landmarkLabel: landmarkLabel, mediaKind: mediaKind, shortDescription: normalizedShortDescription, userDescription: normalizedUserDescription, latitude: latitude, longitude: longitude, horizontalAccuracy: horizontalAccuracy, nonce: UUID().uuidString)

            try await completeSubmission(completeRequest, token: idToken)

            updateStage(
                .complete,
                progress: 1,
                status: String(localized: "Landmark media uploaded"),
                detail: String(localized: "Your combined video was saved successfully.")
            )

            return PositiveSubmissionResult(submissionId: initResponse.submissionId, landmarkId: landmarkId, mediaKind: mediaKind, s3Key: initResponse.s3Key)

        } catch {
            updateStage(
                .failed,
                progress: progress,
                status: String(localized: "Upload couldn’t be completed"),
                detail: userFriendlyMessage(for: error)
            )
            throw error
        }
    }

    func reset() {
        guard !isUploading else { return }
        stage = .idle
        progress = 0
        status = String(localized: "Ready to upload")
        detail = String(localized: "Your landmark media has not been uploaded yet.")
    }

    private func updateStage(_ newStage: PositiveUploadStage, progress newProgress: Double, status newStatus: String, detail newDetail: String) {
        stage = newStage
        self.progress = min(max(newProgress, 0), 1)
        self.status = newStatus
        self.detail = newDetail
    }

    private func userFriendlyMessage(for error: Error) -> String {
        if let mergeError = error as? VideoMergeError { return mergeError.localizedDescription }
        if let uploadError = error as? UploadError { return uploadError.localizedDescription }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return String(localized: "No internet connection was found. Your information is still on this screen, so reconnect and try again.")
            case .timedOut: return String(localized: "The upload took too long. Check your connection and try again.")
            case .networkConnectionLost: return String(localized: "The connection was interrupted. Your information is still on this screen, so you can retry.")
            case .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed: return String(localized: "LookSee could not connect to the upload service. Please check your connection and try again.")
            case .cancelled: return String(localized: "The upload was cancelled.")
            default: return String(localized: "A network problem interrupted the upload. Please check your connection and try again.")
            }
        }
        return error.localizedDescription
    }

    private func getCognitoIDToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()
        guard session.isSignedIn else { throw UploadError.notSignedIn }
        guard let tokenProvider = session as? AuthCognitoTokensProvider else { throw UploadError.tokensUnavailable }
        let tokens = try tokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    private func addAuthorizationHeader(to request: inout URLRequest) async throws {
        let idToken = try await getCognitoIDToken()
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
    }

    private func initSubmission(_ requestBody: InitSubmissionRequest, token: String, retryCount: Int = 0) async throws -> InitSubmissionResponse {
        let url = baseURL.appendingPathComponent("submissions").appendingPathComponent("init")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        
        request.allowsCellularAccess = true
        request.allowsExpensiveNetworkAccess = true
        request.allowsConstrainedNetworkAccess = true

        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        try await addAuthorizationHeader(to: &request)
        request.setValue(token, forHTTPHeaderField: "Authorization")
        
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let payload = try encoder.encode(requestBody)
        request.httpBody = payload
        
        do {
            let assertion = try await AppAttestService.shared.generateAssertion(for: payload)
            if let keyId = AppAttestService.shared.currentKeyId {
                print("✅ [Upload Init] Hardware signature generated! Attaching headers.")
                request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                request.setValue(assertion, forHTTPHeaderField: "X-LookSee-App-Attest")
                
                let hashString = Data(SHA256.hash(data: payload)).base64EncodedString()
                request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
            } else {
                print("❌ [Upload Init] Hardware signature generated, but currentKeyId is missing from memory.")
            }
        } catch {
            print("❌ [Upload Init] FAILED to generate hardware signature: \(error.localizedDescription)")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        
        // 🛡️ FIX: Auto-retry if the counter drifted due to background syncs
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 403 {
            let bodyString = String(data: data, encoding: .utf8) ?? ""
            if bodyString.contains("Invalid hardware counter") && retryCount < 2 {
                print("⚠️ Hardware counter out of sync. Retrying Init request...")
                // The previous attempt bumped the Secure Enclave counter forward,
                // so the next generation will naturally align with the AWS backend.
                return try await initSubmission(requestBody, token: token, retryCount: retryCount + 1)
            }
        }
        
        try validateAPIResponse(response, data: data)

        do {
            return try JSONDecoder().decode(InitSubmissionResponse.self, from: data)
        } catch {
            throw UploadError.invalidResponse
        }
    }

    private func postToS3(presignedPost: S3PresignedPost, contentType: String, videoURL: URL?, image: UIImage?) async throws {
        guard let url = URL(string: presignedPost.url) else { throw UploadError.invalidURL }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = mediaUploadTimeout
        
        request.allowsCellularAccess = true
        request.allowsExpensiveNetworkAccess = true
        request.allowsConstrainedNetworkAccess = true
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        
        for (key, value) in presignedPost.fields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }

        if let videoURL = videoURL {
            guard FileManager.default.fileExists(atPath: videoURL.path) else { throw UploadError.noMediaSelected }
            let videoData = try Data(contentsOf: videoURL)
            let filename = videoURL.lastPathComponent
            
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: \(contentType)\r\n\r\n".data(using: .utf8)!)
            body.append(videoData)
            body.append("\r\n".data(using: .utf8)!)
            
        } else if let image = image {
            guard let imageData = image.jpegData(compressionQuality: 0.9) else { throw UploadError.missingImageData }
            
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: \(contentType)\r\n\r\n".data(using: .utf8)!)
            body.append(imageData)
            body.append("\r\n".data(using: .utf8)!)
        } else {
            throw UploadError.noMediaSelected
        }

        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        let (responseData, response) = try await URLSession.shared.upload(for: request, from: body)
        try validateS3Response(response, data: responseData)
    }

    private func validateS3Response(_ response: URLResponse, data: Data? = nil) throws {
        guard let httpResponse = response as? HTTPURLResponse else { throw UploadError.invalidResponse }
        guard (200...299).contains(httpResponse.statusCode) else {
            if let data = data, let errorString = String(data: data, encoding: .utf8) {
                print("❌ S3 ERROR: \(errorString)")
            }
            throw UploadError.badStatus(httpResponse.statusCode, String(localized: "The S3 media upload failed."))
        }
    }

    private func completeSubmission(_ requestBody: CompleteSubmissionRequest, token: String) async throws {
        let url = baseURL.appendingPathComponent("submissions").appendingPathComponent("complete")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        
        request.allowsCellularAccess = true
        request.allowsExpensiveNetworkAccess = true
        request.allowsConstrainedNetworkAccess = true

        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        try await addAuthorizationHeader(to: &request)
        request.setValue(token, forHTTPHeaderField: "Authorization")
        
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let payload = try encoder.encode(requestBody)
        request.httpBody = payload
        
        do {
            let assertion = try await AppAttestService.shared.generateAssertion(for: payload)
            if let keyId = AppAttestService.shared.currentKeyId {
                print("✅ [Upload Complete] Hardware signature generated! Attaching headers.")
                request.setValue(keyId, forHTTPHeaderField: "X-LookSee-App-Attest-KeyId")
                request.setValue(assertion, forHTTPHeaderField: "X-LookSee-App-Attest")
                
                let hashString = Data(SHA256.hash(data: payload)).base64EncodedString()
                request.setValue(hashString, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
            } else {
                print("❌ [Upload Complete] Hardware signature generated, but currentKeyId is missing from memory.")
            }
        } catch {
            print("❌ [Upload Complete] FAILED to generate hardware signature: \(error.localizedDescription)")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        try validateAPIResponse(response, data: data)
    }

    private func validateAPIResponse(_ response: URLResponse, data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else { throw UploadError.invalidResponse }
        let bodyString = String(data: data, encoding: .utf8) ?? ""
        print("🌐 API Response [\(httpResponse.statusCode)]: \(bodyString)")
        guard (200...299).contains(httpResponse.statusCode) else {
            throw UploadError.badStatus(httpResponse.statusCode, bodyString)
        }
    }
}
