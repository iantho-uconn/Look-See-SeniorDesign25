import Foundation
import CoreML
import Combine
import ZIPFoundation

// MARK: - Object Location
// Represents a detectable object's GPS position and which model/cluster it belongs to.
struct ObjectLocation {
    let clusterId: String
    let lat: Double
    let lon: Double
}

// MARK: - Model Info
struct ModelInfo: Identifiable {
    let id = UUID()
    let name: String
    let downloadURL: URL
    let reason: String
    let clusterID: String
    var compiledModelURL: URL?
    var objects: [ObjectLocation] = []
}

// MARK: - API Response Shape

private struct ModelsResponse: Decodable {
    let models: [ModelPayload]
    let reason: String

    // These are optional so older/incomplete backend responses do not crash decoding.
    let location: LocationResponse?
    let objects: [ObjectPayload]?
    let radiusMeters: Double?
    let maxClusters: Int?
    let returnedClusterCount: Int?
}

private struct ModelPayload: Decodable {
    let clusterId: String

    // Important:
    // These are optional because the backend may find a nearby cluster,
    // but the model zip may not exist in S3 yet.
    let downloadUrl: String?
    let modelKey: String?

    // Extra debug/context fields from the backend.
    let distanceMeters: Double?
    let closestLandmarkId: String?
    let closestObject: ClosestObjectPayload?
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
    let clusterId: String
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

// MARK: - Model Service

@MainActor
class ModelService: ObservableObject {
    static let shared = ModelService()

    @Published var state: ModelState = .notLoaded
    @Published var pullReason: ModelPullReason = .none
    @Published var downloadProgress: Double = 0.0
    @Published var updateAvailable: Bool = false

    private let apiURL = "https://o1ul6zexoj.execute-api.us-east-1.amazonaws.com/prod/discover"

    private var modelsDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LookSeeModels", isDirectory: true)
    }

    private init() {
        try? FileManager.default.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )
    }

    // MARK: - Load Models

    func loadModels(latitude: Double, longitude: Double) async {
        state = .loading
        pullReason = .none
        downloadProgress = 0.0

        do {
            guard let endpoint = URL(string: apiURL) else {
                state = .failed("Invalid endpoint URL")
                return
            }

            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")

            let body: [String: Double] = [
                "latitude": latitude,
                "longitude": longitude
            ]

            request.httpBody = try JSONSerialization.data(withJSONObject: body)

            let (data, response) = try await URLSession.shared.data(for: request)

            if let raw = String(data: data, encoding: .utf8) {
                print("✅ Raw response: \(raw)")
            }

            guard let http = response as? HTTPURLResponse else {
                state = .failed("Invalid response from server")
                return
            }

            guard (200...299).contains(http.statusCode) else {
                let raw = String(data: data, encoding: .utf8) ?? "no body"
                print("❌ HTTP \(http.statusCode): \(raw)")
                state = .failed("Server error: HTTP \(http.statusCode)")
                return
            }

            let parsed = try JSONDecoder().decode(ModelsResponse.self, from: data)

            print("📍 Discover reason: \(parsed.reason)")
            print("📦 Returned model records: \(parsed.models.count)")
            print("🧭 Returned object records: \(parsed.objects?.count ?? 0)")

            downloadProgress = 0.2

            let allObjects: [ObjectLocation] = (parsed.objects ?? []).map {
                ObjectLocation(
                    clusterId: $0.clusterId,
                    lat: $0.lat,
                    lon: $0.lon
                )
            }

            // Only attempt to download models that actually have a usable URL.
            // This prevents a valid backend response with downloadUrl: null from crashing the app.
            let downloadablePayloads = parsed.models.filter { payload in
                guard let urlString = payload.downloadUrl else {
                    print("⚠️ Cluster \(payload.clusterId) has no downloadUrl. Skipping.")
                    return false
                }

                guard URL(string: urlString) != nil else {
                    print("⚠️ Cluster \(payload.clusterId) has invalid downloadUrl. Skipping.")
                    return false
                }

                return true
            }

            guard !downloadablePayloads.isEmpty else {
                print("⚠️ No downloadable models returned. Reason: \(parsed.reason)")
                pullReason = .none
                downloadProgress = 1.0
                state = .loaded([])
                return
            }

            var models: [ModelInfo] = []
            let progressPerModel = 0.8 / Double(max(downloadablePayloads.count, 1))

            for (index, payload) in downloadablePayloads.enumerated() {
                guard let urlString = payload.downloadUrl,
                      let remoteURL = URL(string: urlString) else {
                    continue
                }

                let modelObjects = allObjects.filter {
                    $0.clusterId == payload.clusterId
                }

                var info = ModelInfo(
                    name: payload.clusterId,
                    downloadURL: remoteURL,
                    reason: parsed.reason,
                    clusterID: payload.clusterId,
                    objects: modelObjects
                )

                do {
                    let compiled = try await downloadAndCompile(
                        remoteURL: remoteURL,
                        clusterID: payload.clusterId
                    )

                    info.compiledModelURL = compiled
                    print("✅ Model compiled for cluster \(payload.clusterId): \(compiled.lastPathComponent)")
                } catch {
                    print("❌ Failed to download/compile cluster \(payload.clusterId): \(error.localizedDescription)")
                }

                // Keep the model info even if compile failed.
                // ModelSelector will ignore it unless compiledModelURL is non-nil.
                models.append(info)

                downloadProgress = 0.2 + progressPerModel * Double(index + 1)
            }

            let successfullyCompiledModels = models.filter {
                $0.compiledModelURL != nil
            }

            pullReason = switch successfullyCompiledModels.count {
            case 0:
                .none
            case 1:
                .single(reason: parsed.reason)
            default:
                .multiple(reasons: successfullyCompiledModels.map { _ in parsed.reason })
            }

            downloadProgress = 1.0
            state = .loaded(models)

        } catch let error as DecodingError {
            print("❌ Decoding error: \(error)")
            state = .failed("Failed to decode response: \(error.localizedDescription)")
        } catch {
            print("❌ Request error: \(error)")
            state = .failed("Request failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Download + Unzip + Compile

    private func downloadAndCompile(remoteURL: URL, clusterID: String) async throws -> URL {
        let compiledDest = modelsDirectory.appendingPathComponent("\(clusterID).mlmodelc")

        if FileManager.default.fileExists(atPath: compiledDest.path) {
            print("♻️ Using cached model for cluster \(clusterID)")
            return compiledDest
        }

        print("⬇️ Downloading model zip for cluster \(clusterID)...")
        let (zipLocalURL, _) = try await URLSession.shared.download(from: remoteURL)

        let unzipDir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)

        try FileManager.default.createDirectory(
            at: unzipDir,
            withIntermediateDirectories: true
        )

        print("📦 Unzipping to \(unzipDir.lastPathComponent)...")
        try unzip(zipURL: zipLocalURL, to: unzipDir)

        guard let mlpackageURL = findAnyMLPackage(in: unzipDir) else {
            try? FileManager.default.removeItem(at: unzipDir)
            try? FileManager.default.removeItem(at: zipLocalURL)

            throw NSError(
                domain: "ModelService",
                code: 1,
                userInfo: [
                    NSLocalizedDescriptionKey: "No .mlpackage found in zip for cluster \(clusterID)"
                ]
            )
        }

        print("⚙️ Compiling \(mlpackageURL.lastPathComponent)...")
        let tempCompiled = try await MLModel.compileModel(at: mlpackageURL)

        if FileManager.default.fileExists(atPath: compiledDest.path) {
            try FileManager.default.removeItem(at: compiledDest)
        }

        try FileManager.default.moveItem(at: tempCompiled, to: compiledDest)

        try? FileManager.default.removeItem(at: unzipDir)
        try? FileManager.default.removeItem(at: zipLocalURL)

        print("✅ Compiled model saved to \(compiledDest.lastPathComponent)")
        return compiledDest
    }

    // MARK: - Unzip Helper

    private func unzip(zipURL: URL, to destination: URL) throws {
        try FileManager.default.unzipItem(at: zipURL, to: destination)
    }

    // Finds the first .mlpackage in a directory tree, regardless of filename.
    private func findAnyMLPackage(in directory: URL) -> URL? {
        let fm = FileManager.default

        guard let enumerator = fm.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        for case let url as URL in enumerator {
            if url.pathExtension == "mlpackage" {
                return url
            }
        }

        return nil
    }

    // MARK: - Reload Models

    func reloadModels(latitude: Double, longitude: Double) async {
        try? FileManager.default.removeItem(at: modelsDirectory)

        try? FileManager.default.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )

        state = .notLoaded
        pullReason = .none
        downloadProgress = 0.0

        await loadModels(latitude: latitude, longitude: longitude)
    }

    // MARK: - Check for Updates

    func checkForUpdates(latitude: Double, longitude: Double) async {
        // Placeholder for later:
        // Eventually this should call the backend and compare modelKey/version info
        // against what is cached locally.
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        updateAvailable = true
    }

    // MARK: - Delete Models

    func deleteModels() throws {
        try FileManager.default.removeItem(at: modelsDirectory)

        try FileManager.default.createDirectory(
            at: modelsDirectory,
            withIntermediateDirectories: true
        )

        state = .notLoaded
        pullReason = .none
        updateAvailable = false
        downloadProgress = 0.0
    }

    // MARK: - Movement Check

    func checkIfShouldReload(latitude: Double, longitude: Double) async {
        await loadModels(latitude: latitude, longitude: longitude)
    }
}
