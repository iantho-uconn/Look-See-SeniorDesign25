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
    private let cacheKey = "BusinessLandmarksCache"

    init(service: BusinessLandmarkService = BusinessLandmarkService()) {
        self.service = service
        loadFromCache()
    }

    private func loadFromCache() {
        guard let data = UserDefaults.standard.data(forKey: cacheKey) else {
            print("⚠️ Cache diagnostic: No cached data found in UserDefaults.")
            return
        }
        do {
            let cached = try JSONDecoder().decode([BusinessLandmark].self, from: data)
            self.landmarks = cached
            print("✅ Cache diagnostic: Successfully loaded \(cached.count) landmarks from cache.")
        } catch {
            print("❌ Cache diagnostic: Failed to decode cached landmarks: \(error)")
        }
    }

    private func saveToCache(_ items: [BusinessLandmark]) {
        do {
            let data = try JSONEncoder().encode(items)
            UserDefaults.standard.set(data, forKey: cacheKey)
            print("💾 Cache diagnostic: Successfully saved \(items.count) landmarks to cache.")
        } catch {
            print("❌ Cache diagnostic: Failed to encode landmarks for caching: \(error)")
        }
    }

    func loadLandmarks() async {
        guard !isLoading else { return }

        isLoading = true
        errorMessage = nil

        do {
            let response = try await service.fetchBusinessLandmarks()
            let sorted = response.items.sorted {
                let comparison = $0.label.localizedCaseInsensitiveCompare($1.label)
                return comparison == .orderedAscending
            }
            landmarks = sorted
            saveToCache(sorted)
        } catch {
            errorMessage = error.localizedDescription
            print("⚠️ API fetch failed: \(error.localizedDescription)")
        }

        isLoading = false
    }

    func refresh() async {
        await loadLandmarks()
    }
    
    func replaceLandmark(_ updatedLandmark: BusinessLandmark) {
        guard let index = landmarks.firstIndex(where: { $0.landmarkId == updatedLandmark.landmarkId }) else {
            return
        }

        landmarks[index] = updatedLandmark

        landmarks.sort {
            let comparison = $0.label.localizedCaseInsensitiveCompare($1.label)
            return comparison == .orderedAscending
        }
        saveToCache(landmarks)
    }
}
