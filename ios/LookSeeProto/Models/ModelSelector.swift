//
//  ModelSelector.swift
//  LookSeeProto
//
//  Selects one complete model release based on the user's location.
//  A release is identified by cluster ID + model version, not cluster ID alone.
//

import Foundation
import CoreLocation
import Combine

// MARK: - Temporary bundled-model testing

/// Set this to `false` to remove the model-testing UI and return the app to
/// location-based model selection. The rest of the production model flow does
/// not depend on this testing feature.
enum ModelTestingConfiguration {
    static let isEnabled = true

    /// Xcode compiles every `.mlmodel` / `.mlpackage` in the app target into a
    /// `.mlmodelc` resource. Discovering those resources here keeps the testing
    /// feature independent of model filenames and makes adding a new candidate
    /// a target-membership change rather than a code change.
    static var models: [BundledTestModel] {
        guard isEnabled else { return [] }

        let urls = Bundle.main.urls(
            forResourcesWithExtension: "mlmodelc",
            subdirectory: nil
        ) ?? []

        return urls
            .map(BundledTestModel.init(compiledModelURL:))
            .sorted {
                $0.displayName.localizedStandardCompare($1.displayName)
                    == .orderedAscending
            }
    }
}

struct BundledTestModel: Identifiable, Equatable {
    var id: String { resourceName }

    let compiledModelURL: URL

    var resourceName: String {
        compiledModelURL.deletingPathExtension().lastPathComponent
    }

    var displayName: String {
        resourceName.replacingOccurrences(of: "_", with: " ")
    }

    var detail: String {
        "\(resourceName).mlmodelc"
    }

    /// Optional human-readable labels can be added later if a model does not
    /// have a matching production manifest. Index-based labels remain usable
    /// for side-by-side model testing.
    var classLabels: [String] {
        []
    }

    var isInstalled: Bool {
        FileManager.default.fileExists(atPath: compiledModelURL.path)
    }
}

/// The complete identity Detector needs to run a model and later resolve
/// its class indexes through the matching landmark manifest.
struct ActiveModelRelease: Identifiable, Equatable {
    var id: String {
        releaseIdentifier
    }

    let clusterID: String
    let modelVersion: String
    let compiledModelURL: URL
    let manifestFileURL: URL?
    let classCount: Int
    let displayName: String
    let classLabels: [String]

    let modelKey: String?
    let manifestKey: String?

    var releaseIdentifier: String {
        "\(clusterID)|\(modelVersion)"
    }
}

@MainActor
final class ModelSelector: ObservableObject {
    static let shared = ModelSelector()

    /// Release-aware selection used by the updated Detector.
    @Published private(set) var activeRelease: ActiveModelRelease?

    /// Temporary compatibility property for existing code that still observes
    /// only the cluster ID. This stays synchronized with `activeRelease`.
    @Published private(set) var activeClusterID: String?

    /// Non-nil while the temporary bundled-model override is active.
    @Published private(set) var selectedTestModelID: String?

    var activeModelVersion: String? {
        activeRelease?.modelVersion
    }

    var activeClassCount: Int? {
        activeRelease?.classCount
    }

    // How close the user must be to an object to activate its model.
    private let activationRadiusMeters: Double = 75.0

    private var models: [ModelInfo] = []
    private var latestUserLocation: CLLocation?

    private let selectedTestModelDefaultsKey =
        "looksee.selectedBundledTestModel"

    private init() {
        restoreTestSelection()
        observeModelState()
    }

    var availableTestModels: [BundledTestModel] {
        ModelTestingConfiguration.isEnabled
            ? ModelTestingConfiguration.models
            : []
    }

    var activeDisplayName: String {
        activeRelease?.displayName ?? "No model loaded"
    }

    func selectTestModel(_ testModel: BundledTestModel) {
        guard
            ModelTestingConfiguration.isEnabled,
            testModel.isInstalled
        else { return }

        selectedTestModelID = testModel.id
        UserDefaults.standard.set(
            testModel.id,
            forKey: selectedTestModelDefaultsKey
        )
        activate(testModel)
    }

    /// Leaves testing mode and immediately returns selection ownership to the
    /// normal location-based production flow.
    func useAutomaticModelSelection() {
        guard selectedTestModelID != nil else { return }

        selectedTestModelID = nil
        UserDefaults.standard.removeObject(
            forKey: selectedTestModelDefaultsKey
        )

        if let latestUserLocation {
            chooseBestRelease(for: latestUserLocation)
        } else {
            chooseDefaultRelease()
        }
    }

    private func restoreTestSelection() {
        guard ModelTestingConfiguration.isEnabled else { return }

        let savedID = UserDefaults.standard.string(
            forKey: selectedTestModelDefaultsKey
        )
        let selected = ModelTestingConfiguration.models.first {
            $0.id == savedID && $0.isInstalled
        }

        guard let selected else { return }
        selectedTestModelID = selected.id
        activate(selected)
    }

    // MARK: - Watch ModelService State

    private func observeModelState() {
        Task { [weak self] in
            guard let self else { return }

            for await state in ModelService.shared.$state.values {
                switch state {
                case .loaded(let loadedModels):
                    self.models = loadedModels

                    let completeCount = loadedModels.filter {
                        self.isCompleteRelease($0)
                    }.count

                    print(
                        "🧠 ModelSelector received " +
                        "\(loadedModels.count) loaded model records " +
                        "(\(completeCount) complete releases)"
                    )

                    if self.reactivateSelectedTestModelIfNeeded() {
                        continue
                    } else if let latestUserLocation = self.latestUserLocation {
                        self.chooseBestRelease(
                            for: latestUserLocation
                        )
                    } else {
                        self.chooseDefaultRelease()
                    }

                case .notLoaded:
                    self.models = []
                    if !self.reactivateSelectedTestModelIfNeeded() {
                        self.clearActiveRelease(
                            reason: "ModelService is not loaded"
                        )
                    }

                case .loading:
                    // Keep the current complete release active while a
                    // user-visible refresh is in progress.
                    break

                case .failed(let message):
                    // Keep the current release active on a network/backend
                    // failure. It remains a complete local release.
                    print(
                        "⚠️ ModelService failed; keeping active release: " +
                        message
                    )
                }
            }
        }
    }

    // MARK: - Update with User Location

    func updateUserLocation(
        latitude: Double,
        longitude: Double
    ) {
        let userLocation = CLLocation(
            latitude: latitude,
            longitude: longitude
        )

        latestUserLocation = userLocation

        guard !reactivateSelectedTestModelIfNeeded() else {
            return
        }

        chooseBestRelease(for: userLocation)
    }

    // MARK: - Selection Logic

    private func chooseBestRelease(
        for userLocation: CLLocation
    ) {
        var closestModel: ModelInfo?
        var closestDistance: Double = .infinity

        for model in models {
            guard isCompleteRelease(model) else {
                continue
            }

            for object in model.objects {
                let objectLocation = CLLocation(
                    latitude: object.lat,
                    longitude: object.lon
                )

                let distance = userLocation.distance(
                    from: objectLocation
                )

                if distance <= activationRadiusMeters,
                   distance < closestDistance {
                    closestDistance = distance
                    closestModel = model
                }
            }
        }

        if let closestModel {
            activate(
                closestModel,
                reason:
                    "closest object is " +
                    "\(String(format: "%.1f", closestDistance))m away"
            )
            return
        }

        // No candidate is currently inside the activation radius. Keep the
        // active release only when that exact cluster + version remains loaded.
        if let current = activeRelease,
           models.contains(where: {
               $0.clusterID == current.clusterID &&
               $0.modelVersion == current.modelVersion &&
               isCompleteRelease($0)
           }) {
            print(
                "⚠️ No objects within " +
                "\(String(format: "%.1f", activationRadiusMeters))m — " +
                "keeping release \(current.releaseIdentifier) active"
            )
            return
        }

        chooseDefaultRelease()
    }

    private func chooseDefaultRelease() {
        guard let fallback = models.first(where: {
            isCompleteRelease($0)
        }) else {
            clearActiveRelease(
                reason: "No complete model releases available"
            )
            return
        }

        activate(
            fallback,
            reason: "defaulting to first complete loaded release"
        )
    }

    private func activate(
        _ model: ModelInfo,
        reason: String
    ) {
        guard let candidate = makeActiveRelease(from: model) else {
            print(
                "⚠️ Refusing to activate incomplete release " +
                "cluster=\(model.clusterID), " +
                "version=\(model.modelVersion)"
            )
            return
        }

        guard candidate != activeRelease else {
            // The same exact release is already active.
            return
        }

        let previous = activeRelease?.releaseIdentifier ?? "none"

        activeRelease = candidate
        activeClusterID = candidate.clusterID

        print("")
        print("📍 Active model release changed")
        print("   previous: \(previous)")
        print("   current: \(candidate.releaseIdentifier)")
        print("   clusterID: \(candidate.clusterID)")
        print("   modelVersion: \(candidate.modelVersion)")
        print("   classCount: \(candidate.classCount)")
        print(
            "   compiledModel: " +
            candidate.compiledModelURL.lastPathComponent
        )
        print(
            "   manifest: " +
            (candidate.manifestFileURL?.lastPathComponent ?? "none")
        )
        print("   reason: \(reason)")
        print("")
    }

    @discardableResult
    private func activate(_ testModel: BundledTestModel) -> Bool {
        guard testModel.isInstalled else {
            print(
                "❌ Bundled test model is missing: " +
                "\(testModel.resourceName).mlmodelc"
            )
            return false
        }

        let candidate = ActiveModelRelease(
            clusterID: "bundled-test",
            modelVersion: "bundled-\(testModel.resourceName)",
            compiledModelURL: testModel.compiledModelURL,
            manifestFileURL: nil,
            // Zero tells Detector to infer this from the model's outputs.
            classCount: testModel.classLabels.count,
            displayName: testModel.displayName,
            classLabels: testModel.classLabels,
            modelKey: testModel.resourceName,
            manifestKey: nil
        )

        guard candidate != activeRelease else { return true }

        activeRelease = candidate
        activeClusterID = candidate.clusterID

        print(
            "🧪 Bundled test model selected: " +
            "\(testModel.displayName)"
        )
        return true
    }

    @discardableResult
    private func reactivateSelectedTestModelIfNeeded() -> Bool {
        guard
            ModelTestingConfiguration.isEnabled,
            let selectedTestModelID,
            let selected = ModelTestingConfiguration.models.first(
                where: { $0.id == selectedTestModelID }
            )
        else {
            return false
        }

        return activate(selected)
    }

    private func clearActiveRelease(
        reason: String
    ) {
        guard activeRelease != nil || activeClusterID != nil else {
            if models.isEmpty {
                print("⚠️ \(reason)")
            }
            return
        }

        print("🧠 Clearing active model release: \(reason)")
        activeRelease = nil
        activeClusterID = nil
    }

    // MARK: - Release Validation / Conversion

    private func isCompleteRelease(
        _ model: ModelInfo
    ) -> Bool {
        guard
            let compiledModelURL = model.compiledModelURL,
            let manifestFileURL = model.manifestFileURL,
            model.classCount != nil
        else {
            return false
        }

        return FileManager.default.fileExists(
            atPath: compiledModelURL.path
        ) &&
        FileManager.default.fileExists(
            atPath: manifestFileURL.path
        )
    }

    private func makeActiveRelease(
        from model: ModelInfo
    ) -> ActiveModelRelease? {
        guard
            let compiledModelURL = model.compiledModelURL,
            let manifestFileURL = model.manifestFileURL,
            let classCount = model.classCount,
            classCount >= 0
        else {
            return nil
        }

        guard
            FileManager.default.fileExists(
                atPath: compiledModelURL.path
            ),
            FileManager.default.fileExists(
                atPath: manifestFileURL.path
            )
        else {
            return nil
        }

        return ActiveModelRelease(
            clusterID: model.clusterID,
            modelVersion: model.modelVersion,
            compiledModelURL: compiledModelURL,
            manifestFileURL: manifestFileURL,
            classCount: classCount,
            displayName: "Cluster \(model.clusterID)",
            classLabels: [],
            modelKey: model.modelKey,
            manifestKey: model.manifestKey
        )
    }
}
