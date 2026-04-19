//
//  SubmissionModels.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//  Updated to include upload metadata (location + descriptions).
//

import Foundation

enum MediaKind: String, Codable {
    case video
    case photo
}

struct InitSubmissionRequest: Codable {
    let userEmail: String     // ← add this
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
// This is where we attach metadata so the backend has everything it needs for downstream pipeline steps.
struct CompleteSubmissionRequest: Codable {
    let submissionId: String
    let s3Key: String
    let userEmail: String     // ← add this

    let label: String
    let mediaKind: MediaKind

    let shortDescription: String?
    let userDescription: String?

    let latitude: Double?
    let longitude: Double?
    let horizontalAccuracy: Double?
}
