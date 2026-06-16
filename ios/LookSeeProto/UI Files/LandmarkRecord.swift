//
//  UploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//  Updated to:
//  - Return positive submission results
//  - Propagate upload errors
//  - Prevent duplicate uploads
//  - Provide user-friendly progress messages
//

import Foundation
import UIKit
import Combine

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
        case .idle:
            return "arrow.up.circle"

        case .validating:
            return "checklist"

        case .preparingUpload:
            return "lock.shield"

        case .uploadingMedia:
            return "icloud.and.arrow.up"

        case .finalizing:
            return "gearshape.2"

        case .complete:
            return "checkmark.circle.fill"

        case .failed:
            return "exclamationmark.triangle.fill"
        }
    }
}

// MARK: - Successful positive-upload result

struct PositiveSubmissionResult {
    let submissionId: String
    let landmarkId: String?
    let mediaKind: MediaKind
    let s3Key: String
}

// MARK: - Upload service

@MainActor
final class UploadService: ObservableObject {

    // MARK: Published state

    @Published private(set) var status: String = "Ready to upload"

    @Published private(set) var detail: String =
        "Your landmark media has not been uploaded yet."

    @Published private(set) var progress: Double = 0

    @Published private(set) var isUploading: Bool = false

    @Published private(set) var stage: PositiveUploadStage = .idle

    // MARK: API configuration

    private let baseURL = URL(
        string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"
    )!

    private let apiTimeout: TimeInterval = 60
    private let mediaUploadTimeout: TimeInterval = 300

    // MARK: Errors

    enum UploadError: LocalizedError {
        case uploadAlreadyInProgress
        case missingLabel
        case missingUserEmail
        case noMediaSelected
        case multipleMediaSelected
        case invalidURL
        case invalidResponse
        case missingImageData
        case badStatus(Int, String)

        var errorDescription: String? {
            switch self {
            case .uploadAlreadyInProgress:
                return "An upload is already in progress."

            case .missingLabel:
                return "Please enter a landmark label before uploading."

            case .missingUserEmail:
                return "We could not verify your account. Please sign in again and retry."

            case .noMediaSelected:
                return "Please record one video or take one landmark photo."

            case .multipleMediaSelected:
                return "Please select either one video or one photo, not both."

            case .invalidURL:
                return "The server returned an invalid upload link. Please try again."

            case .invalidResponse:
                return "The server returned an unexpected response. Please try again."

            case .missingImageData:
                return "The selected photo could not be prepared for upload."

            case .badStatus(let code, _):
                if code == 401 || code == 403 {
                    return "Your session is no longer authorized. Please sign in again."
                }

                if code == 404 {
                    return "The upload service could not find the requested resource."
                }

                if code == 408 {
                    return "The upload request timed out. Please try again."
                }

                if code == 413 {
                    return "The selected media file is too large to upload."
                }

                if code == 429 {
                    return "Too many upload attempts were made. Please wait a moment and try again."
                }

                if code >= 500 {
                    return "The LookSee service is temporarily unavailable. Please try again shortly."
                }

                return "The upload could not be completed. Server error \(code)."
            }
        }
    }

    // MARK: - Main upload flow

    func upload(
        userEmail: String,
        label: String,
        landmarkId: String? = nil,
        landmarkLabel: String? = nil,
        shortDescription: String?,
        userDescription: String?,
        latitude: Double?,
        longitude: Double?,
        horizontalAccuracy: Double?,
        videoURL: URL?,
        image: UIImage?
    ) async throws -> PositiveSubmissionResult {

        guard !isUploading else {
            throw UploadError.uploadAlreadyInProgress
        }

        isUploading = true

        defer {
            isUploading = false
        }

        do {
            updateStage(
                .validating,
                progress: 0.05,
                status: "Checking your landmark details",
                detail: "Making sure the required information and media are ready."
            )

            let trimmedLabel = label.trimmingCharacters(
                in: .whitespacesAndNewlines
            )

            guard !trimmedLabel.isEmpty else {
                throw UploadError.missingLabel
            }

            let trimmedUserEmail = userEmail.trimmingCharacters(
                in: .whitespacesAndNewlines
            )

            guard !trimmedUserEmail.isEmpty else {
                throw UploadError.missingUserEmail
            }

            let hasVideo = videoURL != nil
            let hasImage = image != nil

            guard hasVideo || hasImage else {
                throw UploadError.noMediaSelected
            }

            guard !(hasVideo && hasImage) else {
                throw UploadError.multipleMediaSelected
            }

            let trimmedShortDescription = shortDescription?
                .trimmingCharacters(
                    in: .whitespacesAndNewlines
                )

            let trimmedUserDescription = userDescription?
                .trimmingCharacters(
                    in: .whitespacesAndNewlines
                )

            let mediaKind: MediaKind
            let filename: String
            let contentType: String

            if videoURL != nil {
                mediaKind = .video
                filename = "video.mov"
                contentType = "video/quicktime"
            } else {
                mediaKind = .photo
                filename = "photo.jpg"
                contentType = "image/jpeg"
            }

            let initRequest = InitSubmissionRequest(
                userEmail: trimmedUserEmail,
                label: trimmedLabel,
                mediaKind: mediaKind,
                filename: filename,
                contentType: contentType
            )

            updateStage(
                .preparingUpload,
                progress: 0.12,
                status: "Preparing a secure upload",
                detail: "This should only take a moment."
            )

            let initResponse = try await initSubmission(
                initRequest
            )

            print(
                "✅ Positive init completed:",
                initResponse.submissionId
            )

            if mediaKind == .video {
                updateStage(
                    .uploadingMedia,
                    progress: 0.20,
                    status: "Uploading your landmark video",
                    detail: "Videos can take a little longer. Keep LookSee open until the upload finishes."
                )
            } else {
                updateStage(
                    .uploadingMedia,
                    progress: 0.20,
                    status: "Uploading your landmark photo",
                    detail: "Keep LookSee open while your photo is uploaded."
                )
            }

            try await putToS3(
                presignedURL: initResponse.uploadUrl,
                contentType: contentType,
                videoURL: videoURL,
                image: image
            )

            updateStage(
                .finalizing,
                progress: 0.88,
                status: "Saving your landmark",
                detail: "Your media is uploaded. We’re attaching its information and location."
            )

            let normalizedShortDescription: String?

            if let trimmedShortDescription,
               !trimmedShortDescription.isEmpty {
                normalizedShortDescription = trimmedShortDescription
            } else {
                normalizedShortDescription = nil
            }

            let normalizedUserDescription: String?

            if let trimmedUserDescription,
               !trimmedUserDescription.isEmpty {
                normalizedUserDescription = trimmedUserDescription
            } else {
                normalizedUserDescription = nil
            }

            let completeRequest = CompleteSubmissionRequest(
                submissionId: initResponse.submissionId,
                s3Key: initResponse.s3Key,
                userEmail: trimmedUserEmail,
                label: trimmedLabel,
                landmarkId: landmarkId,
                landmarkLabel: landmarkLabel,
                mediaKind: mediaKind,
                shortDescription: normalizedShortDescription,
                userDescription: normalizedUserDescription,
                latitude: latitude,
                longitude: longitude,
                horizontalAccuracy: horizontalAccuracy
            )

            try await completeSubmission(
                completeRequest
            )

            updateStage(
                .complete,
                progress: 1,
                status: "Landmark media uploaded",
                detail: "Your positive landmark media was saved successfully."
            )

            print(
                "✅ Positive upload completed:",
                initResponse.submissionId
            )

            return PositiveSubmissionResult(
                submissionId: initResponse.submissionId,
                landmarkId: landmarkId,
                mediaKind: mediaKind,
                s3Key: initResponse.s3Key
            )

        } catch {
            print("❌ Positive upload failed:", error)

            updateStage(
                .failed,
                progress: progress,
                status: "Upload couldn’t be completed",
                detail: userFriendlyMessage(for: error)
            )

            throw error
        }
    }

    // MARK: - Reset state

    func reset() {
        guard !isUploading else {
            return
        }

        stage = .idle
        progress = 0
        status = "Ready to upload"
        detail = "Your landmark media has not been uploaded yet."
    }

    // MARK: - State helpers

    private func updateStage(
        _ newStage: PositiveUploadStage,
        progress newProgress: Double,
        status newStatus: String,
        detail newDetail: String
    ) {
        stage = newStage
        progress = min(
            max(newProgress, 0),
            1
        )
        status = newStatus
        detail = newDetail
    }

    private func userFriendlyMessage(
        for error: Error
    ) -> String {
        if let uploadError = error as? UploadError {
            return uploadError.localizedDescription
        }

        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet:
                return "No internet connection was found. Your information is still on this screen, so reconnect and try again."

            case .timedOut:
                return "The upload took too long. Check your connection and try again."

            case .networkConnectionLost:
                return "The connection was interrupted. Your information is still on this screen, so you can retry."

            case .cannotConnectToHost,
                 .cannotFindHost,
                 .dnsLookupFailed:
                return "LookSee could not connect to the upload service. Please check your connection and try again."

            case .cancelled:
                return "The upload was cancelled."

            default:
                return "A network problem interrupted the upload. Please check your connection and try again."
            }
        }

        return error.localizedDescription
    }

    // MARK: - Initialize submission

    private func initSubmission(
        _ requestBody: InitSubmissionRequest
    ) async throws -> InitSubmissionResponse {
        let url = baseURL
            .appendingPathComponent("submissions")
            .appendingPathComponent("init")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Content-Type"
        )
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Accept"
        )
        request.httpBody = try JSONEncoder().encode(
            requestBody
        )

        let (data, response) = try await URLSession.shared.data(
            for: request
        )

        try validateAPIResponse(
            response,
            data: data
        )

        do {
            return try JSONDecoder().decode(
                InitSubmissionResponse.self,
                from: data
            )
        } catch {
            print(
                "❌ Could not decode init response:",
                String(data: data, encoding: .utf8) ?? "<empty>"
            )

            throw UploadError.invalidResponse
        }
    }

    // MARK: - Upload positive media to S3

    private func putToS3(
        presignedURL: String,
        contentType: String,
        videoURL: URL?,
        image: UIImage?
    ) async throws {
        guard let url = URL(
            string: presignedURL
        ) else {
            throw UploadError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.timeoutInterval = mediaUploadTimeout

        // This must exactly match the content type used when the
        // backend generated the presigned URL.
        request.setValue(
            contentType,
            forHTTPHeaderField: "Content-Type"
        )

        if let videoURL {
            guard FileManager.default.fileExists(
                atPath: videoURL.path
            ) else {
                throw UploadError.noMediaSelected
            }

            let (_, response) = try await URLSession.shared.upload(
                for: request,
                fromFile: videoURL
            )

            try validateS3Response(response)
            return
        }

        if let image {
            guard let imageData = image.jpegData(
                compressionQuality: 0.9
            ) else {
                throw UploadError.missingImageData
            }

            let (_, response) = try await URLSession.shared.upload(
                for: request,
                from: imageData
            )

            try validateS3Response(response)
            return
        }

        throw UploadError.noMediaSelected
    }

    private func validateS3Response(
        _ response: URLResponse
    ) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(
            httpResponse.statusCode
        ) else {
            throw UploadError.badStatus(
                httpResponse.statusCode,
                "The S3 media upload failed."
            )
        }
    }

    // MARK: - Complete submission

    private func completeSubmission(
        _ requestBody: CompleteSubmissionRequest
    ) async throws {
        let url = baseURL
            .appendingPathComponent("submissions")
            .appendingPathComponent("complete")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = apiTimeout
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Content-Type"
        )
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Accept"
        )
        request.httpBody = try JSONEncoder().encode(
            requestBody
        )

        let (data, response) = try await URLSession.shared.data(
            for: request
        )

        try validateAPIResponse(
            response,
            data: data
        )
    }

    // MARK: - API response validation

    private func validateAPIResponse(
        _ response: URLResponse,
        data: Data
    ) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(
            httpResponse.statusCode
        ) else {
            let body = String(
                data: data,
                encoding: .utf8
            ) ?? ""

            print(
                "❌ API error \(httpResponse.statusCode):",
                body
            )

            throw UploadError.badStatus(
                httpResponse.statusCode,
                body
            )
        }
    }
}
