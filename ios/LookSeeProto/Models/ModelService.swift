import Foundation
import CoreML
import Combine
import ZIPFoundation

// MARK: - Object Location

struct ObjectLocation {
    let clusterId: String
    let lat: Double
    let lon: Double
}

// MARK: - Model Info

/// One complete, immutable model release.
///
/// A release is only added to `ModelService.state` after both the compiled model
/// and its matching landmark manifest have been downloaded, validated, and stored.
struct ModelInfo: Identifiable {
    var id: String {
        "\(clusterID)|\(modelVersion)"
    }

    let name: String
    let downloadURL: URL
    let manifestURL: URL
    let reason: String

    let clusterID: String
    let modelVersion: String

    let modelKey: String?
    let manifestKey: String?
    let manifestSchemaVersion: Int?
    let classCount: Int?

    var compiledModelURL: URL?
    var manifestFileURL: URL?
    var objects: [ObjectLocation] = []

    var releaseIdentifier: String {
        "\(clusterID)|\(modelVersion)"
    }
}

// MARK: - API Response Shape

private struct ModelsResponse: Decodable {
    let models: [ModelPayload]
    let reason: String

    let location: LocationResponse?
    let objects: [ObjectPayload]?
    let radiusMeters: Double?
    let maxClusters: Int?
    let returnedClusterCount: Int?
}

private struct ModelPayload: Decodable {
    let clusterId: FlexibleStringValue
    let modelVersion: String?

    // `downloadUrl` is kept for compatibility with the current backend response.
    // `modelUrl` is accepted as a fallback.
    let downloadUrl: String?
    let modelUrl: String?
    let manifestUrl: String?

    let modelKey: String?
    let manifestKey: String?
    let releaseKey: String?
    let manifestSchemaVersion: Int?
    let classCount: Int?

    let distanceMeters: Double?
    let closestLandmarkId: String?
    let closestObject: ClosestObjectPayload?

    var resolvedModelURLString: String? {
        downloadUrl ?? modelUrl
    }
}

private struct FlexibleStringValue: Decodable {
    let value: String

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if let stringValue = try? container.decode(String.self) {
            value = stringValue
            return
        }

        if let intValue = try? container.decode(Int.self) {
            value = String(intValue)
            return
        }

        if let doubleValue = try? container.decode(Double.self) {
            if doubleValue.rounded() == doubleValue {
                value = String(Int(doubleValue))
            } else {
                value = String(doubleValue)
            }
            return
        }

        throw DecodingError.typeMismatch(
            String.self,
            DecodingError.Context(
                codingPath: decoder.codingPath,
                debugDescription: "Expected a String, Int, or Double value."
            )
        )
    }
}

private struct ClosestObjectPayload: Decodable {
    let lat: Double
    let lon: Double
}

private struct LocationResponse: Decodable {
    let lat: Double
    let lon: Double
}

private struct ObjectPayload: Decodable {
    let clusterId: FlexibleStringValue
    let landmarkId: String?
    let lat: Double
    let lon: Double
    let distanceMeters: Double?
    let label: String?
    let shortDescription: String?
}

// MARK: - Pull Reason

enum ModelPullReason {
    case none
    case single(reason: String)
    case multiple(reasons: [String])
}

// MARK: - Model State

enum ModelState: Equatable {
    case notLoaded
    case loading
    case loaded([ModelInfo])
    case failed(String)

    static func == (lhs: ModelState, rhs: ModelState) -> Bool {
        switch (lhs, rhs) {
        case (.notLoaded, .notLoaded):
            return true
        case (.loading, .loading):
            return true
        case (.failed, .failed):
            return true
        case (.loaded, .loaded):
            return true
        default:
            return false
        }
    }
}

// MARK: - Release Preparation Errors

private enum ModelReleaseError: LocalizedError {
    case invalidEndpoint
    case invalidHTTPResponse
    case serverError(Int)
    case missingModelURL(clusterID: String)
    case invalidModelURL(clusterID: String)
    case missingManifestURL(clusterID: String)
    case invalidManifestURL(clusterID: String)
    case missingModelVersion(clusterID: String)
    case nonNumericClusterID(String)
    case manifestClusterMismatch(expected: Int, actual: Int)
    case manifestVersionMismatch(expected: String, actual: String)
    case manifestSchemaMismatch(expected: Int, actual: Int)
    case manifestClassCountMismatch(expected: Int, actual: Int)
    case modelDownloadFailed(Int)
    case manifestDownloadFailed(Int)
    case missingMLPackage(clusterID: String)
    case cacheIncomplete(clusterID: String, modelVersion: String)

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint:
            return "The model discovery endpoint URL is invalid."

        case .invalidHTTPResponse:
            return "The server returned an invalid HTTP response."

        case .serverError(let statusCode):
            return "The server returned HTTP \(statusCode)."

        case .missingModelURL(let clusterID):
            return "Cluster \(clusterID) did not include a model download URL."

        case .invalidModelURL(let clusterID):
            return "Cluster \(clusterID) included an invalid model download URL."

        case .missingManifestURL(let clusterID):
            return "Cluster \(clusterID) did not include a landmark manifest URL."

        case .invalidManifestURL(let clusterID):
            return "Cluster \(clusterID) included an invalid landmark manifest URL."

        case .missingModelVersion(let clusterID):
            return "Cluster \(clusterID) did not include a modelVersion."

        case .nonNumericClusterID(let clusterID):
            return "The landmark manifest requires a numeric cluster ID, but received \(clusterID)."

        case .manifestClusterMismatch(let expected, let actual):
            return "The manifest cluster ID \(actual) does not match the release cluster ID \(expected)."

        case .manifestVersionMismatch(let expected, let actual):
            return "The manifest trainingRunId \(actual) does not match modelVersion \(expected)."

        case .manifestSchemaMismatch(let expected, let actual):
            return "The manifest schema version \(actual) does not match the API value \(expected)."

        case .manifestClassCountMismatch(let expected, let actual):
            return "The manifest class count \(actual) does not match the API value \(expected)."

        case .modelDownloadFailed(let statusCode):
            return "The model download returned HTTP \(statusCode)."

        case .manifestDownloadFailed(let statusCode):
            return "The landmark manifest download returned HTTP \(statusCode)."

        case .missingMLPackage(let clusterID):
            return "No .mlpackage was found in the downloaded ZIP for cluster \(clusterID)."

        case .cacheIncomplete(let clusterID, let modelVersion):
            return "The cached release for cluster \(clusterID), version \(modelVersion), is incomplete."
        }
    }
}

// MARK: - Prepared Release

private struct PreparedRelease {
    let compiledModelURL: URL
    let manifestFileURL: URL
}

// MARK: - Model Service

@MainActor
class ModelService: ObservableObject {
    static let shared = ModelService()

    @Published var state: ModelState = .notLoaded
    @Published var pullReason: ModelPullReason = .none
    @Published var downloadProgress: Double = 0.0
    @Published var updateAvailable: Bool = false

    private let apiURL = "https://o1ul6zexoj.execute-api.us-east-1.amazonaws.com/prod/discover"

    private let fileManager = FileManager.default
    private let manifestDecoder = JSONDecoder()

    private var isRefreshing = false

    private var modelsDirectory: URL {
        FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
        .appendingPathComponent("LookSeeModels", isDirectory: true)
    }

    private init() {
        try? fileManager.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )
    }

    // MARK: - Initial / User-Visible Load

    func loadModels(latitude: Double, longitude: Double) async {
        state = .loading
        pullReason = .none
        downloadProgress = 0.0

        do {
            let models = try await fetchDownloadAndCompileModels(
                latitude: latitude,
                longitude: longitude,
                shouldUpdateProgress: true
            )

            updatePullReason(from: models)
            downloadProgress = 1.0
            state = .loaded(models)

        } catch let error as DecodingError {
            print("❌ Decoding error: \(error)")
            state = .failed(
                "Failed to decode response: \(error.localizedDescription)"
            )
        } catch {
            print("❌ Request error: \(error)")
            state = .failed(
                "Request failed: \(error.localizedDescription)"
            )
        }
    }

    // MARK: - Silent Polling Refresh

    /// Used by `ModelAutoRefreshService`.
    ///
    /// This checks the backend again without putting the app back into `.loading`.
    /// A model version change for the same cluster now counts as a real update.
    @discardableResult
    func refreshModelsSilentlyIfNeeded(
        latitude: Double,
        longitude: Double
    ) async -> Bool {
        guard !isRefreshing else {
            print("⏳ Model silent refresh skipped: refresh already in progress")
            return false
        }

        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let oldSignature = currentLoadedModelSignature()

            let newModels = try await fetchDownloadAndCompileModels(
                latitude: latitude,
                longitude: longitude,
                shouldUpdateProgress: false
            )

            let newSignature = modelSignature(for: newModels)

            guard oldSignature != newSignature else {
                print("✅ Model silent refresh complete: model set unchanged")
                return false
            }

            print("🔁 Model silent refresh found updated model set")
            print("   Old: \(oldSignature)")
            print("   New: \(newSignature)")

            updatePullReason(from: newModels)
            state = .loaded(newModels)

            return true

        } catch {
            print(
                "⚠️ Model silent refresh failed, keeping existing models: " +
                error.localizedDescription
            )
            return false
        }
    }

    // MARK: - Shared Fetch / Download / Compile Logic

    private func fetchDownloadAndCompileModels(
        latitude: Double,
        longitude: Double,
        shouldUpdateProgress: Bool
    ) async throws -> [ModelInfo] {
        guard let endpoint = URL(string: apiURL) else {
            throw ModelReleaseError.invalidEndpoint
        }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Content-Type"
        )

        let body: [String: Double] = [
            "latitude": latitude,
            "longitude": longitude
        ]

        request.httpBody = try JSONSerialization.data(
            withJSONObject: body
        )

        let (data, response) = try await URLSession.shared.data(
            for: request
        )

        if let raw = String(data: data, encoding: .utf8) {
            print("✅ Raw response: \(raw)")
        }

        guard let http = response as? HTTPURLResponse else {
            throw ModelReleaseError.invalidHTTPResponse
        }

        guard (200...299).contains(http.statusCode) else {
            let raw = String(data: data, encoding: .utf8) ?? "no body"
            print("❌ HTTP \(http.statusCode): \(raw)")
            throw ModelReleaseError.serverError(http.statusCode)
        }

        let parsed = try JSONDecoder().decode(
            ModelsResponse.self,
            from: data
        )

        print("📍 Discover reason: \(parsed.reason)")
        print("📦 Returned model records: \(parsed.models.count)")
        print("🧭 Returned object records: \(parsed.objects?.count ?? 0)")

        if shouldUpdateProgress {
            downloadProgress = 0.15
        }

        let allObjects: [ObjectLocation] = (parsed.objects ?? []).map {
            ObjectLocation(
                clusterId: normalizeClusterID($0.clusterId.value),
                lat: $0.lat,
                lon: $0.lon
            )
        }

        guard !parsed.models.isEmpty else {
            print("⚠️ No model releases returned. Reason: \(parsed.reason)")

            if shouldUpdateProgress {
                pullReason = .none
                downloadProgress = 1.0
            }

            return []
        }

        var models: [ModelInfo] = []
        let progressPerModel = 0.85 / Double(max(parsed.models.count, 1))

        for (index, payload) in parsed.models.enumerated() {
            let clusterID = normalizeClusterID(payload.clusterId.value)

            do {
                let modelInfo = try await prepareModelInfo(
                    payload: payload,
                    reason: parsed.reason,
                    allObjects: allObjects
                )

                models.append(modelInfo)

                print(
                    "✅ Complete release ready: " +
                    "cluster=\(modelInfo.clusterID), " +
                    "version=\(modelInfo.modelVersion)"
                )
            } catch {
                print(
                    "❌ Skipping incomplete release for cluster \(clusterID): " +
                    error.localizedDescription
                )
            }

            if shouldUpdateProgress {
                downloadProgress =
                    0.15 + progressPerModel * Double(index + 1)
            }
        }

        return models
    }

    private func prepareModelInfo(
        payload: ModelPayload,
        reason: String,
        allObjects: [ObjectLocation]
    ) async throws -> ModelInfo {
        let clusterID = normalizeClusterID(payload.clusterId.value)

        guard let modelVersion = payload.modelVersion?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !modelVersion.isEmpty else {
            throw ModelReleaseError.missingModelVersion(
                clusterID: clusterID
            )
        }

        guard let modelURLString = payload.resolvedModelURLString else {
            throw ModelReleaseError.missingModelURL(
                clusterID: clusterID
            )
        }

        guard let modelURL = URL(string: modelURLString) else {
            throw ModelReleaseError.invalidModelURL(
                clusterID: clusterID
            )
        }

        guard let manifestURLString = payload.manifestUrl else {
            throw ModelReleaseError.missingManifestURL(
                clusterID: clusterID
            )
        }

        guard let manifestURL = URL(string: manifestURLString) else {
            throw ModelReleaseError.invalidManifestURL(
                clusterID: clusterID
            )
        }

        let preparedRelease = try await prepareRelease(
            clusterID: clusterID,
            modelVersion: modelVersion,
            modelURL: modelURL,
            manifestURL: manifestURL,
            expectedManifestSchemaVersion:
                payload.manifestSchemaVersion,
            expectedClassCount: payload.classCount
        )

        let modelObjects = allObjects.filter {
            $0.clusterId == clusterID
        }

        return ModelInfo(
            name: clusterID,
            downloadURL: modelURL,
            manifestURL: manifestURL,
            reason: reason,
            clusterID: clusterID,
            modelVersion: modelVersion,
            modelKey: payload.modelKey,
            manifestKey: payload.manifestKey,
            manifestSchemaVersion: payload.manifestSchemaVersion,
            classCount: payload.classCount,
            compiledModelURL: preparedRelease.compiledModelURL,
            manifestFileURL: preparedRelease.manifestFileURL,
            objects: modelObjects
        )
    }

    // MARK: - Release Cache / Installation

    private func prepareRelease(
        clusterID: String,
        modelVersion: String,
        modelURL: URL,
        manifestURL: URL,
        expectedManifestSchemaVersion: Int?,
        expectedClassCount: Int?
    ) async throws -> PreparedRelease {
        let releaseDirectory = releaseDirectoryURL(
            clusterID: clusterID,
            modelVersion: modelVersion
        )

        let compiledModelURL = releaseDirectory
            .appendingPathComponent("Model.mlmodelc", isDirectory: true)

        let manifestFileURL = releaseDirectory
            .appendingPathComponent("landmark-manifest.json")

        let modelExists = fileManager.fileExists(
            atPath: compiledModelURL.path
        )
        let manifestExists = fileManager.fileExists(
            atPath: manifestFileURL.path
        )

        if modelExists && manifestExists {
            do {
                let manifestData = try Data(
                    contentsOf: manifestFileURL
                )

                let manifest = try manifestDecoder.decode(
                    ClusterLandmarkManifest.self,
                    from: manifestData
                )

                try validateManifestIdentity(
                    manifest,
                    clusterID: clusterID,
                    modelVersion: modelVersion,
                    expectedSchemaVersion:
                        expectedManifestSchemaVersion,
                    expectedClassCount: expectedClassCount
                )

                try LandmarkManifestStore.shared.register(
                    manifest
                )

                print(
                    "♻️ Using cached complete release " +
                    "cluster=\(clusterID), version=\(modelVersion)"
                )

                return PreparedRelease(
                    compiledModelURL: compiledModelURL,
                    manifestFileURL: manifestFileURL
                )
            } catch {
                print(
                    "⚠️ Cached release failed validation and will be replaced: " +
                    error.localizedDescription
                )

                try? fileManager.removeItem(at: releaseDirectory)
            }
        } else if modelExists || manifestExists {
            print(
                "⚠️ Removing partial cached release " +
                "cluster=\(clusterID), version=\(modelVersion)"
            )

            try? fileManager.removeItem(at: releaseDirectory)
        }

        return try await downloadCompileAndInstallRelease(
            clusterID: clusterID,
            modelVersion: modelVersion,
            modelURL: modelURL,
            manifestURL: manifestURL,
            finalReleaseDirectory: releaseDirectory,
            expectedManifestSchemaVersion:
                expectedManifestSchemaVersion,
            expectedClassCount: expectedClassCount
        )
    }

    private func downloadCompileAndInstallRelease(
        clusterID: String,
        modelVersion: String,
        modelURL: URL,
        manifestURL: URL,
        finalReleaseDirectory: URL,
        expectedManifestSchemaVersion: Int?,
        expectedClassCount: Int?
    ) async throws -> PreparedRelease {
        try fileManager.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )

        let stagingDirectory = modelsDirectory.appendingPathComponent(
            ".staging-\(UUID().uuidString)",
            isDirectory: true
        )

        let workDirectory = stagingDirectory.appendingPathComponent(
            "_work",
            isDirectory: true
        )

        let unzipDirectory = workDirectory.appendingPathComponent(
            "unzipped",
            isDirectory: true
        )

        let stagedManifestURL = stagingDirectory
            .appendingPathComponent("landmark-manifest.json")

        let stagedCompiledURL = stagingDirectory
            .appendingPathComponent("Model.mlmodelc", isDirectory: true)

        var downloadedZipURL: URL?

        defer {
            if fileManager.fileExists(atPath: stagingDirectory.path) {
                try? fileManager.removeItem(at: stagingDirectory)
            }

            if let downloadedZipURL {
                try? fileManager.removeItem(at: downloadedZipURL)
            }
        }

        try fileManager.createDirectory(
            at: unzipDirectory,
            withIntermediateDirectories: true
        )

        // Download and validate the manifest first. A model is never activated
        // when its metadata is absent or does not match the release identity.
        print(
            "⬇️ Downloading landmark manifest " +
            "cluster=\(clusterID), version=\(modelVersion)"
        )

        let (manifestData, manifestResponse) =
            try await URLSession.shared.data(from: manifestURL)

        if let http = manifestResponse as? HTTPURLResponse,
           !(200...299).contains(http.statusCode) {
            throw ModelReleaseError.manifestDownloadFailed(
                http.statusCode
            )
        }

        let manifest = try manifestDecoder.decode(
            ClusterLandmarkManifest.self,
            from: manifestData
        )

        try validateManifestIdentity(
            manifest,
            clusterID: clusterID,
            modelVersion: modelVersion,
            expectedSchemaVersion: expectedManifestSchemaVersion,
            expectedClassCount: expectedClassCount
        )

        try manifestData.write(
            to: stagedManifestURL,
            options: .atomic
        )

        print(
            "✅ Landmark manifest validated " +
            "cluster=\(manifest.clusterId), " +
            "version=\(manifest.trainingRunId), " +
            "classes=\(manifest.classCount)"
        )

        print(
            "⬇️ Downloading model ZIP " +
            "cluster=\(clusterID), version=\(modelVersion)"
        )

        let (temporaryZipURL, modelResponse) =
            try await URLSession.shared.download(from: modelURL)

        downloadedZipURL = temporaryZipURL

        if let http = modelResponse as? HTTPURLResponse,
           !(200...299).contains(http.statusCode) {
            throw ModelReleaseError.modelDownloadFailed(
                http.statusCode
            )
        }

        print("📦 Unzipping model release...")
        try unzip(
            zipURL: temporaryZipURL,
            to: unzipDirectory
        )

        guard let mlpackageURL = findAnyMLPackage(
            in: unzipDirectory
        ) else {
            throw ModelReleaseError.missingMLPackage(
                clusterID: clusterID
            )
        }

        print("⚙️ Compiling \(mlpackageURL.lastPathComponent)...")
        let temporaryCompiledURL =
            try await MLModel.compileModel(at: mlpackageURL)

        if fileManager.fileExists(atPath: stagedCompiledURL.path) {
            try fileManager.removeItem(at: stagedCompiledURL)
        }

        try fileManager.moveItem(
            at: temporaryCompiledURL,
            to: stagedCompiledURL
        )

        // Do not carry extraction files into the permanent release directory.
        try? fileManager.removeItem(at: workDirectory)

        let parentDirectory =
            finalReleaseDirectory.deletingLastPathComponent()

        try fileManager.createDirectory(
            at: parentDirectory,
            withIntermediateDirectories: true
        )

        if fileManager.fileExists(
            atPath: finalReleaseDirectory.path
        ) {
            try fileManager.removeItem(
                at: finalReleaseDirectory
            )
        }

        // The complete staging folder becomes visible as one versioned release.
        try fileManager.moveItem(
            at: stagingDirectory,
            to: finalReleaseDirectory
        )

        let finalCompiledURL = finalReleaseDirectory
            .appendingPathComponent(
                "Model.mlmodelc",
                isDirectory: true
            )

        let finalManifestURL = finalReleaseDirectory
            .appendingPathComponent("landmark-manifest.json")

        do {
            try LandmarkManifestStore.shared.register(manifest)
        } catch {
            try? fileManager.removeItem(
                at: finalReleaseDirectory
            )
            throw error
        }

        print(
            "✅ Release installed at " +
            finalReleaseDirectory.path
        )

        return PreparedRelease(
            compiledModelURL: finalCompiledURL,
            manifestFileURL: finalManifestURL
        )
    }

    // MARK: - Manifest Validation

    private func validateManifestIdentity(
        _ manifest: ClusterLandmarkManifest,
        clusterID: String,
        modelVersion: String,
        expectedSchemaVersion: Int?,
        expectedClassCount: Int?
    ) throws {
        try manifest.validate()

        guard let numericClusterID = Int(
            normalizeClusterID(clusterID)
        ) else {
            throw ModelReleaseError.nonNumericClusterID(
                clusterID
            )
        }

        guard manifest.clusterId == numericClusterID else {
            throw ModelReleaseError.manifestClusterMismatch(
                expected: numericClusterID,
                actual: manifest.clusterId
            )
        }

        guard manifest.trainingRunId == modelVersion else {
            throw ModelReleaseError.manifestVersionMismatch(
                expected: modelVersion,
                actual: manifest.trainingRunId
            )
        }

        if let expectedSchemaVersion,
           manifest.schemaVersion != expectedSchemaVersion {
            throw ModelReleaseError.manifestSchemaMismatch(
                expected: expectedSchemaVersion,
                actual: manifest.schemaVersion
            )
        }

        if let expectedClassCount,
           manifest.classCount != expectedClassCount {
            throw ModelReleaseError.manifestClassCountMismatch(
                expected: expectedClassCount,
                actual: manifest.classCount
            )
        }
    }

    // MARK: - File Helpers

    private func releaseDirectoryURL(
        clusterID: String,
        modelVersion: String
    ) -> URL {
        let clusterComponent = sanitizePathComponent(
            normalizeClusterID(clusterID),
            fallback: "unknown-cluster"
        )

        let versionComponent = sanitizePathComponent(
            modelVersion,
            fallback: "unknown-version"
        )

        return modelsDirectory
            .appendingPathComponent(
                "cluster-\(clusterComponent)",
                isDirectory: true
            )
            .appendingPathComponent(
                versionComponent,
                isDirectory: true
            )
    }

    private func sanitizePathComponent(
        _ value: String,
        fallback: String
    ) -> String {
        let allowed =
            CharacterSet.alphanumerics.union(
                CharacterSet(charactersIn: "._-")
            )

        let sanitizedScalars = value.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? Character(String(scalar)) : "-"
        }

        let sanitized = String(sanitizedScalars)
            .trimmingCharacters(
                in: CharacterSet(charactersIn: "-._")
            )

        return sanitized.isEmpty ? fallback : sanitized
    }

    private func normalizeClusterID(_ rawValue: String) -> String {
        var value = rawValue
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let lowered = value.lowercased()

        if lowered.hasPrefix("cluster-") {
            value = String(value.dropFirst("cluster-".count))
        } else if lowered.hasPrefix("cluster_") {
            value = String(value.dropFirst("cluster_".count))
        }

        if let integer = Int(value) {
            return String(integer)
        }

        if let double = Double(value),
           double.rounded() == double {
            return String(Int(double))
        }

        return value
    }

    private func unzip(
        zipURL: URL,
        to destination: URL
    ) throws {
        try fileManager.unzipItem(
            at: zipURL,
            to: destination
        )
    }

    private func findAnyMLPackage(
        in directory: URL
    ) -> URL? {
        guard let enumerator = fileManager.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        for case let url as URL in enumerator {
            if url.pathExtension.lowercased() == "mlpackage" {
                return url
            }
        }

        return nil
    }

    // MARK: - State Helpers

    private func updatePullReason(
        from models: [ModelInfo]
    ) {
        let completeModels = models.filter {
            $0.compiledModelURL != nil &&
            $0.manifestFileURL != nil
        }

        pullReason = switch completeModels.count {
        case 0:
            .none
        case 1:
            .single(reason: completeModels[0].reason)
        default:
            .multiple(
                reasons: completeModels.map { $0.reason }
            )
        }
    }

    private func currentLoadedModelSignature() -> [String] {
        switch state {
        case .loaded(let models):
            return modelSignature(for: models)
        default:
            return []
        }
    }

    private func modelSignature(
        for models: [ModelInfo]
    ) -> [String] {
        models
            .filter {
                $0.compiledModelURL != nil &&
                $0.manifestFileURL != nil
            }
            .map { model in
                [
                    model.clusterID,
                    model.modelVersion,
                    model.modelKey ?? "no-model-key",
                    model.manifestKey ?? "no-manifest-key",
                    String(model.objects.count)
                ]
                .joined(separator: "|")
            }
            .sorted()
    }

    // MARK: - Reload Models

    func reloadModels(
        latitude: Double,
        longitude: Double
    ) async {
        try? fileManager.removeItem(at: modelsDirectory)

        try? fileManager.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )

        LandmarkManifestStore.shared.removeAll()

        state = .notLoaded
        pullReason = .none
        downloadProgress = 0.0

        await loadModels(
            latitude: latitude,
            longitude: longitude
        )
    }

    // MARK: - Check for Updates

    func checkForUpdates(
        latitude: Double,
        longitude: Double
    ) async {
        let changed = await refreshModelsSilentlyIfNeeded(
            latitude: latitude,
            longitude: longitude
        )

        updateAvailable = changed
    }

    // MARK: - Delete Models

    func deleteModels() throws {
        if fileManager.fileExists(atPath: modelsDirectory.path) {
            try fileManager.removeItem(at: modelsDirectory)
        }

        try fileManager.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )

        LandmarkManifestStore.shared.removeAll()

        state = .notLoaded
        pullReason = .none
        updateAvailable = false
        downloadProgress = 0.0
    }

    // MARK: - Movement Check

    func checkIfShouldReload(
        latitude: Double,
        longitude: Double
    ) async {
        await refreshModelsSilentlyIfNeeded(
            latitude: latitude,
            longitude: longitude
        )
    }
}
