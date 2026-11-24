//
//  LiveLandmarkInfoService.swift
//  LookSeeProto
//

import Foundation

struct LiveLandmarkInfoResponse: Decodable {
    let ok: Bool?
    let landmarkId: String
    let label: String
    let displayName: String?
    let shortDescription: String
    let websiteUrl: String?
    let latitude: Double?
    let longitude: Double?
    let isActive: Bool
    let promotionEnabled: Bool
    let activePromotion: LiveLandmarkPromotion?
    let activePromotions: [LiveLandmarkPromotion]?
    let activePromotionCount: Int?
    let reason: String?

    let merchantName: String?
    let merchantBio: String?
    let merchantPhone: String?
    let merchantWebsite: String?
    let merchantAddress: String?
    let merchantLogoUrl: String?

    var effectiveDisplayName: String {
        let preferred = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !preferred.isEmpty {
            return preferred
        }
        let fallback = label.trimmingCharacters(in: .whitespacesAndNewlines)
        return fallback.isEmpty ? "Unknown Landmark" : fallback
    }
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
    private let baseURL = URL(string: "https://d11vl3v9w133rh.cloudfront.net")!

    func fetchLiveInfo(
        landmarkId: String,
        timeoutSeconds: TimeInterval = 3.5,
        updateSharedMerchant: Bool = true
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

        if let lat = decoded.latitude, let lon = decoded.longitude {
            SuppressionCache.shared.setCoordinates(
                latitude: lat,
                longitude: lon,
                for: decoded.landmarkId
            )
        }

        let cacheKey = "cached_merchant_\(decoded.landmarkId)"
        let merchantDict: [String: Any] = [
            "merchantName": decoded.merchantName ?? "",
            "merchantBio": decoded.merchantBio ?? "",
            "merchantPhone": decoded.merchantPhone ?? "",
            "merchantLogoUrl": decoded.merchantLogoUrl ?? "",
            "merchantWebsite": decoded.merchantWebsite ?? "",
            "merchantAddress": decoded.merchantAddress ?? ""
        ]
        UserDefaults.standard.set(merchantDict, forKey: cacheKey)

        if updateSharedMerchant {
            Task { @MainActor in
                VariableContainer.shared.merchantName = decoded.merchantName ?? ""
                VariableContainer.shared.merchantBio = decoded.merchantBio ?? ""
                VariableContainer.shared.merchantPhone = decoded.merchantPhone ?? ""
                VariableContainer.shared.merchantLogoUrl = decoded.merchantLogoUrl ?? ""
                VariableContainer.shared.merchantWebsite = decoded.merchantWebsite ?? ""
                VariableContainer.shared.merchantAddress = decoded.merchantAddress ?? ""
            }
        }

        print("""
        ✅ decoded live-info
          landmarkId: \(decoded.landmarkId)
          displayName: \(decoded.effectiveDisplayName)
          websiteUrl: \(decoded.websiteUrl ?? "")
          activePromotion: \(decoded.activePromotion?.name ?? "none")
          promoImageUrl: \(decoded.activePromotion?.imageUrl ?? "")
          merchantName: \(decoded.merchantName ?? "none")
          merchantLogoUrl: \(decoded.merchantLogoUrl ?? "none")
        """)

        return decoded
    }
}
