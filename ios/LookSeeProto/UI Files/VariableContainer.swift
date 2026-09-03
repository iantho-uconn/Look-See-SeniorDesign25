//
//  VariableContainer.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 4/12/26.
//

import SwiftUI
import Combine

@MainActor
final class VariableContainer: ObservableObject {
    static let shared = VariableContainer()

    @Published var infoView: Bool = false
    @Published var bboxCounter: Int = 0

    @Published var landmarkName: String = "Not available"
    @Published var landmarkConfidence: Float = 0.00
    @Published var landmarkCategory: String = "Not available"
    @Published var landmarkDescription: String = "No description is available for this landmark."
    @Published var landmarkURL: String = ""
    @Published var landmarkWebsiteUrl: String = ""

    @Published var landmarkId: String = ""
    @Published var landmarkClassIndex: Int?
    @Published var landmarkClusterId: Int?
    @Published var landmarkTrainingRunId: String = ""
    @Published var landmarkDatasetClassName: String = ""

    @Published var promoName: String = "No active promotion"
    @Published var promoDescription: String = ""
    @Published var promoImageUrl: String = ""
    
    @Published var merchantName: String = ""
    @Published var merchantBio: String = ""
    @Published var merchantPhone: String = ""
    @Published var merchantWebsite: String = ""
    @Published var merchantAddress: String = ""
    @Published var merchantLogoUrl: String = ""
    
    // 🚀 NEW: Map Routing & Reporting Fields
    @Published var isMapPin: Bool = false
    @Published var mapLatitude: Double?
    @Published var mapLongitude: Double?
    @Published var reportedOwnerId: String?

    private init() {
        resetLandmarkDisplay()
    }

    func presentLandmark(
        _ entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
        resetLandmarkDisplay()
        
        landmarkId = entry.landmarkId
        landmarkClassIndex = entry.classIndex
        landmarkClusterId = clusterId
        landmarkTrainingRunId = trainingRunId
        landmarkDatasetClassName = entry.datasetClassName

        landmarkName = entry.label

        let trimmedDescription = entry.shortDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)

        landmarkDescription = trimmedDescription.isEmpty
            ? "No description is available for this landmark."
            : trimmedDescription

        landmarkCategory = entry.datasetClassName
            .replacingOccurrences(of: "_", with: " ")

        let clampedConfidence = min(max(detectionConfidence, 0), 1)
        landmarkConfidence = clampedConfidence * 100
        
        let cacheKey = "cached_merchant_\(entry.landmarkId)"
        if let cachedData = UserDefaults.standard.dictionary(forKey: cacheKey) {
            merchantName = cachedData["merchantName"] as? String ?? ""
            merchantBio = cachedData["merchantBio"] as? String ?? ""
            merchantPhone = cachedData["merchantPhone"] as? String ?? ""
            merchantWebsite = cachedData["merchantWebsite"] as? String ?? ""
            merchantAddress = cachedData["merchantAddress"] as? String ?? ""
            merchantLogoUrl = cachedData["merchantLogoUrl"] as? String ?? ""
        }

        infoView = true
    }

    // 🚀 NEW: Added promoName, promoDescription, and Merchant Cache lookup!
    func presentMapLandmark(
        id: String,
        name: String,
        description: String,
        latitude: Double,
        longitude: Double,
        promotionEnabled: Bool,
        promotion: String?,
        ownerId: String?,
        websiteUrl: String?,
        promoName: String?,         // <-- NEW
        promoDescription: String?,  // <-- NEW
        promoImageUrl: String?,
        merchantName: String?,
        merchantBio: String?,
        merchantPhone: String?,
        merchantAddress: String?,
        merchantLogoUrl: String?
    ) {
        resetLandmarkDisplay()
        
        self.landmarkId = id
        self.landmarkName = name
        
        let trimmedDesc = description.trimmingCharacters(in: .whitespacesAndNewlines)
        self.landmarkDescription = trimmedDesc.isEmpty ? "No description is available for this landmark." : trimmedDesc
        
        self.isMapPin = true
        self.mapLatitude = latitude
        self.mapLongitude = longitude
        self.reportedOwnerId = ownerId
        
        self.landmarkWebsiteUrl = websiteUrl ?? ""
        
        if promotionEnabled {
            self.promoName = promoName ?? promotion ?? "Special Promotion Available!"
            self.promoDescription = promoDescription ?? ""
            self.promoImageUrl = promoImageUrl ?? ""
        }
        
        // 🚀 THE FIX: Pull the Merchant Info from the exact same cache the Scanner uses!
        let cacheKey = "cached_merchant_\(id)"
        if let cachedData = UserDefaults.standard.dictionary(forKey: cacheKey) {
            self.merchantName = cachedData["merchantName"] as? String ?? merchantName ?? ""
            self.merchantBio = cachedData["merchantBio"] as? String ?? merchantBio ?? ""
            self.merchantPhone = cachedData["merchantPhone"] as? String ?? merchantPhone ?? ""
            self.merchantWebsite = cachedData["merchantWebsite"] as? String ?? merchantWebsite ?? ""
            self.merchantAddress = cachedData["merchantAddress"] as? String ?? merchantAddress ?? ""
            self.merchantLogoUrl = cachedData["merchantLogoUrl"] as? String ?? merchantLogoUrl ?? ""
        } else {
            self.merchantName = merchantName ?? ""
            self.merchantBio = merchantBio ?? ""
            self.merchantPhone = merchantPhone ?? ""
            self.merchantWebsite = merchantWebsite ?? ""
            self.merchantAddress = merchantAddress ?? ""
            self.merchantLogoUrl = merchantLogoUrl ?? ""
        }
        
        self.infoView = true
    }

    func dismissLandmark() {
        infoView = false
        landmarkURL = ""
    }

    func resetLandmarkDisplay() {
        infoView = false
        bboxCounter = 0

        landmarkName = ""
        landmarkConfidence = 0.00
        landmarkCategory = "Not available"
        landmarkDescription = "No description is available for this landmark."
        landmarkURL = ""
        landmarkWebsiteUrl = ""

        landmarkId = ""
        landmarkClassIndex = nil
        landmarkClusterId = nil
        landmarkTrainingRunId = ""
        landmarkDatasetClassName = ""

        promoName = "No active promotion"
        promoDescription = ""
        promoImageUrl = ""
        
        merchantName = ""
        merchantBio = ""
        merchantPhone = ""
        merchantWebsite = ""
        merchantAddress = ""
        merchantLogoUrl = ""
        
        isMapPin = false
        mapLatitude = nil
        mapLongitude = nil
        reportedOwnerId = nil
    }

    func getlandmarkName() -> String {
        landmarkName
    }
}
