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
    
    // MARK: - New Filter Fields (Matched to the updated Lambda)
    let createdBy: String?
    let createdAt: String?
    let promotionEnabled: Bool
    let promotion: String?
    let clusterId: String? // <-- THIS IS THE NEWEST ADDITION!

    // 🚀 NEW: Rich UI fields pulled from the backend
    let websiteUrl: String?
    let promoName: String?        
    let promoDescription: String?
    let promoImageUrl: String?
    let merchantName: String?
    let merchantBio: String?
    let merchantPhone: String?
    let merchantAddress: String?
    let merchantLogoUrl: String?

    var id: String { landmarkId }
}

struct NearbyLandmarksRequest: Codable {
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
    let limit: Int? // Added to support our new database limit safeguard!
}

struct NearbyLandmarksResponse: Codable {
    let items: [NearbyLandmark]
    let count: Int
    let radiusMeters: Double
}
