//
//  VariableContainer.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 4/12/26.
//

import SwiftUI
import Combine

struct PendingMediaProcessingState: Codable, Equatable, Sendable {
    let landmarkId: String
    let baselineFrameCount: Int?
    let baselineSecondsNeeded: Int?
    let startedAt: TimeInterval
    var uploadCompletedAt: TimeInterval?
}

struct CachedCoordinate: Codable, Equatable, Sendable {
    let latitude: Double
    let longitude: Double
}

final class SuppressionCache: @unchecked Sendable {
    static let shared = SuppressionCache()

    private let lock = NSLock()
    private let inactiveDefaultsKey = "looksee_inactive_landmark_ids"
    private let hiddenMapDefaultsKey = "looksee_hidden_map_landmark_ids"
    private let modifiedDefaultsKey = "looksee_locally_modified_landmark_ids"
    private let processingDefaultsKey = "looksee_processing_new_data_landmarks"
    private let displayNamesDefaultsKey = "looksee_landmark_display_names"
    private let coordinatesDefaultsKey = "looksee_landmark_coordinates"

    private var inactiveIds: Set<String>
    private var hiddenMapIds: Set<String>
    private var locallyModifiedIds: Set<String>
    private var processingStates: [String: PendingMediaProcessingState]
    private var displayNames: [String: String]
    private var coordinates: [String: CachedCoordinate]

    private init() {
        let savedInactive = UserDefaults.standard.stringArray(forKey: inactiveDefaultsKey) ?? []
        let savedHidden = UserDefaults.standard.stringArray(forKey: hiddenMapDefaultsKey) ?? []
        let savedModified = UserDefaults.standard.stringArray(forKey: modifiedDefaultsKey) ?? []
        self.inactiveIds = Set(savedInactive)
        self.hiddenMapIds = Set(savedHidden)
        self.locallyModifiedIds = Set(savedModified)
        self.displayNames = UserDefaults.standard.dictionary(forKey: displayNamesDefaultsKey) as? [String: String] ?? [:]

        if let data = UserDefaults.standard.data(forKey: processingDefaultsKey),
           let decoded = try? JSONDecoder().decode([String: PendingMediaProcessingState].self, from: data) {
            self.processingStates = decoded
        } else {
            self.processingStates = [:]
        }

        if let data = UserDefaults.standard.data(forKey: coordinatesDefaultsKey),
           let decoded = try? JSONDecoder().decode([String: CachedCoordinate].self, from: data) {
            self.coordinates = decoded
        } else {
            self.coordinates = [:]
        }
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

    func displayName(for id: String) -> String? {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        lock.lock()
        defer { lock.unlock() }
        let value = displayNames[trimmed]?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (value?.isEmpty == false) ? value : nil
    }

    func setDisplayName(_ name: String, for id: String) {
        let trimmedId = id.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty, !trimmedName.isEmpty else { return }

        lock.lock()
        let changed = displayNames[trimmedId] != trimmedName
        if changed {
            displayNames[trimmedId] = trimmedName
        }
        let snapshot = displayNames
        lock.unlock()

        if changed {
            DispatchQueue.global(qos: .background).async {
                UserDefaults.standard.set(snapshot, forKey: self.displayNamesDefaultsKey)
            }
        }
    }

    func mergeDisplayNames(_ updates: [String: String]) {
        guard !updates.isEmpty else { return }

        lock.lock()
        var changed = false
        for (rawId, rawName) in updates {
            let id = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !name.isEmpty else { continue }
            if displayNames[id] != name {
                displayNames[id] = name
                changed = true
            }
        }
        let snapshot = displayNames
        lock.unlock()

        if changed {
            DispatchQueue.global(qos: .background).async {
                UserDefaults.standard.set(snapshot, forKey: self.displayNamesDefaultsKey)
            }
        }
    }

    func coordinates(for id: String) -> CachedCoordinate? {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return coordinates[trimmed]
    }

    func setCoordinates(latitude: Double, longitude: Double, for id: String) {
        let trimmedId = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty,
              latitude.isFinite, longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude),
              !(latitude == 0 && longitude == 0) else { return }

        let coord = CachedCoordinate(latitude: latitude, longitude: longitude)
        lock.lock()
        let changed = coordinates[trimmedId] != coord
        if changed {
            coordinates[trimmedId] = coord
        }
        let snapshot = coordinates
        lock.unlock()

        if changed {
            DispatchQueue.global(qos: .background).async {
                if let encoded = try? JSONEncoder().encode(snapshot) {
                    UserDefaults.standard.set(encoded, forKey: self.coordinatesDefaultsKey)
                }
            }
        }
    }

    func mergeCoordinates(_ updates: [String: CachedCoordinate]) {
        guard !updates.isEmpty else { return }

        lock.lock()
        var changed = false
        for (rawId, coord) in updates {
            let id = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty,
                  coord.latitude.isFinite, coord.longitude.isFinite,
                  (-90.0...90.0).contains(coord.latitude),
                  (-180.0...180.0).contains(coord.longitude),
                  !(coord.latitude == 0 && coord.longitude == 0) else { continue }
            if coordinates[id] != coord {
                coordinates[id] = coord
                changed = true
            }
        }
        let snapshot = coordinates
        lock.unlock()

        if changed {
            DispatchQueue.global(qos: .background).async {
                if let encoded = try? JSONEncoder().encode(snapshot) {
                    UserDefaults.standard.set(encoded, forKey: self.coordinatesDefaultsKey)
                }
            }
        }
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

    func updateProcessingStates(_ states: [String: PendingMediaProcessingState]) {
        lock.lock()
        self.processingStates = states
        lock.unlock()

        DispatchQueue.global(qos: .background).async {
            if let encoded = try? JSONEncoder().encode(states) {
                UserDefaults.standard.set(encoded, forKey: self.processingDefaultsKey)
            }
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

    func getInitialProcessingStates() -> [String: PendingMediaProcessingState] {
        lock.lock()
        defer { lock.unlock() }
        return processingStates
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

    nonisolated static func displayName(for landmarkId: String) -> String? {
        return SuppressionCache.shared.displayName(for: landmarkId)
    }

    nonisolated static func coordinates(for landmarkId: String) -> CachedCoordinate? {
        return SuppressionCache.shared.coordinates(for: landmarkId)
    }

    func setDisplayName(landmarkId: String, displayName: String) {
        SuppressionCache.shared.setDisplayName(displayName, for: landmarkId)
    }

    func setCoordinates(landmarkId: String, latitude: Double, longitude: Double) {
        SuppressionCache.shared.setCoordinates(latitude: latitude, longitude: longitude, for: landmarkId)
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

    private var isUpdatingNearbyLocally = false

    @Published var allLandmarks: [NearbyLandmark] = [] {
        didSet {
            if !isUpdatingNearbyLocally {
                syncSuppressionSetsFromNearby()
            }
        }
    }
    @Published var inactiveLandmarkIds: Set<String> = []
    @Published var hiddenMapLandmarkIds: Set<String> = []
    @Published var processingNewDataById: [String: PendingMediaProcessingState] = [:]
    private var locallyModifiedIds: Set<String> = []

    private let postUploadProcessingTimeout: TimeInterval = 240
    private let maxOverallProcessingTimeout: TimeInterval = 900

    private init() {
        self.inactiveLandmarkIds = SuppressionCache.shared.getInitialInactive()
        self.hiddenMapLandmarkIds = SuppressionCache.shared.getInitialHidden()
        self.locallyModifiedIds = SuppressionCache.shared.getInitialModified()
        self.processingNewDataById = SuppressionCache.shared.getInitialProcessingStates()
        resetLandmarkDisplay()
    }

    static func makePopupPreview() -> VariableContainer {
        VariableContainer()
    }

    private func persistSuppressionSets() {
        SuppressionCache.shared.update(
            inactive: inactiveLandmarkIds,
            hidden: hiddenMapLandmarkIds,
            modified: locallyModifiedIds
        )
    }

    private func persistProcessingStates() {
        SuppressionCache.shared.updateProcessingStates(processingNewDataById)
    }

    // MARK: - Optimistic "Processing New Data" Management

    func markLandmarkProcessingNewData(
        landmarkId: String,
        baselineFrameCount: Int? = nil,
        baselineSecondsNeeded: Int? = nil,
        isUploadAlreadyComplete: Bool = false
    ) {
        let id = landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }

        let cachedLandmark = Self.lookupCachedBusinessLandmark(id: id)
        let resolvedFrames = baselineFrameCount ?? cachedLandmark?.cleanFrameCount
        let resolvedSeconds = baselineSecondsNeeded ?? cachedLandmark?.secondsNeeded
        let now = Date().timeIntervalSince1970

        processingNewDataById[id] = PendingMediaProcessingState(
            landmarkId: id,
            baselineFrameCount: resolvedFrames,
            baselineSecondsNeeded: resolvedSeconds,
            startedAt: now,
            uploadCompletedAt: isUploadAlreadyComplete ? now : nil
        )
        persistProcessingStates()
    }

    func markLandmarkUploadCompleted(landmarkId: String) {
        let id = landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        let now = Date().timeIntervalSince1970

        if var existing = processingNewDataById[id] {
            existing.uploadCompletedAt = now
            processingNewDataById[id] = existing
        } else {
            let cachedLandmark = Self.lookupCachedBusinessLandmark(id: id)
            processingNewDataById[id] = PendingMediaProcessingState(
                landmarkId: id,
                baselineFrameCount: cachedLandmark?.cleanFrameCount,
                baselineSecondsNeeded: cachedLandmark?.secondsNeeded,
                startedAt: now,
                uploadCompletedAt: now
            )
        }
        persistProcessingStates()
    }

    func clearLandmarkProcessingNewData(landmarkId: String) {
        let id = landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        if processingNewDataById.removeValue(forKey: id) != nil {
            persistProcessingStates()
        }
    }

    func isLandmarkProcessingNewData(_ landmark: BusinessLandmark) -> Bool {
        let id = landmark.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return false }

        if landmark.isProcessing {
            return false
        }

        let hasActiveQueuedUpload = OfflineMediaManager.shared.archivedItems.contains { item in
            let matchesId = (item.landmarkId == id || item.queueLandmarkId == id)
            let isHealthy = (item.lastUploadError == nil && item.deletionBlocked != true)
            return matchesId && isHealthy
        }
        if hasActiveQueuedUpload {
            return true
        }

        guard let state = processingNewDataById[id] else {
            return false
        }

        let now = Date().timeIntervalSince1970
        if let completedAt = state.uploadCompletedAt, now - completedAt > postUploadProcessingTimeout {
            return false
        }
        if now - state.startedAt > maxOverallProcessingTimeout {
            return false
        }

        if let baseFrames = state.baselineFrameCount,
           let currentFrames = landmark.cleanFrameCount,
           currentFrames != baseFrames {
            return false
        }
        if let baseSeconds = state.baselineSecondsNeeded,
           let currentSeconds = landmark.secondsNeeded,
           currentSeconds != baseSeconds {
            return false
        }

        return true
    }

    func reconcileProcessingNewData(with landmarks: [BusinessLandmark]) {
        var nameUpdates: [String: String] = [:]
        var coordUpdates: [String: CachedCoordinate] = [:]

        for landmark in landmarks {
            let id = landmark.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty else { continue }

            let name = landmark.effectiveDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !name.isEmpty {
                nameUpdates[id] = name
            }
            if let lat = landmark.latitude, let lon = landmark.longitude {
                coordUpdates[id] = CachedCoordinate(latitude: lat, longitude: lon)
            }
        }
        SuppressionCache.shared.mergeDisplayNames(nameUpdates)
        SuppressionCache.shared.mergeCoordinates(coordUpdates)

        guard !processingNewDataById.isEmpty else { return }
        let now = Date().timeIntervalSince1970
        let queuedIds = Set(
            OfflineMediaManager.shared.archivedItems.compactMap { item -> String? in
                guard item.lastUploadError == nil && item.deletionBlocked != true else { return nil }
                return item.landmarkId ?? item.queueLandmarkId
            }
        )

        var updated = processingNewDataById
        var didChange = false

        for (id, state) in updated {
            if queuedIds.contains(id) { continue }

            if let completedAt = state.uploadCompletedAt, now - completedAt > postUploadProcessingTimeout {
                updated.removeValue(forKey: id)
                didChange = true
                continue
            }
            if now - state.startedAt > maxOverallProcessingTimeout {
                updated.removeValue(forKey: id)
                didChange = true
                continue
            }

            if let landmark = landmarks.first(where: { $0.landmarkId == id }) {
                let status = landmark.status ?? ""
                if status != "NEEDS_MORE_MEDIA" {
                    updated.removeValue(forKey: id)
                    didChange = true
                    continue
                }
                if let baseFrames = state.baselineFrameCount,
                   let currentFrames = landmark.cleanFrameCount,
                   currentFrames != baseFrames {
                    updated.removeValue(forKey: id)
                    didChange = true
                    continue
                }
                if let baseSeconds = state.baselineSecondsNeeded,
                   let currentSeconds = landmark.secondsNeeded,
                   currentSeconds != baseSeconds {
                    updated.removeValue(forKey: id)
                    didChange = true
                    continue
                }
            }
        }

        if didChange {
            processingNewDataById = updated
            persistProcessingStates()
        }
    }

    private static func lookupCachedBusinessLandmark(id: String) -> BusinessLandmark? {
        guard let data = UserDefaults.standard.data(forKey: "BusinessLandmarksCache"),
              let cached = try? JSONDecoder().decode([BusinessLandmark].self, from: data) else {
            return nil
        }
        return cached.first(where: { $0.landmarkId == id })
    }

    // MARK: - Map & Scanner Suppression

    private func syncSuppressionSetsFromNearby() {
        var updatedInactive = inactiveLandmarkIds
        var updatedHidden = hiddenMapLandmarkIds
        var updatedModified = locallyModifiedIds
        var nameUpdates: [String: String] = [:]
        var coordUpdates: [String: CachedCoordinate] = [:]

        for item in allLandmarks {
            let id = item.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty else { continue }

            nameUpdates[id] = item.effectiveDisplayName
            coordUpdates[id] = CachedCoordinate(latitude: item.latitude, longitude: item.longitude)

            if updatedModified.contains(id) {
                let localIsActive = !updatedInactive.contains(id)
                let localIsVisible = !updatedHidden.contains(id)
                if item.isActive == localIsActive && item.isVisibleOnMap == localIsVisible {
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

        SuppressionCache.shared.mergeDisplayNames(nameUpdates)
        SuppressionCache.shared.mergeCoordinates(coordUpdates)
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

        if let index = allLandmarks.firstIndex(where: { $0.landmarkId == id }) {
            isUpdatingNearbyLocally = true
            defer { isUpdatingNearbyLocally = false }
            allLandmarks[index] = allLandmarks[index].withState(
                isActive: isActive,
                isVisibleOnMap: isVisibleOnMap
            )
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

        landmarkName = Self.displayName(for: entry.landmarkId) ?? entry.label

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
        setDisplayName(landmarkId: id, displayName: name)
        setCoordinates(landmarkId: id, latitude: latitude, longitude: longitude)

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
