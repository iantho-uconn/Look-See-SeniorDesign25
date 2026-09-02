//
//  BusinessMediaHistoryService.swift
//  LookSeeProto
//
//  Fetches landmark-scoped upload history from the authenticated API.
//

import Foundation
import Amplify
import AWSPluginsCore

enum BusinessMediaHistoryServiceError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case invalidURL
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "You must be signed in before viewing media history."
        case .tokensUnavailable:
            return "Cognito tokens were unavailable."
        case .invalidURL:
            return "The media-history URL could not be created."
        case .badStatus(let code, let body):
            return "Media history API error \(code): \(body)"
        }
    }
}

final class BusinessMediaHistoryService {
    private let baseURL = URL(
        string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"
    )!

    private func getCognitoIDToken() async throws -> String {
        let session = try await Amplify.Auth.fetchAuthSession()

        guard session.isSignedIn else {
            throw BusinessMediaHistoryServiceError.notSignedIn
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            throw BusinessMediaHistoryServiceError.tokensUnavailable
        }

        let tokens = try tokenProvider.getCognitoTokens().get()
        return tokens.idToken
    }

    func fetchHistory(
        landmarkId: String,
        limit: Int = 25,
        nextToken: String? = nil
    ) async throws -> BusinessMediaHistoryResponse {
        let idToken = try await getCognitoIDToken()

        let endpoint = baseURL
            .appendingPathComponent("business")
            .appendingPathComponent("landmarks")
            .appendingPathComponent(landmarkId)
            .appendingPathComponent("media-history")

        guard var components = URLComponents(
            url: endpoint,
            resolvingAgainstBaseURL: false
        ) else {
            throw BusinessMediaHistoryServiceError.invalidURL
        }

        var queryItems = [
            URLQueryItem(
                name: "limit",
                value: String(max(1, min(limit, 100)))
            )
        ]

        if let nextToken,
           !nextToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            queryItems.append(
                URLQueryItem(name: "nextToken", value: nextToken)
            )
        }

        components.queryItems = queryItems

        guard let url = components.url else {
            throw BusinessMediaHistoryServiceError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(
            "Bearer \(idToken)",
            forHTTPHeaderField: "Authorization"
        )
        request.setValue(
            "application/json",
            forHTTPHeaderField: "Accept"
        )
        request.timeoutInterval = 30

        let (data, response) = try await URLSession.shared.data(for: request)

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        let responseBody = String(data: data, encoding: .utf8) ?? ""

        guard (200...299).contains(statusCode) else {
            throw BusinessMediaHistoryServiceError.badStatus(
                statusCode,
                responseBody
            )
        }

        return try JSONDecoder().decode(
            BusinessMediaHistoryResponse.self,
            from: data
        )
    }
}
