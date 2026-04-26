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
    let shortDescription: String?

    enum CodingKeys: String, CodingKey {
        case id = "landmarkId"
        case label
        case shortDescription
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
    func fetchLandmarkById(landmarkId: String) async -> BusinessLocation? {
        let url = baseURL.appendingPathComponent("landmarks/\(landmarkId)")
        
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            // Adjust decoding based on your single-item API response shape
            let decoded = try JSONDecoder().decode(BusinessLocation.self, from: data)
            return decoded
        } catch {
            print("❌ Failed to fetch landmark: \(error)")
            return nil
        }
    }
    func fetchLandmarkByLabel(label: String) async -> BusinessLocation? {
        var components = URLComponents(url: baseURL.appendingPathComponent("landmarks/by-label"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "label", value: label)]
        
        guard let url = components.url else { return nil }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            
            // This assumes your API returns a list (items) even for single searches
            let decoded = try JSONDecoder().decode(BusinessLocationListResponse.self, from: data)
            return decoded.items.first
        } catch {
            print("❌ Error fetching landmark by label: \(error)")
            return nil
        }
    }
}

// MARK: - Response wrapper

private struct BusinessLocationListResponse: Decodable {
    let items: [BusinessLocation]
}
