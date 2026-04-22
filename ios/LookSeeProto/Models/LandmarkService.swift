//
//  LandmarkService.swift
//  LookSeeProto
//

import Foundation
import Combine

// MARK: - Model
// Named BusinessLocation (not Landmark) to avoid conflict with the
// existing Landmark model in Landmark.swift which has a different shape.

struct BusinessLocation: Identifiable, Decodable {
    let id: String
    let label: String

    enum CodingKeys: String, CodingKey {
        case id = "landmarkId"
        case label
    }
}

// MARK: - Service

@MainActor
final class LandmarkService: ObservableObject {
    @Published var landmarks: [BusinessLocation] = []
    @Published var isLoading = false
    @Published var errorMessage: String? = nil

    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    // MARK: - Fetch landmarks owned by this user
    // Calls GET /landmarks/by-user?userEmail=...
    // Backed by a new Lambda that queries LookSeeLandmarks by userEmail.
    func fetchLandmarks(userEmail: String) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        var components = URLComponents(
            url: baseURL.appendingPathComponent("landmarks/by-user"),
            resolvingAgainstBaseURL: false
        )!
        components.queryItems = [URLQueryItem(name: "userEmail", value: userEmail)]

        guard let url = components.url else {
            errorMessage = "Invalid URL."
            return
        }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse else {
                errorMessage = "No response from server."
                return
            }
            guard (200...299).contains(http.statusCode) else {
                let body = String(data: data, encoding: .utf8) ?? ""
                errorMessage = "Server error \(http.statusCode): \(body)"
                return
            }

            // Expected shape: { "items": [ { "landmarkId": "...", "label": "..." }, ... ] }
            let decoded = try JSONDecoder().decode(BusinessLocationListResponse.self, from: data)
            landmarks = decoded.items
        } catch {
            errorMessage = "Failed to load locations: \(error.localizedDescription)"
        }
    }
}

// MARK: - Response wrapper

private struct BusinessLocationListResponse: Decodable {
    let items: [BusinessLocation]
}
