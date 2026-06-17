//
//  Tier2LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import Photos

enum Tier2ActiveMediaSheet: Identifiable {
    case recordVideo
    case galleryVideo
    case takePhoto
    case scanText 

    var id: String {
        switch self {
        case .recordVideo: return "recordVideo"
        case .galleryVideo: return "galleryVideo"
        case .takePhoto: return "takePhoto"
        case .scanText: return "scanText"
        }
    }
}

struct Tier2LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel

    @State private var pickedVideoURL: URL? = nil
    @State private var pickedImage: UIImage? = nil
    
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil

    @State private var activeSheet: Tier2ActiveMediaSheet? = nil

    @State private var statusText: String = "No media selected."

    @StateObject private var uploadService = UploadService()
    @StateObject private var locationManager = LocationManager()
    @StateObject private var nearbyService = NearbyLandmarkService()

    @State private var selectedLandmark: NearbyLandmark? = nil

    @State private var shortDescription: String = ""
    @State private var userDescription: String = ""

    private let maxAllowedAccuracy: Double = 75
    private let radiusMeters: Double = 100

    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""
    
    private var hasMedia: Bool {
        pickedVideoURL != nil || pickedImage != nil
    }

    private var hasUsableLocation: Bool {
        guard locationManager.isAuthorized,
              locationManager.latitude != nil,
              locationManager.longitude != nil,
              let accuracy = locationManager.horizontalAccuracy
        else {
            return false
        }
        return accuracy > 0 && accuracy <= maxAllowedAccuracy
    }

    private var canUpload: Bool {
        hasMedia && selectedLandmark != nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                instructionCard
                captureButtons

                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                locationSection
                nearbyLandmarksSection

                if hasMedia {
                    uploadDetailsSection
                }

                Spacer(minLength: 20)
            }
            .padding(.top, 8)
        }
        .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }
        .task {
            await refreshNearbyIfPossible()
        }
        .onChange(of: locationManager.latitude) { _, _ in
            Task { await refreshNearbyIfPossible() }
        }
        .onChange(of: locationManager.longitude) { _, _ in
            Task { await refreshNearbyIfPossible() }
        }
        .onChange(of: locationManager.horizontalAccuracy) { _, _ in
            Task { await refreshNearbyIfPossible() }
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .recordVideo:
                VideoPicker(
                    useCamera: true,
                    onPicked: handleVideoPicked,
                    onInvalidDuration: handleInvalidVideo
                )
            case .galleryVideo:
                VideoPicker(
                    useCamera: false,
                    onPicked: handleVideoPicked,
                    onInvalidDuration: handleInvalidVideo
                )
            case .takePhoto:
                PhotoPicker { image in
                    pickedImage = image
                    pickedVideoURL = nil
                    statusText = "Selected photo."
                    
                    extractedLatitude = nil
                    extractedLongitude = nil

                    shortDescription = ""
                    userDescription = ""

                    uploadService.status = "Idle"
                    uploadService.progress = 0
                }
            case .scanText: // NEW: The scanner target
                ScannerSheet(scannedText: $userDescription)
            }
        }
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(videoDurationAlertMessage)
        }
    }

    private var instructionCard: some View {
        RoundedRectangle(cornerRadius: 25)
            .stroke(Color(red: 0.75, green: 0.85, blue: 1.00))
            .fill(Color(red: 0.94, green: 0.96, blue: 1.00))
            .frame(height: 140)
            .overlay(
                Text("Record a short video or take a photo of a nearby landmark. First choose one of the valid landmarks returned for your location, then upload media to help improve recognition.")
                    .padding()
                    .foregroundStyle(Color(red: 0.11, green: 0.22, blue: 0.55))
            )
            .padding(.horizontal)
    }

    private var captureButtons: some View {
        HStack(spacing: 12) {
            Button {
                activeSheet = .recordVideo
            } label: {
                Label("Record", systemImage: "video")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .foregroundStyle(.white)
            .background(Color(red: 0.11, green: 0.22, blue: 0.55))
            .cornerRadius(15)

            Button {
                PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                    DispatchQueue.main.async {
                        activeSheet = .galleryVideo
                    }
                }
            } label: {
                Label("Gallery", systemImage: "photo.on.rectangle")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .foregroundStyle(.white)
            .background(Color(red: 0.11, green: 0.22, blue: 0.55))
            .cornerRadius(15)

            Button {
                activeSheet = .takePhoto
            } label: {
                Label("Photo", systemImage: "camera")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .foregroundStyle(.white)
            .background(Color(red: 0.11, green: 0.22, blue: 0.55))
            .cornerRadius(15)
        }
        .padding(.horizontal)
    }

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if locationManager.isAuthorized,
               let lat = locationManager.latitude,
               let lon = locationManager.longitude {

                Text("Location: \(lat), \(lon) (±\(Int(locationManager.horizontalAccuracy ?? 0))m)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if let accuracy = locationManager.horizontalAccuracy, accuracy > maxAllowedAccuracy {
                    Text("Location accuracy is too low right now. Move to an open area or wait for a better GPS fix.")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                } else {
                    Text("Location is good enough to search for nearby landmarks.")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            } else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted {
                Text("Location: Off (permission denied)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Text("Tier-2 submissions require location to find valid nearby landmarks.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            } else {
                Text("Location: Requesting permission…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 12) {
                Button("Enable Location") {
                    locationManager.requestPermissionIfNeeded()
                }
                .font(.footnote)

                Button("Refresh Nearby") {
                    Task { await refreshNearbyIfPossible(force: true) }
                }
                .font(.footnote)
                .disabled(!locationManager.isAuthorized)
            }
        }
        .padding(.horizontal)
    }

    private var nearbyLandmarksSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Nearby landmarks")
                .font(.headline)
                .padding(.horizontal)

            if nearbyService.isLoading {
                ProgressView("Looking for nearby landmarks…")
                    .padding(.horizontal)
            } else if let errorMessage = nearbyService.errorMessage {
                Text("Could not load nearby landmarks: \(errorMessage)")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
            } else if !hasUsableLocation {
                Text("Nearby landmarks will appear once location is available and accurate enough.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            } else if nearbyService.items.isEmpty {
                Text("No landmarks found within \(Int(radiusMeters)) meters.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            } else {
                VStack(spacing: 10) {
                    ForEach(nearbyService.items) { landmark in
                        Button {
                            selectedLandmark = landmark

                            if shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                shortDescription = landmark.shortDescription
                            }

                            uploadService.status = "Idle"
                            uploadService.progress = 0
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: selectedLandmark?.landmarkId == landmark.landmarkId ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(Color(red: 0.11, green: 0.22, blue: 0.55))
                                    .padding(.top, 2)

                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(landmark.label)
                                            .font(.headline)

                                        Spacer()

                                        Text("\(Int(landmark.distanceMeters))m")
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }

                                    Text(landmark.shortDescription)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                        .multilineTextAlignment(.leading)
                                }
                            }
                            .padding()
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(
                                RoundedRectangle(cornerRadius: 16)
                                    .fill(selectedLandmark?.landmarkId == landmark.landmarkId
                                          ? Color(red: 0.90, green: 0.94, blue: 1.0)
                                          : Color(.systemGray6))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(
                                        selectedLandmark?.landmarkId == landmark.landmarkId
                                        ? Color(red: 0.11, green: 0.22, blue: 0.55)
                                        : Color.clear,
                                        lineWidth: 1.5
                                    )
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }

            if let selectedLandmark {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Selected landmark")
                        .font(.headline)

                    Text(selectedLandmark.label)
                        .font(.subheadline)
                        .bold()

                    Text(selectedLandmark.shortDescription)
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    Text("Distance: \(Int(selectedLandmark.distanceMeters)) meters")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemGray6))
                .cornerRadius(16)
                .padding(.horizontal)
            }
        }
    }

    private var uploadDetailsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Short description (required)")
                .padding(.horizontal)

            TextField("e.g., 'Main entrance', 'Statue base', 'North side of building'", text: $shortDescription)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)

            // NEW: Custom ZStack UI to hold the scan button inside the text box
            ZStack(alignment: .bottomTrailing) {
                TextField("e.g., 'UConn logo, scoreboard, seats'", text: $userDescription, axis: .vertical)
                    .lineLimit(4...8) // Expand box to give more room for plaques
                    .textFieldStyle(.roundedBorder)

                Button {
                    activeSheet = .scanText
                } label: {
                    Image(systemName: "text.viewfinder")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                        .padding(8)
                        .background(Color(red: 0.11, green: 0.22, blue: 0.55))
                        .clipShape(Circle())
                        .shadow(radius: 2)
                }
                .padding(.trailing, 8)
                .padding(.bottom, 8)
            }
            .padding(.horizontal)

            Button {
                guard let selectedLandmark else { return }

                Task {
                    await vm.fetchUserEmail()
                    await uploadService.upload(
                        userEmail: vm.userEmail,
                        label: selectedLandmark.label,
                        landmarkId: selectedLandmark.landmarkId,
                        landmarkLabel: selectedLandmark.label,
                        shortDescription: shortDescription,
                        userDescription: userDescription,
                        latitude: extractedLatitude ?? locationManager.latitude,
                        longitude: extractedLongitude ?? locationManager.longitude,
                        horizontalAccuracy: locationManager.horizontalAccuracy,
                        videoURL: pickedVideoURL,
                        image: pickedImage
                    )
                }
            } label: {
                Label("Upload Media", systemImage: "arrow.up.circle")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .padding(.horizontal)
            .foregroundStyle(.white)
            .background(canUpload ? Color(red: 0.11, green: 0.22, blue: 0.55) : .gray)
            .cornerRadius(15)
            .disabled(!canUpload || shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            Text(uploadService.status)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
        }
    }

    private func refreshNearbyIfPossible(force: Bool = false) async {
        guard locationManager.isAuthorized,
              let latitude = locationManager.latitude,
              let longitude = locationManager.longitude,
              let accuracy = locationManager.horizontalAccuracy
        else {
            if force {
                nearbyService.errorMessage = "Location is not available yet."
            }
            return
        }

        guard accuracy > 0 && accuracy <= maxAllowedAccuracy else {
            if force {
                nearbyService.items = []
                nearbyService.errorMessage = "Location accuracy is currently too low."
            }
            return
        }

        await nearbyService.fetchNearby(
            latitude: latitude,
            longitude: longitude,
            radiusMeters: radiusMeters
        )

        if let selected = selectedLandmark,
           !nearbyService.items.contains(where: { $0.landmarkId == selected.landmarkId }) {
            selectedLandmark = nil
        }
    }
    
    private func handleVideoPicked(url: URL, location: CLLocationCoordinate2D?) {
        pickedVideoURL = url
        pickedImage = nil
        statusText = "Selected video: \(url.lastPathComponent)"
        
        if let loc = location {
            extractedLatitude = loc.latitude
            extractedLongitude = loc.longitude
        } else {
            extractedLatitude = nil
            extractedLongitude = nil
        }

        shortDescription = ""
        userDescription = ""

        uploadService.status = "Idle"
        uploadService.progress = 0
    }
    
    private func handleInvalidVideo(message: String) {
        pickedVideoURL = nil
        videoDurationAlertMessage = message
        showVideoDurationAlert = true
        statusText = message
        uploadService.status = "Idle"
        uploadService.progress = 0
    }
}

#Preview {
    Tier2LandmarkRecord()
}
