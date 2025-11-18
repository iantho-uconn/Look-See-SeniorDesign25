//
//  LookSeeTake2App.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import SwiftUI
import SwiftData

import SwiftUI

@main
struct LookSeeTake2App: App {
    @StateObject private var locationManager = LocationManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(locationManager)
        }
    }
}
