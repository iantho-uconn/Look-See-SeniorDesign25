//
//  BusinessLandmarksViewModel.swift
//  LookSeeProto
//
//  View model for the business landmark management list.
//

import Foundation
import Combine

@MainActor
final class BusinessLandmarksViewModel: ObservableObject {
    @Published private(set) var landmarks: [BusinessLandmark] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let service: BusinessLandmarkService

    init(service: BusinessLandmarkService = BusinessLandmarkService()) {
        self.service = service
    }

    func loadLandmarks() async {
        guard !isLoading else { return }

        isLoading = true
        errorMessage = nil

        do {
            let response = try await service.fetchBusinessLandmarks()
            landmarks = response.items.sorted {
                let comparison = $0.label.localizedCaseInsensitiveCompare($1.label)
                return comparison == .orderedAscending
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func refresh() async {
        await loadLandmarks()
    }
}
