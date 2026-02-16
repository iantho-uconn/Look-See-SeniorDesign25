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
    private let baseURL = URL(string: "https://YOUR_API_ID.execute-api.YOUR_REGION.amazonaws.com")!

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
                let initResp = try await InitSubmissionResponse(req)

                // ✅ This is the A2 “success” signal
                status = "Init OK. submissionId=\(initResp.submissionId)"
                print("✅ INIT response:", initResp)

                // Next checkpoint A3 will use initResp.uploadUrl to PUT to S3
            } catch {
                status = "Init failed: \(error.localizedDescription)"
                print("❌ INIT failed:", error)
            }
        }
}
