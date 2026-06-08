//
//  ModelAutoRefreshService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/8/26.
//

import Foundation
import CoreLocation
import Combine

@MainActor
final class ModelAutoRefreshService: ObservableObject {
    static let shared = ModelAutoRefreshService()

    // MARK: - Tunables

    // Poll backend every 10 minutes while active.
    private let pollIntervalSeconds: UInt64 = 10 * 60

    // Only refresh if there has been a meaningful amount of movement
    private let minimumMovementBeforeRefreshMeters: Double = 50.0

    // Avoid accidental double-refreshes from launch/view lifecycle overlap.
    private let minimumTimeBetweenRefreshesSeconds: TimeInterval = 10 * 60

    // MARK: - State

    @Published private(set) var isPolling: Bool = false
    @Published private(set) var lastRefreshDate: Date?
    @Published private(set) var lastRefreshLocation: CLLocation?
    @Published private(set) var lastRefreshReason: String = "Not started"
    @Published private(set) var lastRefreshChangedModels: Bool = false

    private var latestLocation: CLLocation?
    private var pollingTask: Task<Void, Never>?

    private init() {}

    // MARK: - Start / Stop

    func start() {
        guard pollingTask == nil else {
            print("🔁 Model auto-refresh already running")
            return
        }

        isPolling = true
        lastRefreshReason = "Started polling"

        pollingTask = Task { [weak self] in
            guard let self else { return }

            print("🔁 Model auto-refresh started")

            while !Task.isCancelled {
                await self.performRefreshIfNeeded(force: false)

                do {
                    try await Task.sleep(
                        nanoseconds: self.pollIntervalSeconds * 1_000_000_000
                    )
                } catch {
                    break
                }
            }

            self.isPolling = false
            self.pollingTask = nil
            self.lastRefreshReason = "Stopped polling"

            print("🛑 Model auto-refresh stopped")
        }
    }

    func stop() {
        pollingTask?.cancel()
        pollingTask = nil
        isPolling = false
        lastRefreshReason = "Stopped manually"

        print("🛑 Model auto-refresh manually stopped")
    }

    // MARK: - Location Updates

    func updateLocation(latitude: Double, longitude: Double) {
        let location = CLLocation(latitude: latitude, longitude: longitude)
        latestLocation = location

        // Fast local switching between already-loaded clusters.
        ModelSelector.shared.updateUserLocation(
            latitude: latitude,
            longitude: longitude
        )
    }

    // MARK: - Manual Refresh

    func refreshNow() async {
        await performRefreshIfNeeded(force: true)
    }

    // MARK: - Refresh Logic

    private func performRefreshIfNeeded(force: Bool) async {
        guard let location = latestLocation else {
            lastRefreshReason = "Skipped: no location available yet"
            print("⚠️ Model auto-refresh skipped: no location available yet")
            return
        }

        if !force {
            if let lastDate = lastRefreshDate {
                let secondsSinceLastRefresh = Date().timeIntervalSince(lastDate)

                if secondsSinceLastRefresh < minimumTimeBetweenRefreshesSeconds {
                    lastRefreshReason = "Skipped: cooldown active"
                    print("⏳ Model auto-refresh skipped: cooldown active")
                    return
                }
            }

            if let lastLocation = lastRefreshLocation {
                let movement = location.distance(from: lastLocation)

                if movement < minimumMovementBeforeRefreshMeters {
                    lastRefreshReason = "Skipped: moved only \(String(format: "%.1f", movement))m"
                    print("📍 Model auto-refresh skipped: moved only \(String(format: "%.1f", movement))m")
                    return
                }
            }
        }

        lastRefreshDate = Date()
        lastRefreshLocation = location
        lastRefreshReason = force ? "Manual refresh" : "Polling refresh"

        print("🔁 Checking backend for closer models at \(location.coordinate.latitude), \(location.coordinate.longitude)")

        let changed = await ModelService.shared.refreshModelsSilentlyIfNeeded(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude
        )

        lastRefreshChangedModels = changed

        if changed {
            print("✅ Model auto-refresh updated the loaded model set")
        } else {
            print("✅ Model auto-refresh finished with no model-set change")
        }
    }
}
