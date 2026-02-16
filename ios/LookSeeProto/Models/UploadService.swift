//
//  UploadService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//

import Foundation
import UIKit

@MainActor
final class UploadService: ObservableObject {
    @Published var status: String = "Idle"
    @Published var progress: Double = 0.0  // 0..1

    // Later you’ll set this to your API Gateway base URL
    private let baseURL = URL(string: "https://YOUR_API_ID.execute-api.YOUR_REGION.amazonaws.com")!

    func upload(label: String, videoURL: URL?, image: UIImage?) async {
        progress = 0
        status = "Preparing upload…"

        // Decide media + filename + contentType + size
        if let videoURL {
            let filename = videoURL.lastPathComponent.isEmpty ? "submission.mov" : videoURL.lastPathComponent
            let contentType = "video/quicktime" // if your device outputs .mov
            let size = (try? FileManager.default.attributesOfItem(atPath: videoURL.path)[.size] as? NSNumber)?.intValue ?? -1
            status = "Ready (video). Size: \(size) bytes. Label: \(label)"
            print("STUB init request:", InitSubmissionRequest(label: label, mediaKind: .video, filename: filename, contentType: contentType))
            return
        }

        if let image {
            let filename = "submission.jpg"
            let contentType = "image/jpeg"
            let jpeg = image.jpegData(compressionQuality: 0.9)
            status = "Ready (photo). Bytes: \(jpeg?.count ?? 0). Label: \(label)"
            print("STUB init request:", InitSubmissionRequest(label: label, mediaKind: .photo, filename: filename, contentType: contentType))
            return
        }

        status = "No media to upload."
    }
}
