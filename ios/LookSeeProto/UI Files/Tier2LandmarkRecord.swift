//
//  Tier2LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import UIKit

struct Tier2LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel

    // MARK: - Positive media

    @State private var pickedVideoURL: URL?
    @State private var pickedImage: UIImage?

    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false

    @State private var statusText = "No media selected."

    // MARK: - Services

    @StateObject private var uploadService = UploadService()
    @StateObject private var locationManager = LocationManager()
    @StateObject private var nearbyService = NearbyLandmarkService()

    // MARK: - Landmark selection

    @State private var selectedLandmark: NearbyLandmark?

    @State private var shortDescription = ""
    @State private var userDescription = ""

    // MARK: - Location configuration

    private let maxAllowedAccuracy: Double = 75
    private let radiusMeters: Double = 100

    // MARK: - Video validation

    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""

    // MARK: - Appearance

    private let primaryColor = Color(
        red: 0.11,
        green: 0.22,
        blue: 0.55
    )

    // MARK: - Validation

    private var hasMedia: Bool {
        pickedVideoURL != nil || pickedImage != nil
    }

    private var hasUsableLocation: Bool {
        guard locationManager.isAuthorized,
              locationManager.latitude != nil,
              locationManager.longitude != nil,
              let accuracy = locationManager.horizontalAccuracy else {
            return false
        }

        return accuracy > 0 &&
               accuracy <= maxAllowedAccuracy
    }

    private var hasRequiredShortDescription: Bool {
        !shortDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty
    }

    private var canSubmitUpload: Bool {
        hasMedia &&
        selectedLandmark != nil &&
        hasRequiredShortDescription &&
        !uploadService.isUploading
    }

    // MARK: - Body

    var body: some View {
        mainContent
            .safeAreaInset(edge: .top) {
                Color.clear
                    .frame(height: 50)
            }
            .task {
                await refreshNearbyIfPossible()
            }
            .onChange(of: locationManager.latitude) { _, _ in
                Task {
                    await refreshNearbyIfPossible()
                }
            }
            .onChange(of: locationManager.longitude) { _, _ in
                Task {
                    await refreshNearbyIfPossible()
                }
            }
            .onChange(of: locationManager.horizontalAccuracy) { _, _ in
                Task {
                    await refreshNearbyIfPossible()
                }
            }
            .sheet(isPresented: $showVideoPicker) {
                videoPicker
            }
            .sheet(isPresented: $showPhotoPicker) {
                photoPicker
            }
            .alert(
                "Invalid Video Length",
                isPresented: $showVideoDurationAlert
            ) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(videoDurationAlertMessage)
            }
    }

    private var mainContent: some View {
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

                Spacer(minLength: 30)
            }
            .padding(.top, 8)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // MARK: - Instructions

    private var instructionCard: some View {
        RoundedRectangle(cornerRadius: 25)
            .stroke(
                Color(
                    red: 0.75,
                    green: 0.85,
                    blue: 1.00
                )
            )
            .fill(
                Color(
                    red: 0.94,
                    green: 0.96,
                    blue: 1.00
                )
            )
            .frame(height: 140)
            .overlay {
                Text(
                    "Record one short video or take one photo of a nearby landmark. Choose one of the valid landmarks returned for your location, then upload media to help improve recognition."
                )
                .padding()
                .multilineTextAlignment(.center)
                .foregroundStyle(primaryColor)
            }
            .padding(.horizontal)
    }

    // MARK: - Capture buttons

    private var captureButtons: some View {
        HStack(spacing: 12) {
            Button {
                showVideoPicker = true
            } label: {
                Label(
                    "Record Video",
                    systemImage: "video"
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
            .foregroundStyle(.white)
            .background(primaryColor)
            .clipShape(
                RoundedRectangle(cornerRadius: 15)
            )
            .disabled(uploadService.isUploading)
            .opacity(uploadService.isUploading ? 0.6 : 1)

            Button {
                showPhotoPicker = true
            } label: {
                Label(
                    "Take Photo",
                    systemImage: "camera"
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
            .foregroundStyle(.white)
            .background(primaryColor)
            .clipShape(
                RoundedRectangle(cornerRadius: 15)
            )
            .disabled(uploadService.isUploading)
            .opacity(uploadService.isUploading ? 0.6 : 1)
        }
        .padding(.horizontal)
    }

    // MARK: - Location

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if locationManager.isAuthorized,
               let latitude = locationManager.latitude,
               let longitude = locationManager.longitude {

                Text(
                    "Location: \(latitude), \(longitude) " +
                    "(±\(Int(locationManager.horizontalAccuracy ?? 0))m)"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

                if let accuracy = locationManager.horizontalAccuracy,
                   accuracy > maxAllowedAccuracy {

                    Text(
                        "Location accuracy is too low right now. Move to an open area or wait for a better GPS fix."
                    )
                    .font(.footnote)
                    .foregroundStyle(.orange)

                } else {
                    Text(
                        "Location is accurate enough to search for nearby landmarks."
                    )
                    .font(.footnote)
                    .foregroundStyle(.green)
                }

            } else if locationManager.authorizationStatus == .denied ||
                        locationManager.authorizationStatus == .restricted {

                Text("Location: Off — permission denied")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Text(
                    "Tier-2 submissions require location access to find valid nearby landmarks."
                )
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
                .disabled(uploadService.isUploading)

                Button("Refresh Nearby") {
                    Task {
                        await refreshNearbyIfPossible(
                            force: true
                        )
                    }
                }
                .font(.footnote)
                .disabled(
                    !locationManager.isAuthorized ||
                    uploadService.isUploading
                )
            }
        }
        .padding(.horizontal)
    }

    // MARK: - Nearby landmarks

    private var nearbyLandmarksSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Nearby landmarks")
                .font(.headline)
                .padding(.horizontal)

            nearbyLandmarkResults

            if let selectedLandmark {
                selectedLandmarkCard(
                    selectedLandmark
                )
            }
        }
    }

    @ViewBuilder
    private var nearbyLandmarkResults: some View {
        if nearbyService.isLoading {
            ProgressView(
                "Looking for nearby landmarks…"
            )
            .padding(.horizontal)

        } else if let errorMessage = nearbyService.errorMessage {
            Text(
                "Could not load nearby landmarks: \(errorMessage)"
            )
            .font(.footnote)
            .foregroundStyle(.red)
            .padding(.horizontal)

        } else if !hasUsableLocation {
            Text(
                "Nearby landmarks will appear once location is available and accurate enough."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.horizontal)

        } else if nearbyService.items.isEmpty {
            Text(
                "No landmarks found within \(Int(radiusMeters)) meters."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.horizontal)

        } else {
            VStack(spacing: 10) {
                ForEach(nearbyService.items) { landmark in
                    nearbyLandmarkButton(landmark)
                }
            }
            .padding(.horizontal)
        }
    }

    private func nearbyLandmarkButton(
        _ landmark: NearbyLandmark
    ) -> some View {
        let isSelected =
            selectedLandmark?.landmarkId ==
            landmark.landmarkId

        return Button {
            selectedLandmark = landmark

            if shortDescription
                .trimmingCharacters(
                    in: .whitespacesAndNewlines
                )
                .isEmpty {

                shortDescription =
                    landmark.shortDescription
            }

            uploadService.reset()

        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(
                    systemName: isSelected
                    ? "largecircle.fill.circle"
                    : "circle"
                )
                .foregroundStyle(primaryColor)
                .padding(.top, 2)

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(landmark.label)
                            .font(.headline)

                        Spacer()

                        Text(
                            "\(Int(landmark.distanceMeters))m"
                        )
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
            .frame(
                maxWidth: .infinity,
                alignment: .leading
            )
            .background {
                RoundedRectangle(cornerRadius: 16)
                    .fill(
                        isSelected
                        ? Color(
                            red: 0.90,
                            green: 0.94,
                            blue: 1.00
                        )
                        : Color(
                            uiColor: .systemGray6
                        )
                    )
            }
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        isSelected
                        ? primaryColor
                        : Color.clear,
                        lineWidth: 1.5
                    )
            }
        }
        .buttonStyle(.plain)
        .disabled(uploadService.isUploading)
    }

    private func selectedLandmarkCard(
        _ landmark: NearbyLandmark
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Selected landmark")
                .font(.headline)

            Text(landmark.label)
                .font(.subheadline)
                .bold()

            Text(landmark.shortDescription)
                .font(.footnote)
                .foregroundStyle(.secondary)

            Text(
                "Distance: \(Int(landmark.distanceMeters)) meters"
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding()
        .frame(
            maxWidth: .infinity,
            alignment: .leading
        )
        .background(
            Color(uiColor: .systemGray6)
        )
        .clipShape(
            RoundedRectangle(cornerRadius: 16)
        )
        .padding(.horizontal)
    }

    // MARK: - Upload details

    private var uploadDetailsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Short description (required)")
                .padding(.horizontal)

            TextField(
                "e.g., Main entrance, statue base, north side of building",
                text: $shortDescription
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)
            .disabled(uploadService.isUploading)

            uploadButton

            uploadStatusCard
        }
    }

    private var uploadButton: some View {
        Button {
            startUpload()
        } label: {
            HStack(spacing: 10) {
                if uploadService.isUploading {
                    ProgressView()
                        .tint(.white)

                    Text("Uploading…")
                        .fontWeight(.semibold)
                } else {
                    Label(
                        "Upload Media",
                        systemImage: "arrow.up.circle"
                    )
                    .fontWeight(.semibold)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
        }
        .padding(.horizontal)
        .foregroundStyle(.white)
        .background(
            canSubmitUpload
            ? primaryColor
            : Color.gray
        )
        .clipShape(
            RoundedRectangle(cornerRadius: 15)
        )
        .disabled(!canSubmitUpload)
    }

    // MARK: - Upload status card

    @ViewBuilder
    private var uploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if uploadService.isUploading {
                        ProgressView()
                            .padding(.top, 2)
                    } else {
                        Image(
                            systemName:
                                uploadService.stage.systemImage
                        )
                        .font(.title3)
                        .foregroundStyle(
                            uploadStatusColor
                        )
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(uploadService.status)
                            .font(.headline)

                        Text(uploadService.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(
                                horizontal: false,
                                vertical: true
                            )
                    }

                    Spacer()
                }

                if uploadService.isUploading {
                    ProgressView(
                        value: uploadService.progress,
                        total: 1
                    )
                    .progressViewStyle(.linear)

                    Text(
                        "\(Int(uploadService.progress * 100))% complete"
                    )
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                    Text(
                        "Please keep LookSee open until the upload finishes."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                if uploadService.stage == .failed {
                    Button {
                        uploadService.reset()
                    } label: {
                        Label(
                            "Dismiss Error",
                            systemImage: "xmark.circle"
                        )
                    }
                    .font(.footnote.bold())
                }
            }
            .padding()
            .background(
                Color(
                    uiColor: .secondarySystemBackground
                )
            )
            .clipShape(
                RoundedRectangle(cornerRadius: 16)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        uploadStatusColor.opacity(0.3)
                    )
            }
            .padding(.horizontal)
        }
    }

    private var uploadStatusColor: Color {
        switch uploadService.stage {
        case .complete:
            return .green

        case .failed:
            return .red

        default:
            return primaryColor
        }
    }

    // MARK: - Pickers

    private var videoPicker: some View {
        VideoPicker(
            useCamera: true,
            onPicked: { url in
                pickedVideoURL = url
                pickedImage = nil

                statusText =
                    "Selected video: \(url.lastPathComponent)"

                shortDescription = ""
                userDescription = ""

                uploadService.reset()
            },
            onInvalidDuration: { message in
                pickedVideoURL = nil

                videoDurationAlertMessage = message
                showVideoDurationAlert = true
                statusText = message

                uploadService.reset()
            }
        )
    }

    private var photoPicker: some View {
        PhotoPicker { image in
            pickedImage = image
            pickedVideoURL = nil

            statusText = "Selected photo."

            shortDescription = ""
            userDescription = ""

            uploadService.reset()
        }
    }

    // MARK: - Upload action

    private func startUpload() {
        guard let selectedLandmark else {
            return
        }

        Task {
            await vm.fetchUserEmail()

            do {
                let result = try await uploadService.upload(
                    userEmail: vm.userEmail,
                    label: selectedLandmark.label,
                    landmarkId:
                        selectedLandmark.landmarkId,
                    landmarkLabel:
                        selectedLandmark.label,
                    shortDescription:
                        shortDescription,
                    userDescription:
                        userDescription,
                    latitude:
                        locationManager.latitude,
                    longitude:
                        locationManager.longitude,
                    horizontalAccuracy:
                        locationManager.horizontalAccuracy,
                    videoURL:
                        pickedVideoURL,
                    image:
                        pickedImage
                )

                print(
                    "✅ Tier-2 upload completed:",
                    result.submissionId,
                    result.landmarkId ??
                    "no-landmark-id"
                )

            } catch {
                print(
                    "❌ Tier-2 upload failed:",
                    error.localizedDescription
                )
            }
        }
    }

    // MARK: - Nearby landmark refresh

    private func refreshNearbyIfPossible(
        force: Bool = false
    ) async {
        guard locationManager.isAuthorized,
              let latitude =
                locationManager.latitude,
              let longitude =
                locationManager.longitude,
              let accuracy =
                locationManager.horizontalAccuracy else {

            if force {
                nearbyService.errorMessage =
                    "Location is not available yet."
            }

            return
        }

        guard accuracy > 0,
              accuracy <= maxAllowedAccuracy else {

            if force {
                nearbyService.items = []
                nearbyService.errorMessage =
                    "Location accuracy is currently too low."
            }

            return
        }

        await nearbyService.fetchNearby(
            latitude: latitude,
            longitude: longitude,
            radiusMeters: radiusMeters
        )

        if let selectedLandmark,
           !nearbyService.items.contains(
                where: {
                    $0.landmarkId ==
                    selectedLandmark.landmarkId
                }
           ) {
            self.selectedLandmark = nil
        }
    }
}

#Preview {
    Tier2LandmarkRecord()
}
