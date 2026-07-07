//
//  BusinessLandmarkManagement.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 7/6/26.
//

//
//  BusinessLandmarkManagement.swift
//  LookSeeProto
//
//  Landmark management models, service, view model, and read-only UI.
//

import SwiftUI
import Foundation
import Amplify
import AWSPluginsCore

// MARK: - API Models

struct BusinessLandmarkListResponse: Decodable {
    let items: [BusinessLandmark]
    let count: Int
}

struct BusinessLandmark: Decodable, Identifiable {
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

    enum CodingKeys: String, CodingKey {
        case landmarkId
        case label
        case shortDescription
        case latitude
        case longitude
        case promotion
        case promotionEnabled
        case isActive
        case userEmail
        case ownerUserId
        case createdByUserId
        case createdAt
        case updatedAt
        case ownershipUpdatedAt
    }

    init(
        landmarkId: String,
        label: String,
        shortDescription: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        promotion: String? = nil,
        promotionEnabled: Bool? = nil,
        isActive: Bool? = nil,
        userEmail: String? = nil,
        ownerUserId: String? = nil,
        createdByUserId: String? = nil,
        createdAt: String? = nil,
        updatedAt: String? = nil,
        ownershipUpdatedAt: String? = nil
    ) {
        self.landmarkId = landmarkId
        self.label = label
        self.shortDescription = shortDescription
        self.latitude = latitude
        self.longitude = longitude
        self.promotion = promotion
        self.promotionEnabled = promotionEnabled
        self.isActive = isActive
        self.userEmail = userEmail
        self.ownerUserId = ownerUserId
        self.createdByUserId = createdByUserId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.ownershipUpdatedAt = ownershipUpdatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        landmarkId = container.decodeFlexibleString(forKey: .landmarkId) ?? ""
        label = container.decodeFlexibleString(forKey: .label) ?? "Untitled Landmark"
        shortDescription = container.decodeFlexibleString(forKey: .shortDescription)
        latitude = container.decodeFlexibleDouble(forKey: .latitude)
        longitude = container.decodeFlexibleDouble(forKey: .longitude)
        promotion = container.decodeFlexibleString(forKey: .promotion)
        promotionEnabled = container.decodeFlexibleBool(forKey: .promotionEnabled)
        isActive = container.decodeFlexibleBool(forKey: .isActive)
        userEmail = container.decodeFlexibleString(forKey: .userEmail)
        ownerUserId = container.decodeFlexibleString(forKey: .ownerUserId)
        createdByUserId = container.decodeFlexibleString(forKey: .createdByUserId)
        createdAt = container.decodeFlexibleString(forKey: .createdAt)
        updatedAt = container.decodeFlexibleString(forKey: .updatedAt)
        ownershipUpdatedAt = container.decodeFlexibleString(forKey: .ownershipUpdatedAt)
    }
}

// MARK: - Decoding Helpers

private extension KeyedDecodingContainer {
    func decodeFlexibleString(forKey key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return value
        }

        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }

        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            if value.truncatingRemainder(dividingBy: 1) == 0 {
                return String(Int(value))
            }

            return String(value)
        }

        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return String(value)
        }

        return nil
    }

    func decodeFlexibleDouble(forKey key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value
        }

        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Double(value)
        }

        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Double(value.trimmingCharacters(in: .whitespacesAndNewlines))
        }

        return nil
    }

    func decodeFlexibleBool(forKey key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }

        if let value = try? decodeIfPresent(String.self, forKey: key) {
            switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "yes", "1":
                return true
            case "false", "no", "0":
                return false
            default:
                return nil
            }
        }

        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value != 0
        }

        return nil
    }
}

// MARK: - Service

enum BusinessLandmarkError: LocalizedError {
    case notSignedIn
    case tokensUnavailable
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "You must be signed in before managing landmarks."
        case .tokensUnavailable:
            return "The Cognito authentication tokens were unavailable."
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
            throw BusinessLandmarkError.notSignedIn
        }

        guard let tokenProvider = session as? AuthCognitoTokensProvider else {
            throw BusinessLandmarkError.tokensUnavailable
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
            throw BusinessLandmarkError.badStatus(statusCode, body)
        }

        return try JSONDecoder().decode(BusinessLandmarkListResponse.self, from: data)
    }
}

// MARK: - View Model

@MainActor
final class BusinessLandmarksViewModel: ObservableObject {
    @Published var landmarks: [BusinessLandmark] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let service: BusinessLandmarkService

    init(service: BusinessLandmarkService = BusinessLandmarkService()) {
        self.service = service
    }

    func loadLandmarks(force: Bool = false) async {
        if isLoading {
            return
        }

        if !force && !landmarks.isEmpty {
            return
        }

        isLoading = true
        errorMessage = nil

        do {
            let response = try await service.fetchBusinessLandmarks()
            landmarks = response.items
            print("✅ Business landmarks loaded into UI: \(response.count)")
        } catch {
            errorMessage = error.localizedDescription
            print("❌ Business landmarks UI load failed: \(error.localizedDescription)")
        }

        isLoading = false
    }
}

// MARK: - Views

struct BusinessLandmarksView: View {
    @StateObject private var viewModel = BusinessLandmarksViewModel()

    var body: some View {
        List {
            if viewModel.isLoading && viewModel.landmarks.isEmpty {
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Loading landmarks...")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }
            } else if let errorMessage = viewModel.errorMessage {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Unable to load landmarks", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .font(.headline)

                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        Button {
                            Task {
                                await viewModel.loadLandmarks(force: true)
                            }
                        } label: {
                            Label("Try Again", systemImage: "arrow.clockwise")
                        }
                    }
                    .padding(.vertical, 6)
                }
            } else if viewModel.landmarks.isEmpty {
                Section {
                    ContentUnavailableView(
                        "No Managed Landmarks",
                        systemImage: "building.2.crop.circle",
                        description: Text("Landmarks created by this business account will appear here.")
                    )
                }
            } else {
                Section {
                    ForEach(viewModel.landmarks) { landmark in
                        NavigationLink {
                            BusinessLandmarkDetailView(landmark: landmark)
                        } label: {
                            BusinessLandmarkRow(landmark: landmark)
                        }
                    }
                } header: {
                    Text("Landmarks")
                } footer: {
                    Text("\(viewModel.landmarks.count) landmark\(viewModel.landmarks.count == 1 ? "" : "s") available for management.")
                }
            }
        }
        .navigationTitle("My Landmarks")
        .task {
            await viewModel.loadLandmarks()
        }
        .refreshable {
            await viewModel.loadLandmarks(force: true)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task {
                        await viewModel.loadLandmarks(force: true)
                    }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(viewModel.isLoading)
            }
        }
    }
}

private struct BusinessLandmarkRow: View {
    let landmark: BusinessLandmark

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.blue.opacity(0.12))
                    .frame(width: 42, height: 42)

                Image(systemName: "mappin.and.ellipse")
                    .foregroundStyle(Color.blue)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(landmark.label)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                if let description = cleanText(landmark.shortDescription) {
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                HStack(spacing: 8) {
                    statusPill(
                        title: (landmark.isActive ?? true) ? "Active" : "Inactive",
                        systemImage: (landmark.isActive ?? true) ? "checkmark.circle.fill" : "pause.circle.fill"
                    )

                    if landmark.promotionEnabled == true {
                        statusPill(title: "Promotion", systemImage: "tag.fill")
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    private func cleanText(_ value: String?) -> String? {
        let trimmed = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func statusPill(title: String, systemImage: String) -> some View {
        Label(title, systemImage: systemImage)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .labelStyle(.titleAndIcon)
    }
}

struct BusinessLandmarkDetailView: View {
    let landmark: BusinessLandmark

    var body: some View {
        Form {
            Section {
                labeledValue("Name", landmark.label)
                labeledValue("Description", landmark.shortDescription)
                labeledValue("Landmark ID", landmark.landmarkId)
                labeledValue("Status", (landmark.isActive ?? true) ? "Active" : "Inactive")
            } header: {
                Text("Landmark")
            }

            Section {
                labeledValue("Latitude", landmark.latitude.map { String(format: "%.6f", $0) })
                labeledValue("Longitude", landmark.longitude.map { String(format: "%.6f", $0) })
            } header: {
                Text("Location")
            }

            Section {
                labeledValue("Promotion Enabled", (landmark.promotionEnabled ?? false) ? "Yes" : "No")
                labeledValue("Promotion", landmark.promotion)
            } header: {
                Text("Promotion")
            }

            Section {
                labeledValue("Owner Email", landmark.userEmail)
                labeledValue("Owner User ID", landmark.ownerUserId)
                labeledValue("Created By User ID", landmark.createdByUserId)
                labeledValue("Ownership Updated", formattedTimestamp(landmark.ownershipUpdatedAt))
                labeledValue("Last Updated", formattedTimestamp(landmark.updatedAt))
            } header: {
                Text("Ownership")
            }

            Section {
                Label("Positive media upload coming next", systemImage: "photo.on.rectangle.angled")
                    .foregroundStyle(.secondary)
                Label("Negative example upload coming next", systemImage: "minus.circle")
                    .foregroundStyle(.secondary)
            } header: {
                Text("Uploads")
            } footer: {
                Text("This first version is read-only. The next phase will connect this page to the remote upload flow.")
            }
        }
        .navigationTitle(landmark.label)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func labeledValue(_ title: String, _ value: String?) -> some View {
        let trimmed = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        LabeledContent(title) {
            Text(trimmed.isEmpty ? "Not set" : trimmed)
                .multilineTextAlignment(.trailing)
                .foregroundStyle(trimmed.isEmpty ? .secondary : .primary)
        }
    }

    private func formattedTimestamp(_ value: String?) -> String? {
        guard let value else {
            return nil
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)

        guard let seconds = TimeInterval(trimmed) else {
            return trimmed.isEmpty ? nil : trimmed
        }

        let date = Date(timeIntervalSince1970: seconds)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
