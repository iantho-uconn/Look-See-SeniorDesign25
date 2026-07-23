//
//  BusinessMediaHistoryModels.swift
//  LookSeeProto
//
//  Models returned by the landmark media-history API.
//

import Foundation

struct BusinessMediaHistoryResponse: Decodable {
    let landmarkId: String
    let landmarkLabel: String
    let items: [BusinessMediaHistoryItem]
    let count: Int
    let nextToken: String?
}

struct BusinessMediaHistoryUploader: Decodable, Hashable {
    let displayName: String?
    let email: String?
    let userId: String?

    var displayText: String {
        let cleanedDisplayName = displayName?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !cleanedDisplayName.isEmpty {
            return cleanedDisplayName
        }

        let cleanedEmail = email?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !cleanedEmail.isEmpty {
            return cleanedEmail
        }

        let cleanedUserId = userId?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        return cleanedUserId.isEmpty ? "Unknown uploader" : cleanedUserId
    }
}

struct BusinessMediaHistoryItem: Decodable, Identifiable, Hashable {
    let id: String
    let submissionId: String
    let batchId: String?
    let datasetRole: String
    let mediaKind: String
    let originalFilename: String?
    let contentType: String?
    let status: String?
    let uploadedBy: BusinessMediaHistoryUploader
    let uploadedAt: Int64
    let uploadedAtISO: String?
    let thumbnailUrl: URL?
    let thumbnailSource: String?

    var isPositive: Bool {
        datasetRole.lowercased() == "positive"
    }

    var isVideo: Bool {
        mediaKind.lowercased() == "video"
    }

    var roleTitle: String {
        isPositive ? "Positive" : "Negative"
    }

    var mediaTitle: String {
        isVideo ? "Video" : "Image"
    }

    var roleAndMediaTitle: String {
        "\(roleTitle) • \(mediaTitle)"
    }

    var mediaSystemImage: String {
        isVideo ? "video.fill" : "photo.fill"
    }

    var normalizedStatus: String {
        let cleaned = status?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        guard !cleaned.isEmpty else {
            return "Unknown"
        }

        return cleaned
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .lowercased()
            .capitalized
    }

    var uploadDate: Date {
        Date(timeIntervalSince1970: TimeInterval(uploadedAt))
    }

    var displayFilename: String {
        let cleaned = originalFilename?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        return cleaned.isEmpty ? "Unnamed media" : cleaned
    }
}
