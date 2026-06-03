//
//  UploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//  Updated: include userEmail + landmarkId + landmarkLabel in init/complete
//

import Foundation
import UIKit
import Combine

@MainActor
final class UploadService: ObservableObject {
    @Published var status: String = "Idle"
    @Published var progress: Double = 0.0

    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    enum UploadError: LocalizedError {
        case invalidURL
        case badStatus(Int, String)
        case noData
        case missingImageData

        var errorDescription: String? {
            switch self {
            case .invalidURL: return "Invalid URL."
            case .badStatus(let code, let body): return "HTTP \(code): \(body)"
            case .noData: return "No response data."
            case .missingImageData: return "Could not encode image as JPEG."
            }
        }
    }

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
    ) async {
        progress = 0
        status = "Preparing…"

        let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedLabel.isEmpty else {
            status = "Label is required."
            return
        }

        let trimmedUserEmail = userEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedUserEmail.isEmpty else {
            status = "User email is required."
            return
        }

        let trimmedShort = shortDescription?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUser = userDescription?.trimmingCharacters(in: .whitespacesAndNewlines)

        let mediaKind: MediaKind
        let filename: String
        let contentType: String

        if videoURL != nil {
            mediaKind = .video
            filename = "video.mov"
            contentType = "video/quicktime"
        } else if image != nil {
            mediaKind = .photo
            filename = "photo.jpg"
            contentType = "image/jpeg"
        } else {
            status = "No media selected."
            return
        }

        let initReq = InitSubmissionRequest(
            userEmail: trimmedUserEmail,
            label: trimmedLabel,
            mediaKind: mediaKind,
            filename: filename,
            contentType: contentType
        )

        do {
            status = "Calling /submissions/init…"
            let initResp = try await initSubmission(initReq)
            progress = 0.15
            status = "Init OK. submissionId=\(initResp.submissionId)"
            print("✅ INIT response:", initResp)

            status = "Uploading to S3…"
            try await putToS3(
                presignedUrl: initResp.uploadUrl,
                contentType: contentType,
                videoURL: videoURL,
                image: image
            )
            progress = 0.85
            status = "Uploaded to S3. Finalizing…"

            let completeReq = CompleteSubmissionRequest(
                submissionId: initResp.submissionId,
                s3Key: initResp.s3Key,
                userEmail: trimmedUserEmail,
                label: trimmedLabel,
                landmarkId: landmarkId,
                landmarkLabel: landmarkLabel,
                mediaKind: mediaKind,
                shortDescription: (trimmedShort?.isEmpty == true ? nil : trimmedShort),
                userDescription: (trimmedUser?.isEmpty == true ? nil : trimmedUser),
                latitude: latitude,
                longitude: longitude,
                horizontalAccuracy: horizontalAccuracy
            )

            try await completeSubmission(completeReq)
            progress = 1.0
            status = "Complete ✅ (submissionId=\(initResp.submissionId))"

        } catch {
            status = "Upload failed: \(error.localizedDescription)"
            print("❌ Upload failed:", error)
        }
    }

    private func initSubmission(_ reqBody: InitSubmissionRequest) async throws -> InitSubmissionResponse {
        let url = baseURL.appendingPathComponent("submissions/init")

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(reqBody)

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UploadError.noData }

        let bodyStr = String(data: data, encoding: .utf8) ?? ""
        guard (200...299).contains(http.statusCode) else {
            throw UploadError.badStatus(http.statusCode, bodyStr)
        }

        return try JSONDecoder().decode(InitSubmissionResponse.self, from: data)
    }

    private func putToS3(
        presignedUrl: String,
        contentType: String,
        videoURL: URL?,
        image: UIImage?
    ) async throws {
        guard let url = URL(string: presignedUrl) else { throw UploadError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")

        if let videoURL {
            let (_, resp) = try await URLSession.shared.upload(for: req, fromFile: videoURL)
            try validateS3PutResponse(resp)
        } else if let image {
            guard let data = image.jpegData(compressionQuality: 0.9) else {
                throw UploadError.missingImageData
            }
            let (_, resp) = try await URLSession.shared.upload(for: req, from: data)
            try validateS3PutResponse(resp)
        } else {
            throw UploadError.noData
        }
    }

    private func validateS3PutResponse(_ resp: URLResponse) throws {
        guard let http = resp as? HTTPURLResponse else { throw UploadError.noData }
        if http.statusCode == 200 || http.statusCode == 204 { return }
        throw UploadError.badStatus(http.statusCode, "S3 PUT failed")
    }

    private func completeSubmission(_ body: CompleteSubmissionRequest) async throws {
        let url = baseURL.appendingPathComponent("submissions/complete")

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(body)

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UploadError.noData }

        let bodyStr = String(data: data, encoding: .utf8) ?? ""
        guard (200...299).contains(http.statusCode) else {
            throw UploadError.badStatus(http.statusCode, bodyStr)
        }
    }
}
