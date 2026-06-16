//
//  LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import UIKit

struct LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel

    @State private var labelText: String = ""
    @State private var businessLandmarkId: String? = nil

    // Exactly one positive media item.
    @State private var pickedVideoURL: URL? = nil
    @State private var pickedImage: UIImage? = nil

    // Required hard-negative photos.
    @State private var capturedNegativePhotos: [CapturedNegativePhoto] = []

    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false
    @State private var showNegativeCamera = false

    @State private var statusText: String = "No media selected."

    @StateObject private var uploadService = UploadService()
    @StateObject private var locationManager = LocationManager()

    @State private var shortDescription: String = ""
    @State private var userDescription: String = ""

    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""

    private let minimumNegativePhotoCount = 5
    private let maximumNegativePhotoCount = 10

    private let primaryColor = Color(
        red: 0.11,
        green: 0.22,
        blue: 0.55
    )

    private func makeBusinessLandmarkId() -> String {
        "landmark_" +
        UUID()
            .uuidString
            .replacingOccurrences(of: "-", with: "")
            .prefix(8)
    }

    private var hasPositiveMedia: Bool {
        pickedVideoURL != nil || pickedImage != nil
    }

    private var hasRequiredDescriptions: Bool {
        !shortDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty
        &&
        !userDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty
    }

    private var hasRequiredNegativePhotos: Bool {
        capturedNegativePhotos.count >= minimumNegativePhotoCount &&
        capturedNegativePhotos.count <= maximumNegativePhotoCount
    }

    private var canInit: Bool {
        hasPositiveMedia &&
        !labelText
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty &&
        hasRequiredDescriptions &&
        hasRequiredNegativePhotos
    }

    private var negativePhotoStatusText: String {
        if capturedNegativePhotos.count < minimumNegativePhotoCount {
            let remaining =
                minimumNegativePhotoCount - capturedNegativePhotos.count

            return "\(remaining) more negative photo\(remaining == 1 ? "" : "s") required."
        }

        return "Required negative photos captured."
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                positiveMediaInstructions
                positiveMediaButtons

                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                locationSection

                if hasPositiveMedia {
                    landmarkForm
                }

                Spacer(minLength: 20)
            }
            .padding(.top, 8)
        }
        .safeAreaInset(edge: .top) {
            Color.clear.frame(height: 50)
        }
        .sheet(isPresented: $showVideoPicker) {
            videoPicker
        }
        .alert(
            "Invalid Video Length",
            isPresented: $showVideoDurationAlert
        ) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(videoDurationAlertMessage)
        }
        .sheet(isPresented: $showPhotoPicker) {
            photoPicker
        }
        .fullScreenCover(isPresented: $showNegativeCamera) {
            MultiPhotoCameraView(
                existingPhotos: capturedNegativePhotos,
                minimumPhotoCount: minimumNegativePhotoCount,
                maximumPhotoCount: maximumNegativePhotoCount
            ) { photos in
                capturedNegativePhotos = photos
            }
        }
    }

    // MARK: - Positive media

    private var positiveMediaInstructions: some View {
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
            .frame(height: 125)
            .overlay {
                Text(
                    "Record one short video or take one photo of the landmark you’d like to add. This will be used as positive landmark data."
                )
                .padding()
                .foregroundStyle(primaryColor)
            }
            .padding(.horizontal)
    }

    private var positiveMediaButtons: some View {
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
            .cornerRadius(15)

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
            .cornerRadius(15)
        }
        .padding(.horizontal)
    }

    // MARK: - Location

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            if locationManager.isAuthorized,
               let lat = locationManager.latitude,
               let lon = locationManager.longitude {
                Text(
                    "Location: \(lat), \(lon) (±\(Int(locationManager.horizontalAccuracy ?? 0))m)"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            } else if locationManager.authorizationStatus == .denied ||
                        locationManager.authorizationStatus == .restricted {
                Text("Location: Off (permission denied)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text("Location: Requesting permission…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Button("Enable Location") {
                locationManager.requestPermissionIfNeeded()
            }
            .font(.footnote)
        }
        .padding(.horizontal)
    }

    // MARK: - Form

    private var landmarkForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Label (required)")
                .padding(.horizontal)

            TextField(
                "e.g., Gampel Pavilion, Jonathan Statue, The Dairy Bar…",
                text: $labelText
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)

            if let businessLandmarkId {
                Text("Landmark ID: \(businessLandmarkId)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }

            Text("Short description (required)")
                .padding(.horizontal)

            TextField(
                "e.g., 'Front entrance', 'Scoreboard', 'Statue base'",
                text: $shortDescription
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)

            Text("What’s in the frame? (required)")
                .padding(.horizontal)

            TextField(
                "e.g., 'UConn logo, scoreboard, seats'",
                text: $userDescription,
                axis: .vertical
            )
            .lineLimit(3, reservesSpace: true)
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)

            negativePhotoSection

            uploadButton

            Text(uploadService.status)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
        }
    }

    // MARK: - Hard-negative photos

    private var negativePhotoSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(
                    "Negative Reference Photos",
                    systemImage: "photo.stack"
                )
                .font(.headline)

                Spacer()

                Text(
                    "\(capturedNegativePhotos.count)/\(maximumNegativePhotoCount)"
                )
                .font(.subheadline.bold())
                .foregroundStyle(
                    hasRequiredNegativePhotos ? .green : .orange
                )
            }

            Text(
                "Take 5–10 photos of nearby walls, hallways, objects, or angles that should not be recognized as this landmark. Do not include the landmark itself."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)

            Button {
                showNegativeCamera = true
            } label: {
                Label(
                    capturedNegativePhotos.isEmpty
                    ? "Take Negative Photos"
                    : "Continue Taking Photos",
                    systemImage: "camera.fill"
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
            }
            .foregroundStyle(.white)
            .background(primaryColor)
            .clipShape(RoundedRectangle(cornerRadius: 14))

            if capturedNegativePhotos.isEmpty {
                Text("No negative photos captured yet.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                negativeThumbnailStrip
            }

            Label(
                negativePhotoStatusText,
                systemImage: hasRequiredNegativePhotos
                ? "checkmark.circle.fill"
                : "exclamationmark.circle.fill"
            )
            .font(.footnote.bold())
            .foregroundStyle(
                hasRequiredNegativePhotos ? .green : .orange
            )

            Text(
                "Temporary checkpoint: these photos are currently stored on the device. API upload will be connected in the next step."
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding()
        .background(
            Color(
                red: 0.96,
                green: 0.97,
                blue: 1.00
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(
                    Color(
                        red: 0.78,
                        green: 0.84,
                        blue: 0.97
                    )
                )
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var negativeThumbnailStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(capturedNegativePhotos) { photo in
                    ZStack(alignment: .topTrailing) {
                        Image(uiImage: photo.thumbnail)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 78, height: 78)
                            .clipShape(
                                RoundedRectangle(cornerRadius: 10)
                            )
                            .clipped()

                        Button {
                            removeNegativePhoto(photo)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title3)
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .red)
                        }
                        .offset(x: 6, y: -6)
                    }
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 4)
        }
    }

    private func removeNegativePhoto(
        _ photo: CapturedNegativePhoto
    ) {
        capturedNegativePhotos.removeAll {
            $0.id == photo.id
        }

        photo.deleteLocalFile()
    }

    // MARK: - Upload

    private var uploadButton: some View {
        Button {
            Task {
                let trimmedLabel = labelText
                    .trimmingCharacters(
                        in: .whitespacesAndNewlines
                    )

                guard !trimmedLabel.isEmpty else {
                    return
                }

                await vm.fetchUserEmail()

                if businessLandmarkId == nil {
                    businessLandmarkId = makeBusinessLandmarkId()
                }

                // This checkpoint still uploads only the positive media.
                // Hard-negative API upload is added in the next phase.
                await uploadService.upload(
                    userEmail: vm.userEmail,
                    label: trimmedLabel,
                    landmarkId: businessLandmarkId,
                    landmarkLabel: trimmedLabel,
                    shortDescription: shortDescription,
                    userDescription: userDescription,
                    latitude: locationManager.latitude,
                    longitude: locationManager.longitude,
                    horizontalAccuracy: locationManager.horizontalAccuracy,
                    videoURL: pickedVideoURL,
                    image: pickedImage
                )
            }
        } label: {
            Label(
                "Upload Media",
                systemImage: "arrow.up.circle"
            )
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
        }
        .padding(.horizontal)
        .foregroundStyle(.white)
        .background(canInit ? primaryColor : .gray)
        .cornerRadius(15)
        .disabled(!canInit)
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

                labelText = ""
                shortDescription = ""
                userDescription = ""
                businessLandmarkId = makeBusinessLandmarkId()

                uploadService.status = "Idle"
                uploadService.progress = 0
            },
            onInvalidDuration: { message in
                pickedVideoURL = nil
                videoDurationAlertMessage = message
                showVideoDurationAlert = true
                statusText = message

                uploadService.status = "Idle"
                uploadService.progress = 0
            }
        )
    }

    private var photoPicker: some View {
        PhotoPicker { image in
            pickedImage = image
            pickedVideoURL = nil
            statusText = "Selected photo."

            labelText = ""
            shortDescription = ""
            userDescription = ""
            businessLandmarkId = makeBusinessLandmarkId()

            uploadService.status = "Idle"
            uploadService.progress = 0
        }
    }
}

#Preview {
    LandmarkRecord()
}
