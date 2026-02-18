//
//  UploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//

import Foundation
import UIKit
import Combine

@MainActor
final class UploadService: ObservableObject {
    @Published var status: String = "Idle"
    @Published var progress: Double = 0.0  // 0..1

    // API Gateway invoke URL (includes /dev stage)
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

    // End-to-end:
    // 1) POST /submissions/init
    // 2) PUT to S3 presigned URL
    // 3) POST /submissions/complete
    
    func upload(label: String, videoURL: URL?, image: UIImage?) async {
        progress = 0
        status = "Preparing…"

        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            status = "Label is required."
            return
        }

        // Decide media + filename + contentType
        let mediaKind: MediaKind
        let filename: String
        let contentType: String

        if let videoURL {
            mediaKind = .video
            filename = videoURL.lastPathComponent.isEmpty ? "submission.mov" : videoURL.lastPathComponent
            contentType = "video/quicktime"
        } else if image != nil {
            mediaKind = .photo
            filename = "submission.jpg"
            contentType = "image/jpeg"
        } else {
            status = "No media selected."
            return
        }

        let req = InitSubmissionRequest(
            label: trimmed,
            mediaKind: mediaKind,
            filename: filename,
            contentType: contentType
        )

        do {
            // ---- Init Submission ----
            status = "Calling /submissions/init…"
            let initResp = try await initSubmission(req)
            progress = 0.15
            status = "Init OK. submissionId=\(initResp.submissionId)"
            print("✅ INIT response:", initResp)

            // ---- PUT req to S3 ----
            status = "Uploading to S3…"
            try await putToS3(
                presignedUrl: initResp.uploadUrl,
                contentType: contentType,
                videoURL: videoURL,
                image: image
            )
            progress = 0.85
            status = "Uploaded to S3. Finalizing…"

            // ---- Complete upload ----
            try await completeSubmission(submissionId: initResp.submissionId, s3Key: initResp.s3Key)
            progress = 1.0
            status = "Complete ✅ (submissionId=\(initResp.submissionId))"

        } catch {
            status = "Upload failed: \(error.localizedDescription)"
            print("❌ Upload failed:", error)
        }
    }

    // Init Submission code to endpoint helper func

    private func initSubmission(_ reqBody: InitSubmissionRequest) async throws -> InitSubmissionResponse {
        let url = baseURL.appendingPathComponent("submissions/init")

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let encoder = JSONEncoder()
        req.httpBody = try encoder.encode(reqBody)

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UploadError.noData }

        let bodyStr = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(http.statusCode) else {
            throw UploadError.badStatus(http.statusCode, bodyStr)
        }

        let decoder = JSONDecoder()
        return try decoder.decode(InitSubmissionResponse.self, from: data)
    }

    // PUT req to S3 code helper func

    private func putToS3(presignedUrl: String,
                         contentType: String,
                         videoURL: URL?,
                         image: UIImage?) async throws {

        guard let url = URL(string: presignedUrl) else { throw UploadError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")

        // NOTE: do NOT add Authorization headers; presigned URL already contains auth.

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

        // S3 commonly returns 200 or 204 on PUT
        if http.statusCode == 200 || http.statusCode == 204 {
            return
        }

        throw UploadError.badStatus(http.statusCode, "S3 PUT failed")
    }

    // Complete upload code func

    private func completeSubmission(submissionId: String, s3Key: String) async throws {
        let url = baseURL.appendingPathComponent("submissions/complete")

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // Send minimal body (matches our lambda expectation)
        let body: [String: String] = [
            "submissionId": submissionId,
            "s3Key": s3Key
        ]

        req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UploadError.noData }

        let bodyStr = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(http.statusCode) else {
            throw UploadError.badStatus(http.statusCode, bodyStr)
        }
    }
}
