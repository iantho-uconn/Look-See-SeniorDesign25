//
//  ModelLoadingScreen.swift
//  LookSeeProto
//

import SwiftUI

struct ModelLoadingScreen: View {
    @StateObject private var modelService = ModelService.shared
    @StateObject private var locationManager = LocationManager()
    @State private var opacity: Double = 0
    @State private var statusMessage: String = "Getting your location…"
    @State private var failed: Bool = false

    var onComplete: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10)
                .ignoresSafeArea()

            // Glow
            Circle()
                .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                .frame(width: 300, height: 300)
                .blur(radius: 60)

            VStack(spacing: 32) {
                Spacer()

                // Logo
                VStack(spacing: 14) {
                    ZStack {
                        Circle()
                            .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                            .frame(width: 110, height: 110)
                        Image(systemName: "eye.square.fill")
                            .font(.system(size: 58))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                    }
                    Text("LookSee")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                }

                Spacer()

                // Loading state
                VStack(spacing: 16) {
                    if failed {
                        // Error state
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 28))
                                .foregroundStyle(.orange)

                            Text(statusMessage)
                                .font(.subheadline)
                                .foregroundStyle(Color.white.opacity(0.6))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)

                            Button {
                                failed = false
                                Task { await startLoading() }
                            } label: {
                                Text("Retry")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 32)
                                    .padding(.vertical, 12)
                                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                                    .cornerRadius(12)
                            }

                            Button {
                                onComplete()
                            } label: {
                                Text("Continue without model")
                                    .font(.system(size: 13))
                                    .foregroundStyle(Color.white.opacity(0.35))
                            }
                        }
                    } else {
                        // Progress state
                        VStack(spacing: 12) {
                            if case .loading = modelService.state {
                                ProgressView(value: modelService.downloadProgress)
                                    .progressViewStyle(.linear)
                                    .tint(Color(red: 0.22, green: 0.49, blue: 1.00))
                                    .frame(width: 200)
                            } else {
                                ProgressView()
                                    .tint(Color.white.opacity(0.5))
                            }

                            Text(statusMessage)
                                .font(.subheadline)
                                .foregroundStyle(Color.white.opacity(0.5))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)
                                .animation(.easeInOut, value: statusMessage)
                        }
                    }
                }
                .padding(.bottom, 60)
            }
        }
        .opacity(opacity)
        .onAppear {
            withAnimation(.easeIn(duration: 0.4)) { opacity = 1 }
            Task { await startLoading() }
        }
    }

    // MARK: - Loading sequence
    private func startLoading() async {
        // Step 1 — wait for location
        statusMessage = "Getting your location…"
        var attempts = 0
        while !locationManager.isAuthorized || locationManager.latitude == nil {
            try? await Task.sleep(nanoseconds: 500_000_000)
            attempts += 1
            if attempts > 20 {
                failed = true
                statusMessage = "Could not get your location. Make sure location access is enabled."
                return
            }
        }

        guard let lat = locationManager.latitude,
              let lon = locationManager.longitude else {
            failed = true
            statusMessage = "Location unavailable. Please try again."
            return
        }

        // Step 2 — load models
        statusMessage = "Finding models for your area…"
        await modelService.loadModels(latitude: lat, longitude: lon)

        // Step 3 — check result
        switch modelService.state {
        case .loaded(let models):
            // Use pullReason to build a meaningful status message
            switch modelService.pullReason {
            case .none:
                failed = true
                statusMessage = "No models available for your area."

            case .single(let reason):
                let model = models[0]
                statusMessage = "Loaded \(model.name) · Cluster \(model.clusterID)\n\(reason)"
                try? await Task.sleep(nanoseconds: 800_000_000)
                withAnimation(.easeOut(duration: 0.4)) { opacity = 0 }
                try? await Task.sleep(nanoseconds: 400_000_000)
                onComplete()

            case .multiple(let reasons):
                let names = models.map(\.name).joined(separator: ", ")
                let clusterIDs = Set(models.map(\.clusterID)).sorted().joined(separator: ", ")
                statusMessage = "Loaded \(models.count) models: \(names)\nClusters: \(clusterIDs)\n\(reasons.joined(separator: " · "))"
                try? await Task.sleep(nanoseconds: 800_000_000)
                withAnimation(.easeOut(duration: 0.4)) { opacity = 0 }
                try? await Task.sleep(nanoseconds: 400_000_000)
                onComplete()
            }

        case .failed(let error):
            failed = true
            statusMessage = error

        default:
            break
        }
    }
}

#Preview {
    ModelLoadingScreen(onComplete: {})
}
