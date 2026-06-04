//
//  LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation

struct LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel

    @State private var labelText: String = ""
    @State private var businessLandmarkId: String? = nil

    @State private var pickedVideoURL: URL? = nil
    @State private var pickedImage: UIImage? = nil

    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false

    @State private var statusText: String = "No media selected."

    @StateObject private var uploadService = UploadService()
    @StateObject private var locationManager = LocationManager()

    @State private var shortDescription: String = ""
    @State private var userDescription: String = ""
    
    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""
    
    private func makeBusinessLandmarkId() -> String {
        "landmark_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)
    }

    private var canInit: Bool {
        let hasMedia = (pickedVideoURL != nil) || (pickedImage != nil)
        return hasMedia && !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                RoundedRectangle(cornerRadius: 25)
                    .stroke(Color(red: 0.75, green: 0.85, blue: 1.00))
                    .fill(Color(red: 0.94, green: 0.96, blue: 1.00))
                    .frame(height: 125)
                    .overlay(
                        Text("Record a short video or take a photo of the landmark you’d like to add. Capture multiple angles for better recognition.")
                            .padding()
                            .foregroundStyle(Color(red: 0.11, green: 0.22, blue: 0.55))
                    )
                    .padding(.horizontal)

                HStack(spacing: 12) {
                    Button { showVideoPicker = true } label: {
                        Label("Record Video", systemImage: "video")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .foregroundStyle(.white)
                    .background(Color(red: 0.11, green: 0.22, blue: 0.55))
                    .cornerRadius(15)

                    Button { showPhotoPicker = true } label: {
                        Label("Take Photo", systemImage: "camera")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .foregroundStyle(.white)
                    .background(Color(red: 0.11, green: 0.22, blue: 0.55))
                    .cornerRadius(15)
                }
                .padding(.horizontal)

                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                VStack(alignment: .leading, spacing: 4) {
                    if locationManager.isAuthorized,
                       let lat = locationManager.latitude,
                       let lon = locationManager.longitude {
                        Text("Location: \(lat), \(lon) (±\(Int(locationManager.horizontalAccuracy ?? 0))m)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted {
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

                if pickedVideoURL != nil || pickedImage != nil {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Label (required)")
                            .padding(.horizontal)

                        TextField("e.g., Gampel Pavilion, Jonathan Statue, The Dairy Bar…", text: $labelText)
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

                        TextField("e.g., 'Front entrance', 'Scoreboard', 'Statue base'", text: $shortDescription)
                            .textFieldStyle(.roundedBorder)
                            .padding(.horizontal)

                        Text("What’s in the frame? (required)")
                            .padding(.horizontal)

                        TextField("e.g., 'UConn logo, scoreboard, seats'", text: $userDescription, axis: .vertical)
                            .lineLimit(3, reservesSpace: true)
                            .textFieldStyle(.roundedBorder)
                            .padding(.horizontal)

                        Button {
                            Task {
                                let trimmedLabel = labelText.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !trimmedLabel.isEmpty else { return }

                                await vm.fetchUserEmail()

                                if businessLandmarkId == nil {
                                    businessLandmarkId = makeBusinessLandmarkId()
                                }

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
                            Label("Upload Media", systemImage: "arrow.up.circle")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .padding(.horizontal)
                        .foregroundStyle(.white)
                        .background(canInit ? Color(red: 0.11, green: 0.22, blue: 0.55) : .gray)
                        .cornerRadius(15)
                        .disabled(!canInit)

                        Text(uploadService.status)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }
                }

                Spacer(minLength: 20)
            }
            .padding(.top, 8)
        }
        .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }
        .sheet(isPresented: $showVideoPicker) {
            VideoPicker(
                useCamera: true,
                onPicked: { url in
                    pickedVideoURL = url
                    pickedImage = nil
                    statusText = "Selected video: \(url.lastPathComponent)"

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
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(videoDurationAlertMessage)
        }
        .sheet(isPresented: $showPhotoPicker) {
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
}

#Preview {
    LandmarkRecord()
}
