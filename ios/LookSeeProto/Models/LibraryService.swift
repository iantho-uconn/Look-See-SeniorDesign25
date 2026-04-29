//
//  LibraryService.swift
//  LookSeeProto
//
//  Automatically fetches landmark info for whichever model cluster
//  is currently active in ModelSelector.
//
import Foundation
import Combine

// MARK: - Library Landmark Model
struct LibraryLandmark: Identifiable, Decodable {
    let id: String
    let label: String
    let shortDescription: String
    let clusterId: Int

    enum CodingKeys: String, CodingKey {
        case id = "landmarkId"
        case label
        case shortDescription = "short_description"
        case clusterId = "clusterId"
    }
}

// MARK: - API Response
struct LibraryLandmarksResponse: Decodable {
    let items: [LibraryLandmark]
}

// MARK: - Library Service
@MainActor
final class LibraryService: ObservableObject {
    static let shared = LibraryService()

    @Published var items: [LibraryLandmark] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!
    private var observerTask: Task<Void, Never>? = nil

    private init() {
        startObservingActiveCluster()
    }

    // MARK: - Observe ModelSelector
    private func startObservingActiveCluster() {
        observerTask?.cancel()
        observerTask = Task {
            for await activeClusterID in ModelSelector.shared.$activeClusterID.values {
                guard let activeClusterID else {
                    // No active model — clear the library
                    items = []
                    errorMessage = nil
                    continue
                }

                // clusterID is already numeric (e.g. "0")
                guard let numericClusterID = Int(activeClusterID) else {
                    print("⚠️ LibraryService: could not convert clusterID '\(activeClusterID)' to Int")
                    continue
                }

                print("📚 LibraryService: active cluster changed to \(numericClusterID), fetching landmarks...")
                await fetchLandmarks(clusterId: numericClusterID)
            }
        }
    }

    // MARK: - Fetch Landmarks
    private func fetchLandmarks(clusterId: Int) async {
        isLoading = true
        errorMessage = nil
        items = []

        do {
            var components = URLComponents(
                url: baseURL.appendingPathComponent("landmarks/by-cluster"),
                resolvingAgainstBaseURL: false
            )!
            components.queryItems = [
                URLQueryItem(name: "cluster_id", value: String(clusterId))
            ]

            guard let url = components.url else { throw URLError(.badURL) }

            var req = URLRequest(url: url)
            req.httpMethod = "GET"

            let (data, resp) = try await URLSession.shared.data(for: req)

            guard let http = resp as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }
            guard (200...299).contains(http.statusCode) else {
                let bodyStr = String(data: data, encoding: .utf8) ?? ""
                throw NSError(
                    domain: "LibraryService",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode): \(bodyStr)"]
                )
            }

            let decoded = try JSONDecoder().decode(LibraryLandmarksResponse.self, from: data)
            items = decoded.items
            print("✅ LibraryService: loaded \(items.count) landmarks for cluster \(clusterId)")
        } catch {
            errorMessage = error.localizedDescription
            print("❌ LibraryService: \(error.localizedDescription)")
        }

        isLoading = false
    }
}
