//
//  LocationManger.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import Foundation
import CoreLocation
import Combine

final class LocationManager: NSObject, ObservableObject {
    private let manager = CLLocationManager()

    @Published var latitude: Double?
    @Published var longitude: Double?
    @Published var horizontalAccuracy: Double?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    var isAuthorized: Bool {
        authorizationStatus == .authorizedAlways || authorizationStatus == .authorizedWhenInUse
    }
    override init() {
        super.init()

        manager.delegate = self
        //manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 15 // meters
        authorizationStatus = manager.authorizationStatus

        requestPermissionIfNeeded()
    }

    func requestPermissionIfNeeded() {
        let status = manager.authorizationStatus
        authorizationStatus = status

        switch status {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()

        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()

            Task { @MainActor in
                ModelAutoRefreshService.shared.start()
            }

        case .denied, .restricted:
            manager.stopUpdatingLocation()

            Task { @MainActor in
                ModelAutoRefreshService.shared.stop()
            }

        @unknown default:
            break
        }
    }

    func stop() {
        manager.stopUpdatingLocation()

        Task { @MainActor in
            ModelAutoRefreshService.shared.stop()
        }
    }
}

extension LocationManager: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        requestPermissionIfNeeded()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }

        latitude = last.coordinate.latitude
        longitude = last.coordinate.longitude
        horizontalAccuracy = last.horizontalAccuracy

        guard last.horizontalAccuracy > 0 else {
            return
        }

        // Ignore very rough fixes so we do not refresh models based on bad GPS data.
        guard last.horizontalAccuracy <= 100 else {
            print("⚠️ Ignoring rough location fix: \(String(format: "%.1f", last.horizontalAccuracy))m accuracy")
            return
        }

        Task { @MainActor in
            ModelAutoRefreshService.shared.updateLocation(
                latitude: last.coordinate.latitude,
                longitude: last.coordinate.longitude
            )
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("Location error:", error.localizedDescription)
    }
}
