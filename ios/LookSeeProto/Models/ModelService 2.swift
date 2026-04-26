
//
//  ModelService.swift
//  LookSeeProto
//

import Foundation
import Combine

// MARK: - Model Info
struct ModelInfo: Identifiable {
    let id = UUID()
    let name: String
    let downloadURL: URL
    let reason: String
    let clusterID: String
}

// MARK: - API Response Shape
private struct ModelsResponse: Decodable {
    let models: [ModelPayload]
    let reason: String
    let location: LocationResponse
}

private struct ModelPayload: Decodable {
    let clusterId: String
    let downloadUrl: String
}

private struct LocationResponse: Decodable {
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

    private init() {}

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

            let models: [ModelInfo] = parsed.models.compactMap { payload in
                guard let url = URL(string: payload.downloadUrl) else { return nil }
                return ModelInfo(
                    name: payload.clusterId,
                    downloadURL: url,
                    reason: parsed.reason,
                    clusterID: payload.clusterId
                )
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

    // MARK: - Reload Models
    func reloadModels(latitude: Double, longitude: Double) async {
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
    // TODO: backend dev — delete downloaded .mlmodel files from device storage
    func deleteModels() throws {
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

