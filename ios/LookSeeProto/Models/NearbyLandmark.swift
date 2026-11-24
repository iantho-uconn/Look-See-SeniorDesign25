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
    let displayName: String?
    let shortDescription: String
    let latitude: Double
    let longitude: Double
    let distanceMeters: Double
    let createdAt: String?
    let createdBy: String?
    let clusterId: String?
    let status: String?
    let isProcessing: Bool?

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

    init(
        landmarkId: String,
        label: String,
        displayName: String? = nil,
        shortDescription: String,
        latitude: Double,
        longitude: Double,
        distanceMeters: Double,
        createdAt: String?,
        createdBy: String?,
        clusterId: String?,
        status: String?,
        isProcessing: Bool?,
        isActive: Bool?,
        isVisibleOnMap: Bool?,
        promotionEnabled: Bool,
        promotion: String?,
        websiteUrl: String?,
        promoName: String?,
        promoDescription: String?,
        promoImageUrl: String?,
        merchantName: String?,
        merchantBio: String?,
        merchantPhone: String?,
        merchantAddress: String?,
        merchantLogoUrl: String?
    ) {
        self.landmarkId = landmarkId
        self.label = label
        self.displayName = displayName
        self.shortDescription = shortDescription
        self.latitude = latitude
        self.longitude = longitude
        self.distanceMeters = distanceMeters
        self.createdAt = createdAt
        self.createdBy = createdBy
        self.clusterId = clusterId
        self.status = status
        self.isProcessing = isProcessing
        self.isActive = isActive
        self.isVisibleOnMap = isVisibleOnMap
        self.promotionEnabled = promotionEnabled
        self.promotion = promotion
        self.websiteUrl = websiteUrl
        self.promoName = promoName
        self.promoDescription = promoDescription
        self.promoImageUrl = promoImageUrl
        self.merchantName = merchantName
        self.merchantBio = merchantBio
        self.merchantPhone = merchantPhone
        self.merchantAddress = merchantAddress
        self.merchantLogoUrl = merchantLogoUrl
    }

    var effectiveDisplayName: String {
        let preferred = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !preferred.isEmpty {
            return preferred
        }
        let fallback = label.trimmingCharacters(in: .whitespacesAndNewlines)
        return fallback.isEmpty ? "Unknown Landmark" : fallback
    }

    func withState(isActive: Bool, isVisibleOnMap: Bool) -> NearbyLandmark {
        NearbyLandmark(
            landmarkId: landmarkId,
            label: label,
            displayName: displayName,
            shortDescription: shortDescription,
            latitude: latitude,
            longitude: longitude,
            distanceMeters: distanceMeters,
            createdAt: createdAt,
            createdBy: createdBy,
            clusterId: clusterId,
            status: status,
            isProcessing: isProcessing,
            isActive: isActive,
            isVisibleOnMap: isVisibleOnMap,
            promotionEnabled: promotionEnabled,
            promotion: promotion,
            websiteUrl: websiteUrl,
            promoName: promoName,
            promoDescription: promoDescription,
            promoImageUrl: promoImageUrl,
            merchantName: merchantName,
            merchantBio: merchantBio,
            merchantPhone: merchantPhone,
            merchantAddress: merchantAddress,
            merchantLogoUrl: merchantLogoUrl
        )
    }

    static func == (lhs: NearbyLandmark, rhs: NearbyLandmark) -> Bool {
        lhs.landmarkId == rhs.landmarkId &&
        lhs.label == rhs.label &&
        lhs.displayName == rhs.displayName &&
        lhs.isActive == rhs.isActive &&
        lhs.isVisibleOnMap == rhs.isVisibleOnMap &&
        lhs.promotionEnabled == rhs.promotionEnabled &&
        lhs.clusterId == rhs.clusterId &&
        lhs.status == rhs.status &&
        lhs.isProcessing == rhs.isProcessing
    }
}
