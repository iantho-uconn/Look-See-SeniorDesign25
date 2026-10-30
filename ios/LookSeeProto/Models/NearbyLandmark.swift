//
//  NearbyLandmark.swift
//  LookSeeProto
//

import Foundation

struct NearbyLandmarksRequest: Codable {
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
    let limit: Int
}

struct NearbyLandmarksResponse: Codable {
    let items: [NearbyLandmark]
    let count: Int
    let radiusMeters: Double
    let skippedInactiveCount: Int?
}

struct NearbyLandmark: Identifiable, Codable, Equatable {
    var id: String { landmarkId }
    let landmarkId: String
    let label: String
    let shortDescription: String
    let latitude: Double
    let longitude: Double
    let distanceMeters: Double
    let createdAt: String?
    let createdBy: String?
    let clusterId: String?
    let status: String?

    let isActive: Bool?
    let isVisibleOnMap: Bool?
    let promotionEnabled: Bool
    let promotion: String?

    let websiteUrl: String?
    let promoName: String?
    let promoDescription: String?
    let promoImageUrl: String?
    let merchantName: String?
    let merchantBio: String?
    let merchantPhone: String?
    let merchantAddress: String?
    let merchantLogoUrl: String?

    static func == (lhs: NearbyLandmark, rhs: NearbyLandmark) -> Bool {
        lhs.landmarkId == rhs.landmarkId &&
        lhs.isActive == rhs.isActive &&
        lhs.isVisibleOnMap == rhs.isVisibleOnMap &&
        lhs.promotionEnabled == rhs.promotionEnabled &&
        lhs.clusterId == rhs.clusterId &&
        lhs.status == rhs.status
    }
}
