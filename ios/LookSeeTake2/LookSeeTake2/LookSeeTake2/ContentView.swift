//
//  ContentView.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import SwiftUI
import SwiftData
import Combine

struct ContentView: View {
    @EnvironmentObject var locationManager: LocationManager
    @StateObject private var detector = Detector()

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            // Live camera + boxes
            CameraPreview(detector: detector)
                .ignoresSafeArea()

            // Model/inference status (tiny HUD)
            VStack(alignment: .leading, spacing: 8) {
                statusRow(title: "Model", value: detector.isModelLoaded ? "Loaded" : "Loading…")
                statusRow(title: "Detections", value: "\(detector.detections.count)")
                statusRow(title: "Latency", value: String(format: "%.1f ms", detector.lastInferenceMS))
            }
            .padding(10)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.bottom, 140)
            .padding(.leading, 20)

            // Location panel (keeps original functionality)
            VStack(alignment: .leading) {
                Text("Location")
                    .font(.headline)
                Text("Lat: \(locationManager.latitude ?? 0)")
                Text("Lon: \(locationManager.longitude ?? 0)")
                Text("± \(Int(locationManager.horizontalAccuracy ?? 0)) m")
            }
            .padding(14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .padding()
        }
        .onAppear {
            // Ensures permissions prompts
            _ = locationManager.latitude
        }
    }

    private func statusRow(title: String, value: String) -> some View {
        HStack {
            Text(title + ":")
                .foregroundStyle(.secondary)
            Text(value)
                .fontWeight(.semibold)
        }
        .font(.caption)
    }
}

#Preview {
    ContentView()
        .modelContainer(for: Item.self, inMemory: true)
}
