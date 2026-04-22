//
//  PromotionService.swift
//  LookSeeProto
//

import Foundation
import Combine

// MARK: - Promotion API Model
// Separate from the SwiftUI Promotion struct — this is what goes over the wire.

struct PromotionPayload: Identifiable, Codable {
    let id: String           // promotionId (UUID string)
    let userEmail: String
    let landmarkId: String
    let landmarkLabel: String
    let name: String
    let description: String
    let startDate: String    // ISO 8601: "2026-04-21"
    let endDate: String
    let enabled: Bool
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id = "promotionId"
        case userEmail, landmarkId, landmarkLabel
        case name, description, startDate, endDate, enabled, createdAt
    }
}

// MARK: - Request bodies

private struct CreatePromotionRequest: Encodable {
    let userEmail: String
    let landmarkId: String
    let landmarkLabel: String
    let name: String
    let description: String
    let startDate: String
    let endDate: String
    let enabled: Bool
}

private struct UpdatePromotionRequest: Encodable {
    let userEmail: String
    let landmarkId: String
    let landmarkLabel: String
    let name: String
    let description: String
    let startDate: String
    let endDate: String
    let enabled: Bool
}

// MARK: - Service

@MainActor
final class PromotionService: ObservableObject {
    @Published var promotions: [PromotionPayload] = []
    @Published var isLoading = false
    @Published var errorMessage: String? = nil

    private let baseURL = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev")!

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    // MARK: - GET /promotions?userEmail=...
    func fetchPromotions(userEmail: String) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        var components = URLComponents(
            url: baseURL.appendingPathComponent("promotions"),
            resolvingAgainstBaseURL: false
        )!
        components.queryItems = [URLQueryItem(name: "userEmail", value: userEmail)]

        guard let url = components.url else {
            errorMessage = "Invalid URL."
            return
        }

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                errorMessage = "Failed to load promotions."
                return
            }
            let decoded = try JSONDecoder().decode(PromotionListResponse.self, from: data)
            promotions = decoded.items
        } catch {
            errorMessage = "Failed to load promotions: \(error.localizedDescription)"
        }
    }

    // MARK: - POST /promotions
    func createPromotion(
        userEmail: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        startDate: Date,
        endDate: Date,
        enabled: Bool = true
    ) async {
        errorMessage = nil

        let body = CreatePromotionRequest(
            userEmail: userEmail,
            landmarkId: landmarkId,
            landmarkLabel: landmarkLabel,
            name: name,
            description: description,
            startDate: Self.isoFormatter.string(from: startDate),
            endDate: Self.isoFormatter.string(from: endDate),
            enabled: enabled
        )

        do {
            let created = try await post(
                path: "promotions",
                body: body,
                responseType: PromotionPayload.self
            )
            promotions.append(created)
        } catch {
            errorMessage = "Failed to create promotion: \(error.localizedDescription)"
        }
    }

    // MARK: - PATCH /promotions/{promotionId}
    func updatePromotion(
        promotionId: String,
        userEmail: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        startDate: Date,
        endDate: Date,
        enabled: Bool
    ) async {
        errorMessage = nil

        let body = UpdatePromotionRequest(
            userEmail: userEmail,
            landmarkId: landmarkId,
            landmarkLabel: landmarkLabel,
            name: name,
            description: description,
            startDate: Self.isoFormatter.string(from: startDate),
            endDate: Self.isoFormatter.string(from: endDate),
            enabled: enabled
        )

        let url = baseURL.appendingPathComponent("promotions/\(promotionId)")

        do {
            var req = URLRequest(url: url)
            req.httpMethod = "PATCH"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(body)

            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                errorMessage = "Failed to update promotion."
                return
            }
            let updated = try JSONDecoder().decode(PromotionPayload.self, from: data)
            if let index = promotions.firstIndex(where: { $0.id == promotionId }) {
                promotions[index] = updated
            }
        } catch {
            errorMessage = "Failed to update promotion: \(error.localizedDescription)"
        }
    }

    // MARK: - DELETE /promotions/{promotionId}?userEmail=...
    func deletePromotion(promotionId: String, userEmail: String) async {
        errorMessage = nil

        var components = URLComponents(
            url: baseURL.appendingPathComponent("promotions/\(promotionId)"),
            resolvingAgainstBaseURL: false
        )!
        components.queryItems = [URLQueryItem(name: "userEmail", value: userEmail)]

        guard let url = components.url else {
            errorMessage = "Invalid URL."
            return
        }

        do {
            var req = URLRequest(url: url)
            req.httpMethod = "DELETE"

            let (_, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                errorMessage = "Failed to delete promotion."
                return
            }
            promotions.removeAll { $0.id == promotionId }
        } catch {
            errorMessage = "Failed to delete promotion: \(error.localizedDescription)"
        }
    }

    // MARK: - Generic POST helper

    private func post<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body,
        responseType: Response.Type
    ) async throws -> Response {
        let url = baseURL.appendingPathComponent(path)
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw ServiceError.badStatus(body)
        }
        return try JSONDecoder().decode(responseType, from: data)
    }
}

// MARK: - Helpers

private struct PromotionListResponse: Decodable {
    let items: [PromotionPayload]
}

private enum ServiceError: LocalizedError {
    case badStatus(String)
    var errorDescription: String? {
        if case .badStatus(let msg) = self { return msg }
        return nil
    }
}
