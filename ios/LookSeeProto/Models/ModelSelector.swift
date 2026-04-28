//
//  ModelSelector.swift
//  LookSeeProto
//
//  Continuously watches the user's location and picks the active model
//  based on which loaded model has an object within 10 meters of the user.
//

import Foundation
import CoreLocation
import Combine

@MainActor
class ModelSelector: ObservableObject {
    static let shared = ModelSelector()

    // The cluster ID of the currently active model
    // Detector observes this to know which MLModel to run
    @Published var activeClusterID: String? = nil

    // How close the user must be to an object to activate its model
    private let activationRadiusMeters: Double = 50.0

    private var locationTask: Task<Void, Never>? = nil
    private var models: [ModelInfo] = []

    private init() {
        observeModelState()
    }

    // MARK: - Watch ModelService state
    private func observeModelState() {
        Task {
            for await state in ModelService.shared.$state.values {
                if case .loaded(let loadedModels) = state {
                    models = loadedModels
                    // Default to first model with a compiled URL until location kicks in
                    if activeClusterID == nil {
                        activeClusterID = loadedModels.first(where: { $0.compiledModelURL != nil })?.clusterID
                    }
                } else if case .notLoaded = state {
                    models = []
                    activeClusterID = nil
                }
            }
        }
    }

    // MARK: - Update with user location
    // Call this from LocationManager whenever user position changes
    func updateUserLocation(latitude: Double, longitude: Double) {
        let userLocation = CLLocation(latitude: latitude, longitude: longitude)

        // TODO: backend dev — once objects are populated in ModelInfo,
        // this logic will automatically start working. No frontend changes needed.

        var closestClusterID: String? = nil
        var closestDistance: Double = .infinity

        for model in models {
            for object in model.objects {
                let objectLocation = CLLocation(latitude: object.lat, longitude: object.lon)
                let distance = userLocation.distance(from: objectLocation)

                if distance <= activationRadiusMeters && distance < closestDistance {
                    closestDistance = distance
                    closestClusterID = model.clusterID
                }
            }
        }

        // Only switch if we found something within range,
        // otherwise keep the current model active
        if let newCluster = closestClusterID, newCluster != activeClusterID {
            print("📍 Switching to model cluster \(newCluster) — object is \(String(format: "%.1f", closestDistance))m away")
            activeClusterID = newCluster
        }
    }
}

