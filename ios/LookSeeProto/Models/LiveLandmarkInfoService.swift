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

        print("""
        ✅ decoded live-info
           landmarkId: \(decoded.landmarkId)
           websiteUrl: \(decoded.websiteUrl ?? "")
           activePromotion: \(decoded.activePromotion?.name ?? "none")
           promoImageUrl: \(decoded.activePromotion?.imageUrl ?? "")
        """)

        return decoded
    }
}
