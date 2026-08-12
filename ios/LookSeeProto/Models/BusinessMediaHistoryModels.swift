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
        for candidate in [displayName, email, userId] {
            let cleaned = candidate?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !cleaned.isEmpty {
                return cleaned
            }
        }
        return "Unknown uploader"
    }
}

enum BusinessMediaLifecycleState: String, Hashable {
    case processing
    case ready
    case failed
    case unknown

    init(backendValue: String?) {
        let normalized = Self.normalize(backendValue)

        switch normalized {
        case "ready", "complete", "completed", "success", "succeeded":
            self = .ready
        case "processing", "retrying", "pending", "upload pending", "initiated", "uploaded":
            self = .processing
        case "failed", "failure", "error", "rejected":
            self = .failed
        default:
            self = .unknown
        }
    }

    var displayTitle: String {
        switch self {
        case .processing: return "Processing"
        case .ready: return "Ready"
        case .failed: return "Failed"
        case .unknown: return "Unknown"
        }
    }

    private static func normalize(_ value: String?) -> String {
        value?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .lowercased() ?? ""
    }
}

enum BusinessMediaHistoryRole: Hashable {
    case positive
    case hardNegative
    case unknown(String)

    init(backendValue: String) {
        let cleaned = backendValue
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let compact = cleaned
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")

        switch compact {
        case "positive": self = .positive
        case "negative", "hardnegative": self = .hardNegative
        default: self = .unknown(cleaned)
        }
    }

    var displayTitle: String {
        switch self {
        case .positive: return "Positive"
        case .hardNegative: return "Negative"
        case .unknown: return "Unknown role"
        }
    }
}

enum BusinessMediaHistoryKind: Hashable {
    case video
    case photo
    case unknown(String)

    init(backendValue: String) {
        let cleaned = backendValue
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()

        switch cleaned {
        case "video", "movie": self = .video
        case "photo", "image": self = .photo
        default: self = .unknown(cleaned)
        }
    }

    var displayTitle: String {
        switch self {
        case .video: return "Video"
        case .photo: return "Image"
        case .unknown: return "Unknown media"
        }
    }

    var systemImage: String {
        switch self {
        case .video: return "video.fill"
        case .photo: return "photo.fill"
        case .unknown: return "questionmark.square.dashed"
        }
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

    /// Canonical status returned by the newest history Lambda. Older API
    /// responses may still place their raw pipeline status here.
    let status: String?

    /// Original writer status retained by the history Lambda for diagnostics.
    let rawStatus: String?

    let uploadedBy: BusinessMediaHistoryUploader
    let uploadedAt: Int64?
    let uploadedAtISO: String?
    let thumbnailUrl: URL?
    let thumbnailSource: String?
    let retryCount: Int?
    let lastRetryAt: Int64?
    let failureReason: String?

    var role: BusinessMediaHistoryRole {
        BusinessMediaHistoryRole(backendValue: datasetRole)
    }

    var kind: BusinessMediaHistoryKind {
        BusinessMediaHistoryKind(backendValue: mediaKind)
    }

    var lifecycleState: BusinessMediaLifecycleState {
        BusinessMediaLifecycleState(backendValue: status ?? rawStatus)
    }

    var isPositive: Bool {
        role == .positive
    }

    var isVideo: Bool {
        kind == .video
    }

    var roleAndMediaTitle: String {
        "\(role.displayTitle) • \(kind.displayTitle)"
    }

    var mediaSystemImage: String {
        kind.systemImage
    }

    var normalizedStatus: String {
        lifecycleState.displayTitle
    }

    var displayStatus: String {
        isProcessingDelayed ? "Delayed" : lifecycleState.displayTitle
    }

    var backendStatusText: String {
        let value = rawStatus ?? status
        let cleaned = value?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return cleaned.isEmpty ? "unknown" : cleaned
    }

    var uploadDate: Date? {
        if let uploadedAt, uploadedAt > 0 {
            return Date(timeIntervalSince1970: TimeInterval(uploadedAt))
        }

        guard let uploadedAtISO, !uploadedAtISO.isEmpty else {
            return nil
        }
        return ISO8601DateFormatter().date(from: uploadedAtISO)
    }

    var isProcessingDelayed: Bool {
        guard lifecycleState == .processing,
              let activityDate = lastRetryDate ?? uploadDate else {
            return false
        }
        return Date().timeIntervalSince(activityDate) >= 60 * 60
    }

    var canRetryProcessing: Bool {
        guard role == .hardNegative,
              let batchId,
              !batchId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !submissionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }

        return lifecycleState == .failed || isProcessingDelayed
    }

    var lastRetryDate: Date? {
        guard let lastRetryAt, lastRetryAt > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(lastRetryAt))
    }

    var displayFilename: String {
        let cleaned = originalFilename?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return cleaned.isEmpty ? "Unnamed media" : cleaned
    }
}
