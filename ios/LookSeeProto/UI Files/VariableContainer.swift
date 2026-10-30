//
//  VariableContainer.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 4/12/26.
//

import SwiftUI
import Combine

final class SuppressionCache: @unchecked Sendable {
    static let shared = SuppressionCache()

    private let lock = NSLock()
    private let inactiveDefaultsKey = "looksee_inactive_landmark_ids"
    private let hiddenMapDefaultsKey = "looksee_hidden_map_landmark_ids"
    private let modifiedDefaultsKey = "looksee_locally_modified_landmark_ids"

    private var inactiveIds: Set<String>
    private var hiddenMapIds: Set<String>
    private var locallyModifiedIds: Set<String>

    private init() {
        let savedInactive = UserDefaults.standard.stringArray(forKey: inactiveDefaultsKey) ?? []
        let savedHidden = UserDefaults.standard.stringArray(forKey: hiddenMapDefaultsKey) ?? []
        let savedModified = UserDefaults.standard.stringArray(forKey: modifiedDefaultsKey) ?? []
        self.inactiveIds = Set(savedInactive)
        self.hiddenMapIds = Set(savedHidden)
        self.locallyModifiedIds = Set(savedModified)
    }

    func isInactive(_ id: String) -> Bool {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }
        return inactiveIds.contains(trimmed)
    }

    func isHidden(_ id: String) -> Bool {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }
        return hiddenMapIds.contains(trimmed)
    }

    func isLocallyModified(_ id: String) -> Bool {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }
        return locallyModifiedIds.contains(trimmed)
    }

    func update(inactive: Set<String>, hidden: Set<String>, modified: Set<String>) {
        lock.lock()
        self.inactiveIds = inactive
        self.hiddenMapIds = hidden
        self.locallyModifiedIds = modified
        lock.unlock()

        DispatchQueue.global(qos: .background).async {
            UserDefaults.standard.set(Array(inactive), forKey: self.inactiveDefaultsKey)
            UserDefaults.standard.set(Array(hidden), forKey: self.hiddenMapDefaultsKey)
            UserDefaults.standard.set(Array(modified), forKey: self.modifiedDefaultsKey)
        }
    }

    func getInitialInactive() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return inactiveIds
    }

    func getInitialHidden() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return hiddenMapIds
    }

    func getInitialModified() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return locallyModifiedIds
    }
}

@MainActor
final class VariableContainer: ObservableObject {
    static let shared = VariableContainer()

    nonisolated static func isLandmarkInactive(_ landmarkId: String) -> Bool {
        return SuppressionCache.shared.isInactive(landmarkId)
    }

    nonisolated static func isLandmarkHiddenOnMap(_ landmarkId: String) -> Bool {
        return SuppressionCache.shared.isHidden(landmarkId)
    }

    func isLocallyModified(_ landmarkId: String) -> Bool {
        return SuppressionCache.shared.isLocallyModified(landmarkId)
    }

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

    @Published var isMapPin: Bool = false
    @Published var mapLatitude: Double?
    @Published var mapLongitude: Double?
    @Published var reportedOwnerId: String?

    @Published var allLandmarks: [NearbyLandmark] = [] {
        didSet {
            syncSuppressionSetsFromNearby()
        }
    }
    @Published var inactiveLandmarkIds: Set<String> = []
    @Published var hiddenMapLandmarkIds: Set<String> = []
    private var locallyModifiedIds: Set<String> = []

    private init() {
        self.inactiveLandmarkIds = SuppressionCache.shared.getInitialInactive()
        self.hiddenMapLandmarkIds = SuppressionCache.shared.getInitialHidden()
        self.locallyModifiedIds = SuppressionCache.shared.getInitialModified()
        resetLandmarkDisplay()
    }

    private func persistSuppressionSets() {
        SuppressionCache.shared.update(
            inactive: inactiveLandmarkIds,
            hidden: hiddenMapLandmarkIds,
            modified: locallyModifiedIds
        )
    }

    private func syncSuppressionSetsFromNearby() {
        var updatedInactive = inactiveLandmarkIds
        var updatedHidden = hiddenMapLandmarkIds
        var updatedModified = locallyModifiedIds

        for item in allLandmarks {
            let id = item.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty else { continue }

            // If the user toggled this landmark locally, only clear the local override once the server catches up
            if updatedModified.contains(id) {
                let localIsActive = !updatedInactive.contains(id)
                if item.isActive == localIsActive {
                    updatedModified.remove(id)
                } else {
                    continue
                }
            }

            if item.isActive == false {
                updatedInactive.insert(id)
            } else if item.isActive == true {
                updatedInactive.remove(id)
            }

            if item.isVisibleOnMap == false {
                updatedHidden.insert(id)
            } else if item.isVisibleOnMap == true {
                updatedHidden.remove(id)
            }
        }

        inactiveLandmarkIds = updatedInactive
        hiddenMapLandmarkIds = updatedHidden
        locallyModifiedIds = updatedModified
        persistSuppressionSets()
    }

    func setLandmarkState(landmarkId: String, isActive: Bool, isVisibleOnMap: Bool) {
        let id = landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }

        locallyModifiedIds.insert(id)

        if isActive {
            inactiveLandmarkIds.remove(id)
        } else {
            inactiveLandmarkIds.insert(id)
            if self.landmarkId == id {
                dismissLandmark()
            }
        }

        if isVisibleOnMap {
            hiddenMapLandmarkIds.remove(id)
        } else {
            hiddenMapLandmarkIds.insert(id)
        }

        persistSuppressionSets()
    }

    func presentLandmark(
        _ entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
        if Self.isLandmarkInactive(entry.landmarkId) {
            return
        }

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
        promoName: String?,
        promoDescription: String?,
        promoImageUrl: String?,
        merchantName: String?,
        merchantBio: String?,
        merchantPhone: String?,
        merchantAddress: String?,
        merchantLogoUrl: String?
    ) {
        if Self.isLandmarkInactive(id) {
            return
        }

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
