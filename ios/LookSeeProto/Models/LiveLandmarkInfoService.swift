//
//  LiveLandmarkInfoService.swift
//  LookSeeProto
//

import Foundation

struct LiveLandmarkInfoResponse: Decodable {
    let ok: Bool?
    let landmarkId: String
    let label: String
    let shortDescription: String
    let websiteUrl: String?
    let isActive: Bool
    let promotionEnabled: Bool
    let activePromotion: LiveLandmarkPromotion?
    let activePromotions: [LiveLandmarkPromotion]?
    let activePromotionCount: Int?
    let reason: String?
    
    // 🚀 Added merchant profile fields from Lambda response
    let merchantName: String?
    let merchantBio: String?
    let merchantPhone: String?
    let merchantLogoUrl: String?
}

struct LiveLandmarkPromotion: Decodable, Identifiable, Hashable {
    let promotionId: String
    let landmarkId: String
    let landmarkLabel: String
    let name: String
    let description: String
    let imageUrl: String?
    let startDate: String?
    let endDate: String?
    let enabled: Bool
    let createdAt: Int?
    let updatedAt: Int?

    var id: String {
        promotionId
    }
}

enum LiveLandmarkInfoServiceError: LocalizedError {
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .badStatus(let statusCode, let body):
            return "Live landmark info API error \(statusCode): \(body)"
        }
    }
}

final class LiveLandmarkInfoService {
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    func fetchLiveInfo(
        landmarkId: String,
        timeoutSeconds: TimeInterval = 3.5
    ) async throws -> LiveLandmarkInfoResponse {
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("live-info")

        print("📡 Fetching live-info: \(url.absoluteString)")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = timeoutSeconds
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeoutSeconds
        configuration.timeoutIntervalForResource = timeoutSeconds

        let session = URLSession(configuration: configuration)
        let (data, response) = try await session.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        print("📦 live-info status \(statusCode): \(responseBody)")

        guard (200...299).contains(statusCode) else {
            throw LiveLandmarkInfoServiceError.badStatus(statusCode, responseBody)
        }

        let decoded = try JSONDecoder().decode(LiveLandmarkInfoResponse.self, from: data)

        // 🚀 Save to local cache so future scans are instant (0ms delay)
        let cacheKey = "cached_merchant_\(decoded.landmarkId)"
        let merchantDict: [String: Any] = [
            "merchantName": decoded.merchantName ?? "",
            "merchantBio": decoded.merchantBio ?? "",
            "merchantPhone": decoded.merchantPhone ?? "",
            "merchantLogoUrl": decoded.merchantLogoUrl ?? ""
        ]
        UserDefaults.standard.set(merchantDict, forKey: cacheKey)

        // Automatically inject merchant data into VariableContainer for the UI popup
        Task { @MainActor in
            VariableContainer.shared.merchantName = decoded.merchantName ?? ""
            VariableContainer.shared.merchantBio = decoded.merchantBio ?? ""
            VariableContainer.shared.merchantPhone = decoded.merchantPhone ?? ""
            VariableContainer.shared.merchantLogoUrl = decoded.merchantLogoUrl ?? ""
        }

        print("""
        ✅ decoded live-info
          landmarkId: \(decoded.landmarkId)
          websiteUrl: \(decoded.websiteUrl ?? "")
          activePromotion: \(decoded.activePromotion?.name ?? "none")
          promoImageUrl: \(decoded.activePromotion?.imageUrl ?? "")
          merchantName: \(decoded.merchantName ?? "none")
          merchantLogoUrl: \(decoded.merchantLogoUrl ?? "none")
        """)

        return decoded
    }
}
