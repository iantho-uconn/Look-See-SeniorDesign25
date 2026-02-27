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
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10 // meters (tweak as desired)
        authorizationStatus = manager.authorizationStatus

        // Don’t start updates until authorized.
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
        case .denied, .restricted:
            // Leave location nil; UI can show “Location off”
            manager.stopUpdatingLocation()
        @unknown default:
            break
        }
    }

    /// Optional: call this to stop updates after you have a fix (battery friendly).
    func stop() {
        manager.stopUpdatingLocation()
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

        // Optional: stop once we have a reasonable fix
        // if last.horizontalAccuracy > 0 && last.horizontalAccuracy <= 50 {
        //     manager.stopUpdatingLocation()
        // }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Leave values as-is; could optionally set an error string
        print("Location error:", error.localizedDescription)
    }
}
