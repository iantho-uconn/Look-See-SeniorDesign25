//
//  ModelService.swift
//  LookSeeProto
//
//  Hardcoded for frontend development.
//  TODO: backend dev to replace hardcoded values with real API + S3 calls
//

import Foundation
import Combine

// MARK: - Model Metadata
struct ModelInfo {
    let name: String
    let version: String
    let region: String
    let fileSizeBytes: Int64
    let lastUpdated: Date

    var fileSizeFormatted: String {
        let mb = Double(fileSizeBytes) / 1_048_576
        return String(format: "%.1f MB", mb)
    }

    var lastUpdatedFormatted: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: lastUpdated)
    }
}

// MARK: - Model State
enum ModelState: Equatable {
    case notLoaded
    case loading
    case loaded([ModelInfo])
    case failed(String)

    static func == (lhs: ModelState, rhs: ModelState) -> Bool {
        switch (lhs, rhs) {
        case (.notLoaded, .notLoaded): return true
        case (.loading, .loading): return true
        case (.failed, .failed): return true
        case (.loaded, .loaded): return true
        default: return false
        }
    }
}

// MARK: - Model Service (Hardcoded)
@MainActor
class ModelService: ObservableObject {

    static let shared = ModelService()

    @Published var state: ModelState = .notLoaded
    @Published var downloadProgress: Double = 0.0
    @Published var updateAvailable: Bool = false

    private init() {}

    // MARK: - Load Models
    // TODO: backend dev — replace simulated delay with real API + S3 download
    func loadModels(latitude: Double, longitude: Double) async {
        state = .loading
        downloadProgress = 0.0

        // Simulated download progress
        for i in 1...10 {
            try? await Task.sleep(nanoseconds: 200_000_000) // 0.2s per step
            downloadProgress = Double(i) / 10.0
        }

        // Hardcoded fake models based on location
        // TODO: backend dev — replace with real Lambda call:
        // GET https://your-api.amazonaws.com/prod/select-model?lat=\(latitude)&lng=\(longitude)
        let fakeModels: [ModelInfo] = [
            ModelInfo(
                name: "yolo-model-0",
                version: "1",
                region: "Northeast",
                fileSizeBytes: 24_000_000,
                lastUpdated: Date()
            ),
            ModelInfo(
                name: "yolo-model-1",
                version: "1",
                region: "Northeast",
                fileSizeBytes: 18_500_000,
                lastUpdated: Date()
            )
        ]

        state = .loaded(fakeModels)
    }

    // MARK: - Reload Models
    // TODO: backend dev — clear local files and re-download from S3
    func reloadModels(latitude: Double, longitude: Double) async {
        state = .notLoaded
        await loadModels(latitude: latitude, longitude: longitude)
    }

    // MARK: - Check for Updates
    // TODO: backend dev — ping API to compare remote vs local model versions
    func checkForUpdates(latitude: Double, longitude: Double) async {
        try? await Task.sleep(nanoseconds: 1_000_000_000) // simulate network call
        updateAvailable = true // hardcoded to always show update available for UI testing
    }

    // MARK: - Delete Models
    // TODO: backend dev — delete downloaded .mlmodel files from device storage
    func deleteModels() throws {
        state = .notLoaded
        updateAvailable = false
        downloadProgress = 0.0
    }

    // MARK: - Movement Check
    // TODO: backend dev — wire into LocationManager.didUpdateLocations
    func checkIfShouldReload(latitude: Double, longitude: Double) async {
        await loadModels(latitude: latitude, longitude: longitude)
    }
}
