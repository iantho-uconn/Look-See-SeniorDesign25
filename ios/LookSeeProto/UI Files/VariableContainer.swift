//
//  VariableContainer.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 4/12/26.
//

import SwiftUI
import Combine

/// Shared display state used by the landmark information popup.
///
/// The manifest-based detection flow resolves a `LandmarkManifestEntry`, then
/// calls `presentLandmark(...)` to populate the same fields already consumed by
/// `LandmarkInfo` and `PopUp`.
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

    // Manifest/debugging identity. These are useful when confirming that a
    // popup was resolved from the same cluster release as the active model.
    @Published var landmarkId: String = ""
    @Published var landmarkClassIndex: Int?
    @Published var landmarkClusterId: Int?
    @Published var landmarkTrainingRunId: String = ""
    @Published var landmarkDatasetClassName: String = ""

    @Published var promoName: String = "No active promotion"
    @Published var promoDescription: String = ""
    @Published var promoImageUrl: String = ""

    private init() {
        resetLandmarkDisplay()
    }

    /// Populates and opens the popup using information resolved from a local
    /// cluster landmark manifest.
    ///
    /// - Parameters:
    ///   - entry: The manifest entry associated with the detected class index.
    ///   - clusterId: Cluster whose model produced the detection.
    ///   - trainingRunId: Immutable release identifier paired with the model.
    ///   - detectionConfidence: Raw model confidence in the range 0...1.
    func presentLandmark(
        _ entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
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

        // Keep the existing UI field populated without exposing the S3 folder
        // formatting directly to the user.
        landmarkCategory = entry.datasetClassName
            .replacingOccurrences(of: "_", with: " ")

        let clampedConfidence = min(max(detectionConfidence, 0), 1)
        landmarkConfidence = clampedConfidence * 100

        // Live website/promotion data is fetched from the backend after the
        // popup opens. Reset these so a previous landmark cannot leak into the
        // newly displayed popup.
        landmarkWebsiteUrl = ""
        promoName = "No active promotion"
        promoDescription = ""
        promoImageUrl = ""
        landmarkURL = ""

        infoView = true

        print("""
        ✅ [Local Manifest Popup] Presenting landmark
           clusterId: \(clusterId)
           trainingRunId: \(trainingRunId)
           classIndex: \(entry.classIndex)
           landmarkId: \(entry.landmarkId)
           label: \(entry.label)
           confidence: \(String(format: "%.1f", landmarkConfidence))%
        """)
    }

    /// Closes the popup while retaining the most recently displayed landmark
    /// values for diagnostics.
    func dismissLandmark() {
        infoView = false
        landmarkURL = ""
    }

    /// Clears all landmark-specific state. This can be used when changing
    /// active cluster releases or resetting the scan screen.
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
    }

    func getlandmarkName() -> String {
        landmarkName
    }
}
