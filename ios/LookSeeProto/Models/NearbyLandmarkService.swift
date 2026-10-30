//
//  NearbyLandmarkService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 3/9/26.
//

import Foundation
import Combine

@MainActor
final class NearbyLandmarkService: ObservableObject {
    @Published var items: [NearbyLandmark] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let baseURL = URL(string: "https://d11vl3v9w133rh.cloudfront.net")!

    func fetchNearby(latitude: Double, longitude: Double, radiusMeters: Double = 100, limit: Int = 100) async {
        isLoading = true
        errorMessage = nil

        do {
            let url = baseURL.appendingPathComponent("landmarks/map")
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")

            let body = NearbyLandmarksRequest(
                latitude: latitude,
                longitude: longitude,
                radiusMeters: radiusMeters,
                limit: limit
            )
            req.httpBody = try JSONEncoder().encode(body)

            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse else {
                throw URLError(.badServerResponse)
            }

            guard (200...299).contains(http.statusCode) else {
                let bodyStr = String(data: data, encoding: .utf8) ?? ""
                throw NSError(
                    domain: "NearbyLandmarkService",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode): \(bodyStr)"]
                )
            }

            let decoded = try JSONDecoder().decode(NearbyLandmarksResponse.self, from: data)
            items = decoded.items
            VariableContainer.shared.allLandmarks = decoded.items

        } catch {
            // Keep existing landmarks in memory on transient network/403 errors so pins don't vanish
            errorMessage = error.localizedDescription
            print("🚨 BACKEND ERROR: \(error)")
        }

        isLoading = false
    }
}
