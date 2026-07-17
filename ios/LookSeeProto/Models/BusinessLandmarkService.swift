//
//  BusinessLandmarkService.swift
//  LookSeeProto
//
//  Handles authenticated business landmark management APIs.
//


import Foundation
import Amplify
import AWSPluginsCore

// MARK: - Landmark List / Update Models

struct BusinessLandmarkListResponse: Decodable {
    let items: [BusinessLandmark]
    let count: Int
}

struct BusinessLandmarkUpdateResponse: Decodable {
    let ok: Bool
    let item: BusinessLandmark
}

struct BusinessLandmarkDeleteResponse: Decodable {
    let ok: Bool
    let message: String?
    let landmarkId: String
    let status: String?
}

// Updated to Codable to support JSONEncoder in BusinessLandmarksViewModel
struct BusinessLandmark: Codable, Identifiable, Hashable {
    let landmarkId: String
    let label: String
    let shortDescription: String?
    let latitude: Double?
    let longitude: Double?
    let promotion: String?
    let promotionEnabled: Bool?
    let isActive: Bool?
    let userEmail: String?
    let ownerUserId: String?
    let createdByUserId: String?
    let createdAt: String?
    let updatedAt: String?
    let ownershipUpdatedAt: String?

    var id: String {
        landmarkId
    }

    var displayDescription: String {
        let value = shortDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? "No description available." : value
    }

    var displayStatus: String {
        if isActive == false {
            return "Inactive"
        }

        return "Active"
    }

    var displayPromotionStatus: String {
        if promotionEnabled == true {
            return "Promotion enabled"
        }

        return "No active promotion"
    }
}

// MARK: - Business Media Upload Models

enum BusinessDatasetRole: String {
    case positive = "positive"
    case hardNegative = "hard-negative"

    var displayName: String {
        switch self {
        case .positive:
            return "Positive Media"
        case .hardNegative:
            return "Negative Examples"
        }
    }

    var successMessage: String {
        switch self {
        case .positive:
            return "Positive media uploaded successfully."
        case .hardNegative:
            return "Negative example uploaded successfully."
        }
    }

    var filenameComponent: String {
        switch self {
        case .positive:
            return "positive"
        case .hardNegative:
            return "hard_negative"
        }
    }
}

enum BusinessMediaKind: String {
    case photo = "photo"
    case video = "video"
}

struct BusinessMediaUploadInitResponse: Decodable {
    let submissionId: String
    let uploadUrl: String
    let s3Key: String
    let bucket: String?
    let datasetRole: String
    let mediaKind: String
    let landmarkId: String
}

struct BusinessMediaUploadCompleteResponse: Decodable {
    let ok: Bool
    let submissionId: String
    let status: String?
    let datasetRole: String?
    let mediaKind: String?
    let landmarkId: String?
    let s3Key: String?
}

// MARK: - Business Hard Negative Models
// These names are intentionally different from HardNegativeUploadService.swift
// to avoid duplicate type declarations.

struct BusinessHardNegativeInitResponse: Decodable {
    let message: String?
    let batchId: String
    let landmarkId: String
    let landmarkLabel: String?
    let landmarkFolder: String?
    let expiresInSeconds: Int?
    let uploads: [BusinessHardNegativeUploadTarget]
}

struct BusinessHardNegativeUploadTarget: Decodable {
    let negativeId: String
    let uploadUrl: String
    let sourceBucket: String?
    let sourceKey: String
    let contentType: String
}

struct BusinessHardNegativeCompleteResponse: Decodable {
    let message: String?
    let landmarkId: String
    let batchId: String
    let processedCount: Int
    let failedCount: Int
}

// MARK: - Request Bodies

private struct PositiveUploadInitBody: Encodable {
    let mediaKind: String
    let datasetRole: String
    let filename: String
    let contentType: String
}

private struct PositiveUploadCompleteBody: Encodable {
    let submissionId: String
    let s3Key: String
}

private struct BusinessLandmarkPatchBody: Encodable {
    let shortDescription: String?
    let isActive: Bool?
    let promotionEnabled: Bool?
}

private struct BusinessLandmarkDeleteBody: Encodable {
    let confirmation: String
}

private struct BusinessHardNegativeFileBody: Encodable {
    let filename: String
    let contentType: String
}

private struct BusinessHardNegativeInitBody: Encodable {
    let files: [BusinessHardNegativeFileBody]
}

private struct BusinessHardNegativeCompleteBody: Encodable {
    let batchId: String
    let negativeIds: [String]
}

// MARK: - Errors

enum BusinessLandmarkServiceError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case badStatus(Int, String)
    case invalidUploadURL
    case noHardNegativeUploadTarget

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "You must be signed in before managing landmarks."
        case .tokensUnavailable:
            return "Cognito tokens were unavailable."
        case .badStatus(let code, let body):
            return "API error \(code): \(body)"
        case .invalidUploadURL:
            return "The upload URL returned by the server was invalid."
        case .noHardNegativeUploadTarget:
            return "The hard-negative upload request did not return an upload target."
        }
    }
}

// MARK: - Service

final class BusinessLandmarkService {
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    private func getCognitoIDToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard session.isSignedIn else {
            throw BusinessLandmarkServiceError.notSignedIn
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            throw BusinessLandmarkServiceError.tokensUnavailable
        }

        let tokens = try tokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    // MARK: - Landmark List

    func fetchBusinessLandmarks() async throws -> BusinessLandmarkListResponse {
        let idToken = try await getCognitoIDToken()
        let url = baseURL.appendingPathComponent("business/landmarks")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let body = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, body)
        }

        return try JSONDecoder().decode(BusinessLandmarkListResponse.self, from: data)
    }

    // MARK: - Landmark Editing / Settings

    func updateShortDescription(
        landmarkId: String,
        shortDescription: String
    ) async throws -> BusinessLandmark {
        return try await patchBusinessLandmark(
            landmarkId: landmarkId,
            body: BusinessLandmarkPatchBody(
                shortDescription: shortDescription,
                isActive: nil,
                promotionEnabled: nil
            )
        )
    }

    func updateLandmarkSettings(
        landmarkId: String,
        isActive: Bool? = nil,
        promotionEnabled: Bool? = nil
    ) async throws -> BusinessLandmark {
        return try await patchBusinessLandmark(
            landmarkId: landmarkId,
            body: BusinessLandmarkPatchBody(
                shortDescription: nil,
                isActive: isActive,
                promotionEnabled: promotionEnabled
            )
        )
    }

    private func patchBusinessLandmark(
        landmarkId: String,
        body: BusinessLandmarkPatchBody
    ) async throws -> BusinessLandmark {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)

        var request = URLRequest(url: url)
        request.httpMethod = "PATCH"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        let decoded = try JSONDecoder().decode(BusinessLandmarkUpdateResponse.self, from: data)
        return decoded.item
    }

    func deleteLandmark(
        landmarkId: String,
        confirmation: String
    ) async throws -> BusinessLandmarkDeleteResponse {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        request.httpBody = try JSONEncoder().encode(
            BusinessLandmarkDeleteBody(
                confirmation: confirmation
            )
        )

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessLandmarkDeleteResponse.self, from: data)
    }

    // MARK: - Unified Media Upload Entry Point

    func uploadBusinessMedia(
        landmarkId: String,
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        filename: String,
        contentType: String,
        data: Data
    ) async throws -> BusinessMediaUploadCompleteResponse {
        switch datasetRole {
        case .positive:
            return try await uploadPositiveBusinessMedia(
                landmarkId: landmarkId,
                datasetRole: datasetRole,
                mediaKind: mediaKind,
                filename: filename,
                contentType: contentType,
                data: data
            )

        case .hardNegative:
            return try await uploadHardNegativeMedia(
                landmarkId: landmarkId,
                filename: filename,
                contentType: contentType,
                data: data
            )
        }
    }

    // MARK: - Positive Business Uploads

    private func uploadPositiveBusinessMedia(
        landmarkId: String,
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        filename: String,
        contentType: String,
        data: Data
    ) async throws -> BusinessMediaUploadCompleteResponse {
        let initResponse = try await initiatePositiveBusinessMediaUpload(
            landmarkId: landmarkId,
            datasetRole: datasetRole,
            mediaKind: mediaKind,
            filename: filename,
            contentType: contentType
        )

        try await uploadToPresignedURL(
            uploadUrl: initResponse.uploadUrl,
            contentType: contentType,
            data: data
        )

        return try await completePositiveBusinessMediaUpload(
            landmarkId: landmarkId,
            submissionId: initResponse.submissionId,
            s3Key: initResponse.s3Key
        )
    }

    private func initiatePositiveBusinessMediaUpload(
        landmarkId: String,
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        filename: String,
        contentType: String
    ) async throws -> BusinessMediaUploadInitResponse {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("uploads")
            .appendingPathComponent("init")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let body = PositiveUploadInitBody(
            mediaKind: mediaKind.rawValue,
            datasetRole: datasetRole.rawValue,
            filename: filename,
            contentType: contentType
        )

        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessMediaUploadInitResponse.self, from: data)
    }

    private func completePositiveBusinessMediaUpload(
        landmarkId: String,
        submissionId: String,
        s3Key: String
    ) async throws -> BusinessMediaUploadCompleteResponse {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("uploads")
            .appendingPathComponent("complete")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let body = PositiveUploadCompleteBody(
            submissionId: submissionId,
            s3Key: s3Key
        )

        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessMediaUploadCompleteResponse.self, from: data)
    }

    // MARK: - Existing Hard Negative Upload Flow

    private func uploadHardNegativeMedia(
        landmarkId: String,
        filename: String,
        contentType: String,
        data: Data
    ) async throws -> BusinessMediaUploadCompleteResponse {
        let initResponse = try await initiateHardNegativeUpload(
            landmarkId: landmarkId,
            filename: filename,
            contentType: contentType
        )

        guard let uploadTarget = initResponse.uploads.first else {
            throw BusinessLandmarkServiceError.noHardNegativeUploadTarget
        }

        try await uploadToPresignedURL(
            uploadUrl: uploadTarget.uploadUrl,
            contentType: uploadTarget.contentType,
            data: data
        )

        let completeResponse = try await completeHardNegativeUpload(
            landmarkId: landmarkId,
            batchId: initResponse.batchId,
            negativeIds: [uploadTarget.negativeId]
        )

        return BusinessMediaUploadCompleteResponse(
            ok: completeResponse.failedCount == 0,
            submissionId: uploadTarget.negativeId,
            status: completeResponse.failedCount == 0 ? "PROCESSING" : "FAILED",
            datasetRole: BusinessDatasetRole.hardNegative.rawValue,
            mediaKind: nil,
            landmarkId: landmarkId,
            s3Key: uploadTarget.sourceKey
        )
    }

    private func initiateHardNegativeUpload(
        landmarkId: String,
        filename: String,
        contentType: String
    ) async throws -> BusinessHardNegativeInitResponse {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("init")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let body = BusinessHardNegativeInitBody(
            files: [
                BusinessHardNegativeFileBody(
                    filename: filename,
                    contentType: contentType
                )
            ]
        )

        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessHardNegativeInitResponse.self, from: data)
    }

    private func completeHardNegativeUpload(
        landmarkId: String,
        batchId: String,
        negativeIds: [String]
    ) async throws -> BusinessHardNegativeCompleteResponse {
        let idToken = try await getCognitoIDToken()

        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("complete")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let body = BusinessHardNegativeCompleteBody(
            batchId: batchId,
            negativeIds: negativeIds
        )

        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessHardNegativeCompleteResponse.self, from: data)
    }

    // MARK: - Shared S3 Upload Helper

    private func uploadToPresignedURL(
        uploadUrl: String,
        contentType: String,
        data: Data
    ) async throws {
        guard let url = URL(string: uploadUrl) else {
            throw BusinessLandmarkServiceError.invalidUploadURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let (responseData, response) = try await URLSession.shared.upload(
            for: request,
            from: data
        )

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: responseData, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }
    }
}
