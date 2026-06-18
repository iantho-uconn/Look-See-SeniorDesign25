//
//  SubmissionModels.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//  Updated to include upload metadata (location + descriptions + userEmail + landmark tracking).
//

import Foundation

enum MediaKind: String, Codable {
    case video
    case photo
}

struct InitSubmissionRequest: Codable {
    let userEmail: String
    let label: String
    let mediaKind: MediaKind
    let filename: String
    let contentType: String
}

struct InitSubmissionResponse: Codable {
    let submissionId: String
    let uploadUrl: String
    let s3Key: String
}

// Sent on /submissions/complete after PUT upload succeeds.
struct CompleteSubmissionRequest: Codable {
    let submissionId: String
    let s3Key: String
    let userEmail: String

    let label: String
    let landmarkId: String?
    let landmarkLabel: String?
    let mediaKind: MediaKind

    let shortDescription: String?
    let userDescription: String?

    let latitude: Double?
    let longitude: Double?
    let horizontalAccuracy: Double?
}

// Returned locally after the positive submission has completed.
// This is not decoded directly from the backend.
struct PositiveSubmissionResult {
    let submissionId: String
    let landmarkId: String?
    let mediaKind: MediaKind
    let s3Key: String
}
