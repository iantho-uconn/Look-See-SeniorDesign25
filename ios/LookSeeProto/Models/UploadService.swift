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
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!
    private let apiTimeout: TimeInterval = 60
    private let mediaUploadTimeout: TimeInterval = 300
    private let minimumCombinedVideoDuration: Double = 30

    // MARK: Errors
    enum UploadError: LocalizedError {
        case uploadAlreadyInProgress
        case missingLabel
        case missingUserEmail
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
            case .missingUserEmail: return String(localized: "We could not verify your account. Please sign in again and retry.")
            case .notSignedIn: return String(localized: "You must be signed in before uploading.")
            case .tokensUnavailable: return String(localized: "We could not access your secure login token. Please sign out, sign back in, and try again.")
            case .noMediaSelected: return String(localized: "Please record one or more videos (totaling at least 15 seconds) or take one landmark photo.")
            case .multipleMediaSelected: return String(localized: "Please select either video(s) or a photo, not both.")
            case .invalidURL: return String(localized: "The server returned an invalid upload link. Please try again.")
            case .invalidResponse: return String(localized: "The server returned an unexpected response. Please try again.")
            case .missingImageData: return String(localized: "The selected photo could not be prepared for upload.")
            case .badStatus(let code, _):
                if code == 401 || code == 403 { return String(localized: "Your session is no longer authorized. Please sign in again.") }
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
        userEmail: String,
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

            let trimmedUserEmail = userEmail.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedUserEmail.isEmpty else { throw UploadError.missingUserEmail }

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

                // 🚀 THE FIX: Passing the landmarkId up to AWS so it uses the existing S3 folder!
                let initRequest = InitSubmissionRequest(userEmail: trimmedUserEmail, label: trimmedLabel, landmarkId: landmarkId, mediaKind: mediaKind, filename: filename, contentType: contentType)

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

                try await putToS3(presignedURL: initResponse.uploadUrl, contentType: contentType, videoURL: nil, image: image)

                updateStage(
                    .finalizing,
                    progress: 0.88,
                    status: String(localized: "Saving your landmark"),
                    detail: String(localized: "Your media is uploaded. We’re attaching its information and location.")
                )

                let completeRequest = CompleteSubmissionRequest(submissionId: initResponse.submissionId, s3Key: initResponse.s3Key, userEmail: trimmedUserEmail, label: trimmedLabel, landmarkId: landmarkId, landmarkLabel: landmarkLabel, mediaKind: mediaKind, shortDescription: normalizedShortDescription, userDescription: normalizedUserDescription, latitude: latitude, longitude: longitude, horizontalAccuracy: horizontalAccuracy)

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
            let filename = "video.mov"
            let contentType = "video/quicktime"

            // 🚀 THE FIX: Passing the landmarkId up to AWS so it uses the existing S3 folder!
            let initRequest = InitSubmissionRequest(userEmail: trimmedUserEmail, label: trimmedLabel, landmarkId: landmarkId, mediaKind: mediaKind, filename: filename, contentType: contentType)

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

            try await putToS3(presignedURL: initResponse.uploadUrl, contentType: contentType, videoURL: mergedURL, image: nil)

            updateStage(
                .finalizing,
                progress: 0.88,
                status: String(localized: "Saving your landmark"),
                detail: String(localized: "Your media is uploaded. We’re attaching its information and location.")
            )

            let completeRequest = CompleteSubmissionRequest(submissionId: initResponse.submissionId, s3Key: initResponse.s3Key, userEmail: trimmedUserEmail, label: trimmedLabel, landmarkId: landmarkId, landmarkLabel: landmarkLabel, mediaKind: mediaKind, shortDescription: normalizedShortDescription, userDescription: normalizedUserDescription, latitude: latitude, longitude: longitude, horizontalAccuracy: horizontalAccuracy)

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
        progress = min(max(newProgress, 0), 1)
        status = newStatus
        detail = newDetail
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

    private func initSubmission(_ requestBody: InitSubmissionRequest, token: String) async throws -> InitSubmissionResponse {
        let url = baseURL.appendingPathComponent("submissions").appendingPathComponent("init")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        try await addAuthorizationHeader(to: &request)
        request.setValue(token, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validateAPIResponse(response, data: data)

        do {
            return try JSONDecoder().decode(InitSubmissionResponse.self, from: data)
        } catch {
            throw UploadError.invalidResponse
        }
    }

    private func putToS3(presignedURL: String, contentType: String, videoURL: URL?, image: UIImage?) async throws {
        guard let url = URL(string: presignedURL) else { throw UploadError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.timeoutInterval = mediaUploadTimeout
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        if let videoURL {
            guard FileManager.default.fileExists(atPath: videoURL.path) else { throw UploadError.noMediaSelected }
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: videoURL)
            try validateS3Response(response)
            return
        }

        if let image {
            guard let imageData = image.jpegData(compressionQuality: 0.9) else { throw UploadError.missingImageData }
            let (_, response) = try await URLSession.shared.upload(for: request, from: imageData)
            try validateS3Response(response)
            return
        }
        throw UploadError.noMediaSelected
    }

    private func validateS3Response(_ response: URLResponse) throws {
        guard let httpResponse = response as? HTTPURLResponse else { throw UploadError.invalidResponse }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw UploadError.badStatus(httpResponse.statusCode, String(localized: "The S3 media upload failed."))
        }
    }

    private func completeSubmission(_ requestBody: CompleteSubmissionRequest, token: String) async throws {
        let url = baseURL.appendingPathComponent("submissions").appendingPathComponent("complete")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        try await addAuthorizationHeader(to: &request)
        request.setValue(token, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validateAPIResponse(response, data: data)
    }

    private func validateAPIResponse(_ response: URLResponse, data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else { throw UploadError.invalidResponse }
        guard (200...299).contains(httpResponse.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw UploadError.badStatus(httpResponse.statusCode, body)
        }
    }
}
