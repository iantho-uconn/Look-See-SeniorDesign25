//
//  NearbyLandmark.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 3/9/26.
//

import Foundation

struct NearbyLandmark: Codable, Identifiable, Hashable {
    let landmarkId: String
    let label: String
    let shortDescription: String
    let latitude: Double
    let longitude: Double
    let distanceMeters: Double

    var id: String { landmarkId }
}

struct NearbyLandmarksRequest: Codable {
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
}

struct NearbyLandmarksResponse: Codable {
    let items: [NearbyLandmark]
    let count: Int
    let radiusMeters: Double
}
