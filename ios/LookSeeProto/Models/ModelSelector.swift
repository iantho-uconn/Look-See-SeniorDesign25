//
//  ModelSelector.swift
//  LookSeeProto
//
//  Continuously watches the user's location and picks the active model
//  based on which loaded model has the closest object within the activation radius.
//

import Foundation
import CoreLocation
import Combine

@MainActor
class ModelSelector: ObservableObject {
    static let shared = ModelSelector()

    // Detector observes this to know which MLModel to run.
    @Published var activeClusterID: String? = nil

    // How close the user must be to an object to activate its model.
    private let activationRadiusMeters: Double = 75.0

    private var models: [ModelInfo] = []
    private var latestUserLocation: CLLocation?

    private init() {
        observeModelState()
    }

    // MARK: - Watch ModelService State

    private func observeModelState() {
        Task {
            for await state in ModelService.shared.$state.values {
                switch state {
                case .loaded(let loadedModels):
                    models = loadedModels

                    print("🧠 ModelSelector received \(loadedModels.count) loaded model records")

                    if let latestUserLocation {
                        chooseBestCluster(for: latestUserLocation)
                    } else {
                        activeClusterID = loadedModels.first(where: {
                            $0.compiledModelURL != nil
                        })?.clusterID

                        if let activeClusterID {
                            print("🧠 ModelSelector defaulted to cluster \(activeClusterID)")
                        }
                    }

                case .notLoaded:
                    models = []
                    latestUserLocation = nil
                    activeClusterID = nil

                case .loading:
                    // Keep current model active while a user-visible load happens.
                    break

                case .failed:
                    // Keep current model active on failure.
                    break
                }
            }
        }
    }

    // MARK: - Update with User Location

    func updateUserLocation(latitude: Double, longitude: Double) {
        let userLocation = CLLocation(latitude: latitude, longitude: longitude)
        latestUserLocation = userLocation

        chooseBestCluster(for: userLocation)
    }

    // MARK: - Selection Logic

    private func chooseBestCluster(for userLocation: CLLocation) {
        var closestClusterID: String?
        var closestDistance: Double = .infinity

        for model in models {
            guard model.compiledModelURL != nil else { continue }

            for object in model.objects {
                let objectLocation = CLLocation(
                    latitude: object.lat,
                    longitude: object.lon
                )

                let distance = userLocation.distance(from: objectLocation)

                if distance <= activationRadiusMeters && distance < closestDistance {
                    closestDistance = distance
                    closestClusterID = model.clusterID
                }
            }
        }

        if let newCluster = closestClusterID {
            if newCluster != activeClusterID {
                print("📍 Switching to cluster \(newCluster) — closest object is \(String(format: "%.1f", closestDistance))m away")
                activeClusterID = newCluster
            }

            return
        }

        let activeStillLoaded = models.contains { model in
            model.clusterID == activeClusterID && model.compiledModelURL != nil
        }

        if activeStillLoaded {
            print("⚠️ No objects within \(activationRadiusMeters)m — keeping cluster \(activeClusterID ?? "none") active")
            return
        }

        activeClusterID = models.first(where: {
            $0.compiledModelURL != nil
        })?.clusterID

        if let activeClusterID {
            print("🧠 No object in activation range, defaulting to loaded cluster \(activeClusterID)")
        } else {
            print("⚠️ No compiled models available for selection")
        }
    }
}
