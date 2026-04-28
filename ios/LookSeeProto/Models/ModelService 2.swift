

import Foundation
import CoreML
import Combine
import ZIPFoundation

// MARK: - Object Location
// Represents a detectable object's GPS position and which model it belongs to
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
    // TODO: backend dev — populate this from API response once object coordinates are returned
    var objects: [ObjectLocation] = []
}

// MARK: - API Response Shape
private struct ModelsResponse: Decodable {
    let models: [ModelPayload]
    let reason: String
    let location: LocationResponse
    // TODO: backend dev — add objects array to response and decode here
    // let objects: [ObjectPayload]
}

private struct ModelPayload: Decodable {
    let clusterId: String
    let downloadUrl: String
}

private struct LocationResponse: Decodable {
    let lat: Double
    let lon: Double
}

// MARK: - Stubbed Object Payload (remove when backend is ready)
// TODO: backend dev — replace this stub with real decoded data from API
private struct ObjectPayload: Decodable {
    let clusterId: String
    let lat: Double
    let lon: Double
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
        case (.notLoaded, .notLoaded): return true
        case (.loading,   .loading):   return true
        case (.failed,    .failed):    return true
        case (.loaded,    .loaded):    return true
        default: return false
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
        try? FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Load Models
    func loadModels(latitude: Double, longitude: Double) async {
        state = .loading
        downloadProgress = 0.0

        do {
            guard let endpoint = URL(string: apiURL) else {
                state = .failed("Invalid endpoint URL")
                return
            }

            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")

            let body: [String: Double] = ["latitude": latitude, "longitude": longitude]
            request.httpBody = try JSONSerialization.data(withJSONObject: body)

            let (data, response) = try await URLSession.shared.data(for: request)

            // DEBUG — remove before shipping
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
            downloadProgress = 0.2

            // TODO: backend dev — decode real object coordinates from parsed response
            // For now, stub objects are empty — ModelSelector will not switch models
            // until this is populated. Replace the empty array below with:
            // let allObjects = parsed.objects.map { ObjectLocation(clusterId: $0.clusterId, lat: $0.lat, lon: $0.lon) }
            let allObjects: [ObjectLocation] = []

            var models: [ModelInfo] = []
            let progressPerModel = 0.8 / Double(max(parsed.models.count, 1))

            for (index, payload) in parsed.models.enumerated() {
                guard let remoteURL = URL(string: payload.downloadUrl) else { continue }

                var info = ModelInfo(
                    name: payload.clusterId,
                    downloadURL: remoteURL,
                    reason: parsed.reason,
                    clusterID: payload.clusterId,
                    objects: allObjects.filter { $0.clusterId == payload.clusterId }
                )

                do {
                    let compiled = try await downloadAndCompile(
                        remoteURL: remoteURL,
                        clusterID: payload.clusterId
                    )
                    info.compiledModelURL = compiled
                    print("✅ Model compiled: \(compiled.lastPathComponent)")
                } catch {
                    print("❌ Failed to download/compile \(payload.clusterId): \(error)")
                }

                models.append(info)
                downloadProgress = 0.2 + progressPerModel * Double(index + 1)
            }

            pullReason = switch models.count {
            case 0:  .none
            case 1:  .single(reason: parsed.reason)
            default: .multiple(reasons: models.map { _ in parsed.reason })
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

        // Return cached compiled model if it already exists
        if FileManager.default.fileExists(atPath: compiledDest.path) {
            print("♻️ Using cached model for cluster \(clusterID)")
            return compiledDest
        }

        // Step 1 — download the zip from S3
        print("⬇️ Downloading model zip for cluster \(clusterID)...")
        let (zipLocalURL, _) = try await URLSession.shared.download(from: remoteURL)

        // Step 2 — unzip into a temp directory
        let unzipDir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: unzipDir, withIntermediateDirectories: true)

        print("📦 Unzipping to \(unzipDir.lastPathComponent)...")
        try unzip(zipURL: zipLocalURL, to: unzipDir)

        // Step 3 — find {clusterID}.mlpackage inside unzipped contents
        guard let mlpackageURL = findMLPackage(in: unzipDir, clusterID: clusterID) else {
            throw NSError(domain: "ModelService", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No \(clusterID).mlpackage found in zip"])
        }

        // Step 4 — compile .mlpackage → .mlmodelc
        print("⚙️ Compiling \(mlpackageURL.lastPathComponent)...")
        let tempCompiled = try await MLModel.compileModel(at: mlpackageURL)

        // Step 5 — move compiled model to permanent app storage
        if FileManager.default.fileExists(atPath: compiledDest.path) {
            try FileManager.default.removeItem(at: compiledDest)
        }
        try FileManager.default.moveItem(at: tempCompiled, to: compiledDest)

        // Step 6 — clean up temp files
        try? FileManager.default.removeItem(at: unzipDir)
        try? FileManager.default.removeItem(at: zipLocalURL)

        print("✅ Compiled model saved to \(compiledDest.lastPathComponent)")
        return compiledDest
    }

    // MARK: - Unzip Helper (iOS compatible via ZIPFoundation)
    private func unzip(zipURL: URL, to destination: URL) throws {
        try FileManager.default.unzipItem(at: zipURL, to: destination)
    }

    // MARK: - Find .mlpackage by cluster ID
    private func findMLPackage(in directory: URL, clusterID: String) -> URL? {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return nil }

        for case let url as URL in enumerator {
            if url.pathExtension == "mlpackage" &&
               url.deletingPathExtension().lastPathComponent == clusterID {
                return url
            }
        }
        return nil
    }

    // MARK: - Reload Models
    func reloadModels(latitude: Double, longitude: Double) async {
        try? FileManager.default.removeItem(at: modelsDirectory)
        try? FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
        state = .notLoaded
        pullReason = .none
        downloadProgress = 0.0
        await loadModels(latitude: latitude, longitude: longitude)
    }

    // MARK: - Check for Updates
    // TODO: backend dev — ping API to compare remote vs local model versions
    func checkForUpdates(latitude: Double, longitude: Double) async {
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        updateAvailable = true
    }

    // MARK: - Delete Models
    func deleteModels() throws {
        try FileManager.default.removeItem(at: modelsDirectory)
        try FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
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
