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
    @Published private(set) var isPollingProcessingItems = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var nextToken: String?
    @Published private(set) var retryingItemIds: Set<String> = []
    @Published private(set) var retryErrorsByItemId: [String: String] = [:]
    @Published private(set) var processingPollRevision = 0

    let landmarkId: String

    private let service: BusinessMediaHistoryService
    private let landmarkService: BusinessLandmarkService
    private let pageSize: Int
    private var hasLoaded = false
    private var pollingGeneration = 0

    init(
        landmarkId: String,
        landmarkLabel: String,
        pageSize: Int = 25,
        service: BusinessMediaHistoryService = BusinessMediaHistoryService(),
        landmarkService: BusinessLandmarkService = BusinessLandmarkService()
    ) {
        self.landmarkId = landmarkId
        self.landmarkLabel = landmarkLabel
        self.pageSize = pageSize
        self.service = service
        self.landmarkService = landmarkService
    }

    var hasMoreItems: Bool {
        guard let nextToken else {
            return false
        }

        return !nextToken.isEmpty
    }

    var processingItemIds: [String] {
        items
            .filter { $0.lifecycleState == .processing }
            .map(\.id)
            .sorted()
    }

    var processingPollKey: String {
        processingItemIds.joined(separator: "|") + "#\(processingPollRevision)"
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

    func isRetrying(_ item: BusinessMediaHistoryItem) -> Bool {
        retryingItemIds.contains(item.id)
    }

    func retryError(for item: BusinessMediaHistoryItem) -> String? {
        retryErrorsByItemId[item.id]
    }

    func retryProcessing(_ item: BusinessMediaHistoryItem) async {
        guard item.canRetryProcessing,
              !retryingItemIds.contains(item.id),
              let batchId = item.batchId else {
            return
        }

        retryingItemIds.insert(item.id)
        retryErrorsByItemId.removeValue(forKey: item.id)
        defer { retryingItemIds.remove(item.id) }

        do {
            _ = try await landmarkService.retryHardNegativeProcessing(
                landmarkId: landmarkId,
                batchId: batchId,
                negativeId: item.submissionId
            )
            await refreshForPolling()
            processingPollRevision += 1
        } catch is CancellationError {
            return
        } catch {
            retryErrorsByItemId[item.id] = error.localizedDescription
        }
    }

    /// Performs a small, bounded amount of background polling while the
    /// backend still reports processing items. A stale record is never changed
    /// to ready locally; only a subsequent API response can advance it.
    func pollProcessingItems(
        maximumAttempts: Int = 6,
        intervalNanoseconds: UInt64 = 12_000_000_000
    ) async {
        guard !processingItemIds.isEmpty else {
            return
        }

        pollingGeneration += 1
        let generation = pollingGeneration
        isPollingProcessingItems = true
        defer {
            if pollingGeneration == generation {
                isPollingProcessingItems = false
            }
        }

        for _ in 0..<maximumAttempts {
            guard pollingGeneration == generation,
                  !processingItemIds.isEmpty else { return }

            do {
                try await Task.sleep(nanoseconds: intervalNanoseconds)
                try Task.checkCancellation()
            } catch {
                return
            }

            await refreshForPolling()
        }
    }

    private func refreshForPolling() async {
        guard !isLoadingInitial,
              !isRefreshing,
              !isLoadingMore else {
            return
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
            // Background polling should not replace already-visible content
            // with an error. Manual refresh still reports failures normally.
            return
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
