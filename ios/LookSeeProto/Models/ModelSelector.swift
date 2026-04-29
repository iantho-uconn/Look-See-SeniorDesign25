//
//  ModelSelector.swift
//  LookSeeProto
//
//  Continuously watches the user's location and picks the active model
//  based on which loaded model has an object within the activation radius.
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
    // Called by LocationManager whenever user position changes
    func updateUserLocation(latitude: Double, longitude: Double) {
        let userLocation = CLLocation(latitude: latitude, longitude: longitude)

        var closestClusterID: String? = nil
        var closestDistance: Double = .infinity

        for model in models {
            // Skip models that haven't been compiled/loaded yet
            guard model.compiledModelURL != nil else { continue }

            for object in model.objects {
                let objectLocation = CLLocation(latitude: object.lat, longitude: object.lon)
                let distance = userLocation.distance(from: objectLocation)

                if distance <= activationRadiusMeters && distance < closestDistance {
                    closestDistance = distance
                    closestClusterID = model.clusterID
                }
            }
        }

        if let newCluster = closestClusterID {
            // Found a model with an object in range — switch if different
            if newCluster != activeClusterID {
                print("📍 Switching to cluster \(newCluster) — closest object is \(String(format: "%.1f", closestDistance))m away")
                activeClusterID = newCluster
            }
        } else {
            // No object in range — log but keep current model active
            print("⚠️ No objects within \(activationRadiusMeters)m — keeping cluster \(activeClusterID ?? "none") active")
        }
    }
}
