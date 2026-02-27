//
//  SubmissionModels.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 2/15/26.
//

import Foundation

enum MediaKind: String, Codable {
    case video
    case photo
}

struct InitSubmissionRequest: Codable {
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

struct CompleteSubmissionRequest: Codable {
    let submissionId: String
    let s3Key: String
    let label: String
    let mediaKind: MediaKind
}
