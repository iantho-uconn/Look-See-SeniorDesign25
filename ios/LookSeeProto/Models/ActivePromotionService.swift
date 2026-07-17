//
//  ActivePromotionService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 7/13/26.
//

import Foundation

struct ActivePromotionResponse: Decodable {
    let items: [ActivePromotion]
    let count: Int
    let landmarkId: String?
    let landmarkLabel: String?
    let reason: String?
}

struct ActivePromotion: Decodable, Identifiable, Hashable {
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

enum ActivePromotionServiceError: LocalizedError {
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .badStatus(let code, let body):
            return "Active promotion API error \(code): \(body)"
        }
    }
}

final class ActivePromotionService {
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    func fetchActivePromotions(landmarkId: String) async throws -> [ActivePromotion] {
        let url = baseURL
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("promotions")
            .appendingPathComponent("active")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw ActivePromotionServiceError.badStatus(statusCode, responseBody)
        }

        let decoded = try JSONDecoder().decode(ActivePromotionResponse.self, from: data)
        return decoded.items
    }

    func fetchTopActivePromotion(landmarkId: String) async throws -> ActivePromotion? {
        let promotions = try await fetchActivePromotions(landmarkId: landmarkId)
        return promotions.first
    }
}
