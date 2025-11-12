//
//  ContentView.swift
//  LookSeeProto
//

import SwiftUI
import Combine
import CoreLocation

struct ContentView: View {
    @EnvironmentObject var locationManager: LocationManager
    @ObservedObject private var detector = Detector.shared

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            CameraPreview()
                .ignoresSafeArea()

            statusPill
                .padding(.top, 12)
                .frame(maxWidth: .infinity, alignment: .center)
                .allowsHitTesting(false)

            locationCard
                .padding([.leading, .bottom], 16)
        }
        .onAppear {
            // Quick console prints so we can confirm state from device logs
            print("Detector loaded? \(detector.isModelLoaded)")
            if let err = detector.lastError { print("Detector error: \(err)") }
        }
    }

    // MARK: UI
    private var statusPill: some View {
        HStack(spacing: 8) {
            Circle().frame(width: 8, height: 8)
                .foregroundStyle(detector.isModelLoaded ? .green : .red)
            Text(detector.isModelLoaded ? "Model Ready" : "Loading model…")
                .font(.footnote.monospaced())
                .foregroundStyle(.white.opacity(0.9))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.black.opacity(0.35), in: Capsule())
    }

    private var locationCard: some View {
        let coord = locationManager.location?.coordinate
        let latText = coord.map { String(format: "%.6f", $0.latitude) } ?? "--"
        let lonText = coord.map { String(format: "%.6f", $0.longitude) } ?? "--"
        let accText = locationManager.location.map { String(format: "± %.0f m", $0.horizontalAccuracy) } ?? "± — m"

        return VStack(alignment: .leading, spacing: 8) {
            Text("Location")
                .font(.headline.bold())
            Text("Lat: \(latText)")
                .font(.system(.body, design: .monospaced))
            Text("Lon: \(lonText)")
                .font(.system(.body, design: .monospaced))
            Text(accText)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
