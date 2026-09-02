//
//  BusinessPromotionService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 7/13/26.
//  Authenticated business-scoped promotion API client.
//

import Foundation
import Amplify
import AWSPluginsCore

struct BusinessPromotionListResponse: Decodable {
    let items: [BusinessPromotion]
    let count: Int
}

struct BusinessPromotionMutationResponse: Decodable {
    let ok: Bool
    let item: BusinessPromotion
}

struct BusinessPromotionDeleteResponse: Decodable {
    let ok: Bool
    let promotionId: String
    let message: String?
}

struct BusinessPromotion: Identifiable, Decodable, Hashable {
    let promotionId: String
    let userEmail: String
    let ownerUserId: String
    let landmarkId: String
    let landmarkLabel: String
    let name: String
    let description: String
    let imageUrl: String
    let startDate: String
    let endDate: String
    let enabled: Bool
    let createdAt: Int?
    let updatedAt: Int?

    var id: String {
        promotionId
    }

    enum CodingKeys: String, CodingKey {
        case promotionId
        case userEmail
        case ownerUserId
        case landmarkId
        case landmarkLabel
        case name
        case description
        case imageUrl
        case startDate
        case endDate
        case enabled
        case createdAt
        case updatedAt
    }

    init(
        promotionId: String,
        userEmail: String,
        ownerUserId: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        imageUrl: String,
        startDate: String,
        endDate: String,
        enabled: Bool,
        createdAt: Int?,
        updatedAt: Int?
    ) {
        self.promotionId = promotionId
        self.userEmail = userEmail
        self.ownerUserId = ownerUserId
        self.landmarkId = landmarkId
        self.landmarkLabel = landmarkLabel
        self.name = name
        self.description = description
        self.imageUrl = imageUrl
        self.startDate = startDate
        self.endDate = endDate
        self.enabled = enabled
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        promotionId = try container.decodeIfPresent(String.self, forKey: .promotionId) ?? ""
        userEmail = try container.decodeIfPresent(String.self, forKey: .userEmail) ?? ""
        ownerUserId = try container.decodeIfPresent(String.self, forKey: .ownerUserId) ?? ""
        landmarkId = try container.decodeIfPresent(String.self, forKey: .landmarkId) ?? ""
        landmarkLabel = try container.decodeIfPresent(String.self, forKey: .landmarkLabel) ?? ""
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        description = try container.decodeIfPresent(String.self, forKey: .description) ?? ""
        imageUrl = try container.decodeIfPresent(String.self, forKey: .imageUrl) ?? ""
        startDate = try container.decodeIfPresent(String.self, forKey: .startDate) ?? ""
        endDate = try container.decodeIfPresent(String.self, forKey: .endDate) ?? ""
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        createdAt = Self.decodeFlexibleInt(container, key: .createdAt)
        updatedAt = Self.decodeFlexibleInt(container, key: .updatedAt)
    }

    private static func decodeFlexibleInt(_ container: KeyedDecodingContainer<CodingKeys>, key: CodingKeys) -> Int? {
        if let value = try? container.decodeIfPresent(Int.self, forKey: key) {
            return value
        }

        if let value = try? container.decodeIfPresent(Double.self, forKey: key) {
            return Int(value)
        }

        if let value = try? container.decodeIfPresent(String.self, forKey: key) {
            return Int(value)
        }

        return nil
    }

    func copy(
        name: String? = nil,
        description: String? = nil,
        imageUrl: String? = nil,
        startDate: String? = nil,
        endDate: String? = nil,
        enabled: Bool? = nil
    ) -> BusinessPromotion {
        BusinessPromotion(
            promotionId: promotionId,
            userEmail: userEmail,
            ownerUserId: ownerUserId,
            landmarkId: landmarkId,
            landmarkLabel: landmarkLabel,
            name: name ?? self.name,
            description: description ?? self.description,
            imageUrl: imageUrl ?? self.imageUrl,
            startDate: startDate ?? self.startDate,
            endDate: endDate ?? self.endDate,
            enabled: enabled ?? self.enabled,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

enum BusinessPromotionServiceError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case badStatus(Int, String)
    case invalidRequestBody

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "You must be signed in before managing promotions."
        case .tokensUnavailable:
            return "Cognito tokens were unavailable."
        case .badStatus(let statusCode, let body):
            return "API error \(statusCode): \(body)"
        case .invalidRequestBody:
            return "Could not build the promotion request."
        }
    }
}

final class BusinessPromotionService {
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    private func getCognitoIDToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard session.isSignedIn else {
            throw BusinessPromotionServiceError.notSignedIn
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            throw BusinessPromotionServiceError.tokensUnavailable
        }

        let tokens = try tokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    func fetchPromotions(landmarkId: String) async throws -> BusinessPromotionListResponse {
        let idToken = try await getCognitoIDToken()
        let url = promotionsURL(landmarkId: landmarkId)

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessPromotionServiceError.badStatus(statusCode, responseBody)
        }

        return try JSONDecoder().decode(BusinessPromotionListResponse.self, from: data)
    }

    func createPromotion(
        landmarkId: String,
        name: String,
        description: String,
        imageUrl: String,
        startDate: String,
        endDate: String,
        enabled: Bool
    ) async throws -> BusinessPromotion {
        let body: [String: Any] = [
            "name": name,
            "description": description,
            "imageUrl": imageUrl,
            "startDate": startDate,
            "endDate": endDate,
            "enabled": enabled
        ]

        let data = try await sendJSONRequest(
            method: "POST",
            url: promotionsURL(landmarkId: landmarkId),
            body: body
        )

        let decoded = try JSONDecoder().decode(BusinessPromotionMutationResponse.self, from: data)
        return decoded.item
    }

    func updatePromotion(
        landmarkId: String,
        promotionId: String,
        name: String? = nil,
        description: String? = nil,
        imageUrl: String? = nil,
        startDate: String? = nil,
        endDate: String? = nil,
        enabled: Bool? = nil
    ) async throws -> BusinessPromotion {
        var body: [String: Any] = [:]

        if let name { body["name"] = name }
        if let description { body["description"] = description }
        if let imageUrl { body["imageUrl"] = imageUrl }
        if let startDate { body["startDate"] = startDate }
        if let endDate { body["endDate"] = endDate }
        if let enabled { body["enabled"] = enabled }

        guard !body.isEmpty else {
            throw BusinessPromotionServiceError.invalidRequestBody
        }

        let data = try await sendJSONRequest(
            method: "PATCH",
            url: promotionURL(landmarkId: landmarkId, promotionId: promotionId),
            body: body
        )

        let decoded = try JSONDecoder().decode(BusinessPromotionMutationResponse.self, from: data)
        return decoded.item
    }

    func deletePromotion(
        landmarkId: String,
        promotionId: String
    ) async throws {
        _ = try await sendJSONRequest(
            method: "DELETE",
            url: promotionURL(landmarkId: landmarkId, promotionId: promotionId),
            body: nil
        )
    }

    private func sendJSONRequest(
        method: String,
        url: URL,
        body: [String: Any]?
    ) async throws -> Data {
        let idToken = try await getCognitoIDToken()

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessPromotionServiceError.badStatus(statusCode, responseBody)
        }

        return data
    }

    private func promotionsURL(landmarkId: String) -> URL {
        baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("promotions")
    }

    private func promotionURL(landmarkId: String, promotionId: String) -> URL {
        promotionsURL(landmarkId: landmarkId)
            .appendingPathComponent(promotionId)
    }
}
