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
    @Published private(set) var status: String = "Idle"
    @Published private(set) var progress: Double = 0
    @Published private(set) var isUploading = false

    private let baseURL = URL(
        string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"
    )!

    enum UploadError: LocalizedError {
        case noPhotos
        case tooManyPhotos
        case invalidURL
        case invalidResponse
        case badStatus(Int, String)
        case responseCountMismatch(expected: Int, received: Int)
        case missingLocalFile(String)
        case incompleteUpload(processed: Int, failed: Int)

        var errorDescription: String? {
            switch self {
            case .noPhotos:
                return "No negative photos were provided."

            case .tooManyPhotos:
                return "A maximum of 25 negative photos can be uploaded in one batch."

            case .invalidURL:
                return "The upload service returned an invalid URL."

            case .invalidResponse:
                return "The server returned an invalid response."

            case .badStatus(let code, let body):
                return "HTTP \(code): \(body)"

            case .responseCountMismatch(let expected, let received):
                return "Expected \(expected) upload URLs, but received \(received)."

            case .missingLocalFile(let filename):
                return "The local photo could not be found: \(filename)"

            case .incompleteUpload(let processed, let failed):
                return "Only \(processed) photos completed successfully; \(failed) failed."
            }
        }
    }

    // MARK: - Public upload flow

    func upload(
        landmarkId: String,
        photos: [CapturedNegativePhoto]
    ) async throws -> HardNegativeCompleteResponse {
        guard !photos.isEmpty else {
            throw UploadError.noPhotos
        }

        guard photos.count <= 25 else {
            throw UploadError.tooManyPhotos
        }

        isUploading = true
        progress = 0
        status = "Preparing negative photos…"

        defer {
            isUploading = false
        }

        do {
            let initResponse = try await initializeUpload(
                landmarkId: landmarkId,
                photos: photos
            )

            guard initResponse.uploads.count == photos.count else {
                throw UploadError.responseCountMismatch(
                    expected: photos.count,
                    received: initResponse.uploads.count
                )
            }

            progress = 0.1

            for (index, pair) in zip(
                photos,
                initResponse.uploads
            ).enumerated() {
                let photo = pair.0
                let uploadTarget = pair.1

                status = "Uploading negative photo \(index + 1) of \(photos.count)…"

                try await uploadPhoto(
                    photo,
                    to: uploadTarget
                )

                let uploadedFraction =
                    Double(index + 1) / Double(photos.count)

                progress = 0.1 + (uploadedFraction * 0.75)
            }

            status = "Finalizing negative photos…"

            let completeResponse = try await completeUpload(
                landmarkId: landmarkId,
                batchId: initResponse.batchId,
                negativeIds: initResponse.uploads.map(\.negativeId)
            )

            guard completeResponse.failedCount == 0,
                  completeResponse.processedCount == photos.count else {
                throw UploadError.incompleteUpload(
                    processed: completeResponse.processedCount,
                    failed: completeResponse.failedCount
                )
            }

            progress = 1
            status = "Negative photos uploaded ✅"

            return completeResponse

        } catch {
            status = "Negative upload failed: \(error.localizedDescription)"
            print("❌ Hard-negative upload failed:", error)
            throw error
        }
    }

    func reset() {
        status = "Idle"
        progress = 0
        isUploading = false
    }

    // MARK: - Init

    private func initializeUpload(
        landmarkId: String,
        photos: [CapturedNegativePhoto]
    ) async throws -> HardNegativeInitResponse {
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("init")

        let body = HardNegativeInitRequest(
            files: photos.map {
                HardNegativeFileRequest(
                    filename: $0.filename,
                    contentType: "image/jpeg"
                )
            }
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Content-Type"
        )
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(
            for: request
        )

        try validateAPIResponse(
            response,
            data: data
        )

        return try JSONDecoder().decode(
            HardNegativeInitResponse.self,
            from: data
        )
    }

    // MARK: - Presigned S3 uploads

    private func uploadPhoto(
        _ photo: CapturedNegativePhoto,
        to target: HardNegativeUploadTarget
    ) async throws {
        guard FileManager.default.fileExists(
            atPath: photo.fileURL.path
        ) else {
            throw UploadError.missingLocalFile(
                photo.filename
            )
        }

        guard let url = URL(
            string: target.uploadUrl
        ) else {
            throw UploadError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"

        // This must exactly match the content type signed by Lambda.
        request.setValue(
            target.contentType,
            forHTTPHeaderField: "Content-Type"
        )

        let (_, response) = try await URLSession.shared.upload(
            for: request,
            fromFile: photo.fileURL
        )

        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw UploadError.badStatus(
                httpResponse.statusCode,
                "S3 PUT failed for \(photo.filename)"
            )
        }
    }

    // MARK: - Complete

    private func completeUpload(
        landmarkId: String,
        batchId: String,
        negativeIds: [String]
    ) async throws -> HardNegativeCompleteResponse {
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("hard-negatives")
            .appendingPathComponent("complete")

        let body = HardNegativeCompleteRequest(
            batchId: batchId,
            negativeIds: negativeIds
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Content-Type"
        )
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(
            for: request
        )

        // This intentionally accepts 207 Multi-Status. We inspect
        // failedCount after decoding it.
        try validateAPIResponse(
            response,
            data: data
        )

        return try JSONDecoder().decode(
            HardNegativeCompleteResponse.self,
            from: data
        )
    }

    private func validateAPIResponse(
        _ response: URLResponse,
        data: Data
    ) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw UploadError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let body = String(
                data: data,
                encoding: .utf8
            ) ?? ""

            throw UploadError.badStatus(
                httpResponse.statusCode,
                body
            )
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
