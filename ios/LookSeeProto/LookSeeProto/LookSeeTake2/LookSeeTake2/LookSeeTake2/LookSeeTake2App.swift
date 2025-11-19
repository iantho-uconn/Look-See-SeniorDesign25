//
//  LookSeeTake2App.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import SwiftUI

@main
struct LookSeeTake2App: App {
    // Create it once at the app root so everyone can read it
    @StateObject private var locationManager = LocationManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(locationManager)   // << inject here
        }
    }
}
