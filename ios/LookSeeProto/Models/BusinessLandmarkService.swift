//
//  BusinessLandmarkService.swift
//  LookSeeProto
//
//  Handles authenticated business landmark management APIs.
//

import Foundation
import Amplify
import AWSPluginsCore
import CryptoKit

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

struct BusinessLandmark: Codable, Identifiable, Hashable {
    let landmarkId: String
    let label: String
    let shortDescription: String?
    let websiteUrl: String?
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
    
    // Status and Media Requirement Fields
    let status: String?
    let cleanFrameCount: Int?
    let requiredFrames: Int?
    let secondsNeeded: Int?

    var id: String { landmarkId }

    var displayDescription: String {
        let value = shortDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? "No description available." : value
    }

    var displayStatus: String {
        switch status {
        case "NEEDS_MORE_MEDIA": return "Action Needed"
        case "PREPARING_DATA": return "Preparing Data"
        case "PENDING_TRAINING": return "Waiting for Training"
        case "TRAINING_MODEL": return "In Training"
        case "OPTIMIZING_MODEL": return "Optimizing for iOS"
        default: return isActive == false ? "Inactive" : "Active"
        }
    }

    var isProcessing: Bool {
        return status == "PREPARING_DATA" || status == "PENDING_TRAINING" || status == "TRAINING_MODEL" || status == "OPTIMIZING_MODEL"
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
        case .positive: return "Positive Media"
        case .hardNegative: return "Negative Examples"
        }
    }

    var successMessage: String {
        switch self {
        case .positive: return "Positive media uploaded successfully."
        case .hardNegative: return "Negative example uploaded successfully."
        }
    }

    var filenameComponent: String {
        switch self {
        case .positive: return "positive"
        case .hardNegative: return "hard_negative"
        }
    }
}

enum BusinessMediaKind: String {
    case photo = "photo"
    case video = "video"
}

// Business routes may return a signed PUT URL or a signed POST form.
// Keep the transport matched to the format issued by the server.
enum BusinessMediaUploadTarget: Decodable {
    case put(String)
    case post(S3PresignedPost)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let url = try? container.decode(String.self) {
            self = .put(url)
        } else {
            self = .post(try container.decode(S3PresignedPost.self))
        }
    }
}

struct BusinessMediaUploadInitResponse: Decodable {
    let submissionId: String
    //let uploadUrl: S3PresignedPost
    let uploadUrl: BusinessMediaUploadTarget
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
    //let uploadUrl: BusinessMediaUploadTarget
    let uploadUrl: S3PresignedPost
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
    let processed: [BusinessHardNegativeProcessedItem]?
}

struct BusinessHardNegativeProcessedItem: Decodable {
    let negativeId: String
    let status: String
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
    let websiteUrl: String?
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
    let forceRetry: Bool?
}

// MARK: - Errors

enum BusinessLandmarkServiceError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case badStatus(Int, String)
    case backendRejected(String)
    case invalidUploadURL
    case noHardNegativeUploadTarget
    case hardNegativeRetryFailed

    var errorDescription: String? {
        switch self {
        case .notSignedIn: return "You must be signed in before managing landmarks."
        case .tokensUnavailable: return "Cognito tokens were unavailable."
        case .badStatus(let code, let body): return "API error \(code): \(body)"
        case .backendRejected(let message): return message
        case .invalidUploadURL: return "The upload URL returned by the server was invalid."
        case .noHardNegativeUploadTarget: return "The hard-negative upload request did not return an upload target."
        case .hardNegativeRetryFailed: return "The negative media could not be queued for processing again."
        }
    }
}

// MARK: - Service

final class BusinessLandmarkService {
    private let baseURL = URL(string: "https://d11vl3v9w133rh.cloudfront.net")!

    static let shared = BusinessLandmarkService()
    
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

    // 🚀 NEW: Centralized Request Builder for App Attest Support
    private func authorizedRequest(url: URL, method: String, body: Data? = nil) async throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        let idToken = try await getCognitoIDToken()
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        
        let payload = body ?? Data()
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        
        // Inject App Attest signature
        do {
            let assertionToken = try await AppAttestService.shared.generateAssertion(for: payload)
            let payloadHash = Data(SHA256.hash(data: payload)).base64EncodedString()
            request.setValue(assertionToken, forHTTPHeaderField: "X-LookSee-App-Attest")
            request.setValue(payloadHash, forHTTPHeaderField: "X-LookSee-App-Attest-Payload-Hash")
        } catch {
            print("⚠️ App Attest bypassed or failed: \(error.localizedDescription)")
        }
        
        return request
    }

    // MARK: - Landmark List

    func fetchBusinessLandmarks() async throws -> BusinessLandmarkListResponse {
        let url = baseURL.appendingPathComponent("business/landmarks")
        let request = try await authorizedRequest(url: url, method: "GET")

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
                websiteUrl: nil,
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
                websiteUrl: nil,
                isActive: isActive,
                promotionEnabled: promotionEnabled
            )
        )
    }
    
    func updateWebsiteUrl(
        landmarkId: String,
        websiteUrl: String
    ) async throws -> BusinessLandmark {
        return try await patchBusinessLandmark(
            landmarkId: landmarkId,
            body: BusinessLandmarkPatchBody(
                shortDescription: nil,
                websiteUrl: websiteUrl,
                isActive: nil,
                promotionEnabled: nil
            )
        )
    }

    private func patchBusinessLandmark(
        landmarkId: String,
        body: BusinessLandmarkPatchBody
    ) async throws -> BusinessLandmark {
        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)

        let requestBody = try JSONEncoder().encode(body)
        let request = try await authorizedRequest(url: url, method: "PATCH", body: requestBody)

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
        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)

        let requestBody = try JSONEncoder().encode(BusinessLandmarkDeleteBody(confirmation: confirmation))
        let request = try await authorizedRequest(url: url, method: "DELETE", body: requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        let decoded = try JSONDecoder().decode(BusinessLandmarkDeleteResponse.self, from: data)
        
        if !decoded.ok {
            let errorMsg = decoded.message ?? "Backend refused to delete landmark."
            throw BusinessLandmarkServiceError.backendRejected(errorMsg)
        }
        
        return decoded
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
            filename: filename,
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
        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("uploads")
            .appendingPathComponent("init")

        let body = PositiveUploadInitBody(
            mediaKind: mediaKind.rawValue,
            datasetRole: datasetRole.rawValue,
            filename: filename,
            contentType: contentType
        )

        let requestBody = try JSONEncoder().encode(body)
        let request = try await authorizedRequest(url: url, method: "POST", body: requestBody)

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
        let url = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("uploads")
            .appendingPathComponent("complete")

        let body = PositiveUploadCompleteBody(
            submissionId: submissionId,
            s3Key: s3Key
        )

        let requestBody = try JSONEncoder().encode(body)
        let request = try await authorizedRequest(url: url, method: "POST", body: requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessMediaUploadCompleteResponse.self, from: data)
    }
    
    // MARK: - Global Negatives Uploader
    func uploadGlobalNegativeVideo(fileURL: URL) async throws {
        print("🚀 [GLOBAL NEGATIVE] Initiating network request for: \(fileURL.lastPathComponent)")
        let fileName = fileURL.lastPathComponent
        
        let initUrl = baseURL
            .appendingPathComponent("submissions")
            .appendingPathComponent("init")
        
        let initPayload: [String: Any] = [
            "filename": fileName,
            "mediaKind": "video",
            "contentType": "video/quicktime",
            "datasetRole": "global_negative",
            "label": "Global Negative Admin"
        ]
        
        let requestBody = try JSONSerialization.data(withJSONObject: initPayload)
        let initRequest = try await authorizedRequest(url: initUrl, method: "POST", body: requestBody)
        
        let (initData, initResponse) = try await URLSession.shared.data(for: initRequest)
        let initStatusCode = (initResponse as? HTTPURLResponse)?.statusCode ?? -1
        let initBody = String(data: initData, encoding: .utf8) ?? ""
        
        guard (200...299).contains(initStatusCode) else {
            throw BusinessLandmarkServiceError.badStatus(initStatusCode, initBody)
        }
        
        guard let json = try JSONSerialization.jsonObject(with: initData) as? [String: Any],
              let uploadUrlDict = json["uploadUrl"] as? [String: Any],
              let urlString = uploadUrlDict["url"] as? String,
              let fields = uploadUrlDict["fields"] as? [String: String],
              let s3Key = json["s3Key"] as? String,
              let submissionId = json["submissionId"] as? String else {
            print("❌ [GLOBAL NEGATIVE] Failed to parse S3 URL from JSON.")
            throw BusinessLandmarkServiceError.invalidUploadURL
        }
        
        print("🚀 [GLOBAL NEGATIVE] S3 URL Received. Uploading video data...")
        
        let presignedPost = S3PresignedPost(url: urlString, fields: fields)
        let videoData = try Data(contentsOf: fileURL)
        
        try await uploadToPresignedURL(
            uploadUrl: presignedPost,
            contentType: "video/quicktime",
            filename: fileName,
            data: videoData
        )
        
        print("🚀 [GLOBAL NEGATIVE] S3 Upload Finished! Notifying API to complete...")
        let completeUrl = baseURL
            .appendingPathComponent("submissions")
            .appendingPathComponent("complete")
            
        let completePayload: [String: Any] = [
            "submissionId": submissionId,
            "s3Key": s3Key,
            "datasetRole": "global_negative"
        ]
        
        let completeReqBody = try JSONSerialization.data(withJSONObject: completePayload)
        let completeRequest = try await authorizedRequest(url: completeUrl, method: "POST", body: completeReqBody)
        
        let (completeData, completeNetResponse) = try await URLSession.shared.data(for: completeRequest)
        let completeStatusCode = (completeNetResponse as? HTTPURLResponse)?.statusCode ?? -1
        
        guard (200...299).contains(completeStatusCode) else {
            throw BusinessLandmarkServiceError.badStatus(completeStatusCode, String(data: completeData, encoding: .utf8) ?? "")
        }
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
            filename: filename,
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
            status: completeResponse.failedCount == 0
                ? (completeResponse.processed?.first?.status ?? "PROCESSING")
                : "FAILED",
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
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("init")

        let body = BusinessHardNegativeInitBody(
            files: [
                BusinessHardNegativeFileBody(
                    filename: filename,
                    contentType: contentType
                )
            ]
        )

        let requestBody = try JSONEncoder().encode(body)
        let request = try await authorizedRequest(url: url, method: "POST", body: requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessHardNegativeInitResponse.self, from: data)
    }

    func retryHardNegativeProcessing(
        landmarkId: String,
        batchId: String,
        negativeId: String
    ) async throws -> BusinessHardNegativeCompleteResponse {
        let response = try await completeHardNegativeUpload(
            landmarkId: landmarkId,
            batchId: batchId,
            negativeIds: [negativeId],
            forceRetry: true
        )

        guard response.failedCount == 0,
            response.processedCount == 1 else {
            throw BusinessLandmarkServiceError.hardNegativeRetryFailed
        }

        return response
    }

    private func completeHardNegativeUpload(
        landmarkId: String,
        batchId: String,
        negativeIds: [String],
        forceRetry: Bool = false
    ) async throws -> BusinessHardNegativeCompleteResponse {
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("complete")

        let body = BusinessHardNegativeCompleteBody(
            batchId: batchId,
            negativeIds: negativeIds,
            forceRetry: forceRetry ? true : nil
        )

        let requestBody = try JSONEncoder().encode(body)
        let request = try await authorizedRequest(url: url, method: "POST", body: requestBody)

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessHardNegativeCompleteResponse.self, from: data)
    }

    // MARK: - Shared S3 Upload Helpers

    private func uploadToPresignedURL(
        uploadUrl: BusinessMediaUploadTarget,
        contentType: String,
        filename: String,
        data: Data
    ) async throws {
        switch uploadUrl {
        case .post(let form):
            try await uploadToPresignedURL(
                uploadUrl: form,
                contentType: contentType,
                filename: filename,
                data: data
            )
        case .put(let urlString):
            guard let url = URL(string: urlString),
                  url.scheme?.lowercased() == "https",
                  let host = url.host, !host.isEmpty else {
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
            guard (200...299).contains(statusCode) else {
                let responseBody = String(data: responseData, encoding: .utf8) ?? ""
                throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
            }
        }
    }

    private func uploadToPresignedURL(
        uploadUrl: S3PresignedPost,
        contentType: String,
        filename: String,
        data: Data
    ) async throws {
        guard let url = URL(string: uploadUrl.url) else {
            throw BusinessLandmarkServiceError.invalidUploadURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        
        for (key, value) in uploadUrl.fields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }

        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(contentType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        let (responseData, response) = try await URLSession.shared.upload(
            for: request,
            from: body
        )

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: responseData, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, responseBody)
        }
    }
}
