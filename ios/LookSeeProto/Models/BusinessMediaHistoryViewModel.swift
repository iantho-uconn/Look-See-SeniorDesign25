//
//  BusinessMediaHistoryViewModel.swift
//  LookSeeProto
//

import Foundation
import Combine

@MainActor
final class BusinessMediaHistoryViewModel: ObservableObject {
    @Published private(set) var items: [BusinessMediaHistoryItem] = []
    @Published private(set) var landmarkLabel: String
    @Published private(set) var isLoadingInitial = false
    @Published private(set) var isRefreshing = false
    @Published private(set) var isLoadingMore = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var nextToken: String?

    let landmarkId: String

    private let service: BusinessMediaHistoryService
    private let pageSize: Int
    private var hasLoaded = false

    init(
        landmarkId: String,
        landmarkLabel: String,
        pageSize: Int = 25,
        service: BusinessMediaHistoryService = BusinessMediaHistoryService()
    ) {
        self.landmarkId = landmarkId
        self.landmarkLabel = landmarkLabel
        self.pageSize = pageSize
        self.service = service
    }

    var hasMoreItems: Bool {
        guard let nextToken else {
            return false
        }

        return !nextToken.isEmpty
    }

    func loadInitial() async {
        guard !hasLoaded, !isLoadingInitial else {
            return
        }

        isLoadingInitial = true
        errorMessage = nil

        defer {
            isLoadingInitial = false
        }

        do {
            let response = try await service.fetchHistory(
                landmarkId: landmarkId,
                limit: pageSize
            )

            apply(response, replacingItems: true)
            hasLoaded = true
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refresh() async {
        guard !isRefreshing else {
            return
        }

        isRefreshing = true
        errorMessage = nil

        defer {
            isRefreshing = false
        }

        do {
            let response = try await service.fetchHistory(
                landmarkId: landmarkId,
                limit: pageSize
            )

            apply(response, replacingItems: true)
            hasLoaded = true
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadMore() async {
        guard hasMoreItems,
              !isLoadingMore,
              let token = nextToken else {
            return
        }

        isLoadingMore = true
        errorMessage = nil

        defer {
            isLoadingMore = false
        }

        do {
            let response = try await service.fetchHistory(
                landmarkId: landmarkId,
                limit: pageSize,
                nextToken: token
            )

            apply(response, replacingItems: false)
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func retry() async {
        if items.isEmpty {
            hasLoaded = false
            await loadInitial()
        } else {
            await refresh()
        }
    }

    private func apply(
        _ response: BusinessMediaHistoryResponse,
        replacingItems: Bool
    ) {
        landmarkLabel = response.landmarkLabel
        nextToken = response.nextToken

        if replacingItems {
            items = response.items
            return
        }

        let existingIds = Set(items.map(\.id))
        items.append(
            contentsOf: response.items.filter {
                !existingIds.contains($0.id)
            }
        )
    }
}
