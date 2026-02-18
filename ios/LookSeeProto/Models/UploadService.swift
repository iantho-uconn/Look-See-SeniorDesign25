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

    // Later set this to our API Gateway base URL
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    enum UploadError: LocalizedError {
            case invalidURL
            case badStatus(Int, String)
            case noData

            var errorDescription: String? {
                switch self {
                case .invalidURL: return "Invalid URL."
                case .badStatus(let code, let body): return "HTTP \(code): \(body)"
                case .noData: return "No response data."
                }
            }
        }

    func upload(label: String, videoURL: URL?, image: UIImage?) async {
        progress = 0
        status = "Preparing…"

        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            status = "Label is required."
            return
        }

    // Decide media + filename + contentType + size
    let req: InitSubmissionRequest
        if let videoURL {
            let filename = videoURL.lastPathComponent.isEmpty ? "submission.mov" : videoURL.lastPathComponent
            req = InitSubmissionRequest(
                label: trimmed,
                mediaKind: .video,
                filename: filename,
                contentType: "video/quicktime"
            )
        } else if image != nil {
            req = InitSubmissionRequest(
                label: trimmed,
                mediaKind: .photo,
                filename: "submission.jpg",
                contentType: "image/jpeg"
            )
        } else {
            status = "No media selected."
            return
        }

        do {
            status = "Calling /submissions/init…"
            let initResp = try await initSubmission(req)

            // ✅ This is the A2 “success” signal
            status = "Init OK. submissionId=\(initResp.submissionId)"
            print("✅ INIT response:", initResp)

            // Next will use initResp.uploadUrl to PUT to S3
        } catch {
            status = "Init failed: \(error.localizedDescription)"
            print("❌ INIT failed:", error)
        }
    }
    
    
    private func initSubmission(_ reqBody: InitSubmissionRequest) async throws -> InitSubmissionResponse {
            let url = baseURL.appendingPathComponent("submissions/init")

            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")

            // Later: add auth header + Bearer token from Cognito
            
            // from their website:
            // req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

            let encoder = JSONEncoder()
            req.httpBody = try encoder.encode(reqBody)

            let (data, resp) = try await URLSession.shared.data(for: req)
            let http = resp as? HTTPURLResponse

            let bodyStr = String(data: data, encoding: .utf8) ?? ""

            guard let http else {
                throw UploadError.noData
            }

            guard (200...299).contains(http.statusCode) else {
                throw UploadError.badStatus(http.statusCode, bodyStr)
            }

            let decoder = JSONDecoder()
            return try decoder.decode(InitSubmissionResponse.self, from: data)
        }
}
