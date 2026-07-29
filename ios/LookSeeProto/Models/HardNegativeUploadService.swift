//
//  HardNegativeUploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/16/26.
//

import Foundation
import Combine

@MainActor
final class HardNegativeUploadService: ObservableObject {
    @Published private(set) var status: String = String(localized: "Idle")
    @Published private(set) var progress: Double = 0
    @Published private(set) var isUploading = false

    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

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
            status = String(localized: "Negative video uploaded ✅")

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

    private func initializeUpload(landmarkId: String, token: String, video: CapturedNegativeVideo) async throws -> HardNegativeInitResponse {
        let url = baseURL.appendingPathComponent("landmarks").appendingPathComponent(landmarkId).appendingPathComponent("hard-negatives").appendingPathComponent("init")
        let body = HardNegativeInitRequest(files: [HardNegativeFileRequest(filename: video.filename, contentType: "video/quicktime")])

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        try validateAPIResponse(response, data: data)

        return try JSONDecoder().decode(HardNegativeInitResponse.self, from: data)
    }

    // MARK: - Presigned S3 uploads

    private func uploadVideo(_ video: CapturedNegativeVideo, to target: HardNegativeUploadTarget) async throws {
        guard FileManager.default.fileExists(atPath: video.fileURL.path) else {
            throw UploadError.missingLocalFile(video.filename)
        }
        guard let url = URL(string: target.uploadUrl) else {
            throw UploadError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(target.contentType, forHTTPHeaderField: "Content-Type")

        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: video.fileURL)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw UploadError.badStatus(httpResponse.statusCode, "S3 PUT failed for \(video.filename)")
        }
    }

    // MARK: - Complete

    private func completeUpload(landmarkId: String, batchId: String, negativeIds: [String], token: String) async throws -> HardNegativeCompleteResponse {
        let url = baseURL.appendingPathComponent("landmarks").appendingPathComponent(landmarkId).appendingPathComponent("hard-negatives").appendingPathComponent("complete")
        let body = HardNegativeCompleteRequest(batchId: batchId, negativeIds: negativeIds)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
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
}

private struct HardNegativeFileRequest: Encodable {
    let filename: String
    let contentType: String
}

private struct HardNegativeInitResponse: Decodable {
    let message: String
    let batchId: String
    let landmarkId: String
    let landmarkLabel: String
    let landmarkFolder: String
    let expiresInSeconds: Int
    let uploads: [HardNegativeUploadTarget]
}

private struct HardNegativeUploadTarget: Decodable {
    let negativeId: String
    let uploadUrl: String
    let sourceBucket: String
    let sourceKey: String
    let contentType: String
}

// MARK: - Complete models

private struct HardNegativeCompleteRequest: Encodable {
    let batchId: String
    let negativeIds: [String]
}

struct HardNegativeCompleteResponse: Decodable {
    let message: String
    let landmarkId: String
    let batchId: String
    let processedCount: Int
    let failedCount: Int
    let dirtyMarked: Bool
    let processed: [HardNegativeProcessedItem]
    let failed: [HardNegativeFailedItem]
}

struct HardNegativeProcessedItem: Decodable {
    let negativeId: String
    let status: String
    let datasetImageKey: String
    let datasetLabelKey: String
}

struct HardNegativeFailedItem: Decodable {
    let negativeId: String
    let reason: String
}
