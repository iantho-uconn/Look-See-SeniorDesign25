//
//  BusinessLandmarkService.swift
//  LookSeeProto
//
//  Handles the authenticated business landmark management API.
//

import Foundation
import Amplify
import AWSPluginsCore

struct BusinessLandmarkListResponse: Decodable {
    let items: [BusinessLandmark]
    let count: Int
}

struct BusinessLandmark: Decodable, Identifiable, Hashable {
    let landmarkId: String
    let label: String
    let shortDescription: String?
    let latitude: Double?
    let longitude: Double?
    let promotion: String?
    let promotionEnabled: Bool?
    let isActive: Bool?
    let userEmail: String?
    let ownerUserId: String?
    let createdByUserId: String?
    let createdAt: String?
    let updatedAt: String?
    let ownershipUpdatedAt: String?

    var id: String {
        landmarkId
    }

    var displayDescription: String {
        let value = shortDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? "No description available." : value
    }

    var displayStatus: String {
        if isActive == false {
            return "Inactive"
        }

        return "Active"
    }

    var displayPromotionStatus: String {
        if promotionEnabled == true {
            return "Promotion enabled"
        }

        return "No active promotion"
    }
}

enum BusinessLandmarkServiceError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "You must be signed in before managing landmarks."
        case .tokensUnavailable:
            return "Cognito tokens were unavailable."
        case .badStatus(let code, let body):
            return "API error \(code): \(body)"
        }
    }
}

final class BusinessLandmarkService {
    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    private func getCognitoIDToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard session.isSignedIn else {
            throw BusinessLandmarkServiceError.notSignedIn
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            throw BusinessLandmarkServiceError.tokensUnavailable
        }

        let tokens = try tokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    func fetchBusinessLandmarks() async throws -> BusinessLandmarkListResponse {
        let idToken = try await getCognitoIDToken()
        let url = baseURL.appendingPathComponent("business/landmarks")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let body = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessLandmarkServiceError.badStatus(statusCode, body)
        }

        return try JSONDecoder().decode(BusinessLandmarkListResponse.self, from: data)
    }
}
