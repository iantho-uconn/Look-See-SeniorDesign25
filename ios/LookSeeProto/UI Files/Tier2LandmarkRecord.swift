//
//  Tier2LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import Photos
import UIKit
import AVKit

struct Tier2LandmarkRecord: View {
    private let initialLandmarkId: String?
    private let onInitialLandmarkConsumed: () -> Void
    var archivedMedia: ArchivedMedia?

    @EnvironmentObject var vm: AuthViewModel
    @Environment(\.dismiss) var dismiss

    init(
        initialLandmarkId: String? = nil,
        archivedMedia: ArchivedMedia? = nil,
        onInitialLandmarkConsumed: @escaping () -> Void = { }
    ) {
        self.initialLandmarkId = initialLandmarkId
        self.archivedMedia = archivedMedia
        self.onInitialLandmarkConsumed = onInitialLandmarkConsumed
    }

    @State private var pickedVideoURL: URL?
    @State private var pickedImage: UIImage?
    
    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false
    @State private var showGalleryPicker = false
    
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = "No media selected."
    
    @State private var showArchivePrompt = false
    @State private var showDiscardAlert = false
    @State private var pendingArchiveURL: URL?
    @State private var pendingArchiveImage: UIImage?
    @State private var pendingArchiveLocation: CLLocationCoordinate2D?

    @State private var capturedNegativeVideo: CapturedNegativeVideo? = nil
    @State private var showNegativeCamera = false
    @State private var showNegativeGalleryPicker = false

    @StateObject private var uploadService = UploadService()
    @StateObject private var hardNegativeUploadService = HardNegativeUploadService()
    @StateObject private var locationManager = LocationManager()
    @StateObject private var nearbyService = NearbyLandmarkService()

    @State private var selectedLandmark: NearbyLandmark?
    @State private var hasAppliedInitialLandmark = false
    private let maxAllowedAccuracy: Double = 75
    private let radiusMeters: Double = 100
    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""

    private let primaryColor = Color(red: 0.11, green: 0.22, blue: 0.55)

    private var hasMedia: Bool { pickedVideoURL != nil || pickedImage != nil }
    private var hasUsableLocation: Bool {
        guard locationManager.isAuthorized, locationManager.latitude != nil, locationManager.longitude != nil, let acc = locationManager.horizontalAccuracy else { return false }
        return acc > 0 && acc <= maxAllowedAccuracy
    }
    
    private var canSubmitUpload: Bool {
        (hasMedia || capturedNegativeVideo != nil) && selectedLandmark != nil && !uploadService.isUploading && !hardNegativeUploadService.isUploading
    }
    
    private var areNegativePhotosLocked: Bool { uploadService.isUploading || hardNegativeUploadService.isUploading }

    var body: some View {
        ZStack {
            ScrollView {
                VStack(spacing: 18) {
                    if archivedMedia == nil {
                        instructionCard
                        captureButtons
                    }

                    if let url = pickedVideoURL {
                        VideoPlayer(player: AVPlayer(url: url)).frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15)).padding(.horizontal)
                    } else if let img = pickedImage {
                        Image(uiImage: img).resizable().scaledToFill().frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15)).padding(.horizontal)
                    }

                    Text(statusText).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                    locationSection
                    nearbyLandmarksSection
                    
                    if selectedLandmark != nil { uploadDetailsSection }
                    
                    Spacer(minLength: 30)
                }
                // FIX: Matched identically to the 40 used in LandmarkRecord.swift
                .padding(.top, 10)
            }
            // FIX: Locks the ScrollView to the Top so the AWS loading jump doesn't happen
            .defaultScrollAnchor(.top)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }

            if showArchivePrompt { archivePromptOverlay }
        }
        .task {
            if let archive = archivedMedia {
                if archive.isVideo { pickedVideoURL = OfflineMediaManager.shared.getFileURL(for: archive)
                } else {
                    let imgPath = OfflineMediaManager.shared.getFileURL(for: archive).path
                    if let savedImage = UIImage(contentsOfFile: imgPath) { pickedImage = savedImage }
                }
                extractedLatitude = archive.latitude
                extractedLongitude = archive.longitude
                statusText = "Loaded archived media."
            }
            await refreshNearbyIfPossible()
        }
        .onChange(of: initialLandmarkId) { _, newId in guard newId != nil else { return }; hasAppliedInitialLandmark = false; Task { await refreshNearbyIfPossible(force: true) } }
        .onChange(of: locationManager.latitude) { _, _ in Task { await refreshNearbyIfPossible() } }
        .onChange(of: locationManager.longitude) { _, _ in Task { await refreshNearbyIfPossible() } }
        .onChange(of: locationManager.horizontalAccuracy) { _, _ in Task { await refreshNearbyIfPossible() } }
        .sheet(isPresented: $showVideoPicker) { videoPicker }
        .sheet(isPresented: $showGalleryPicker) { galleryPicker }
        .sheet(isPresented: $showPhotoPicker) { photoPicker }
        .fullScreenCover(isPresented: $showNegativeCamera) {
            NegativeVideoCameraView(onDone: { video in
                capturedNegativeVideo = video
                if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() }
            })
        }
        .sheet(isPresented: $showNegativeGalleryPicker) {
            NegativeGalleryPicker(onPicked: { url in
                capturedNegativeVideo = CapturedNegativeVideo(fileURL: url)
                if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() }
            }, onInvalidDuration: { message in
                showVideoDurationAlert = true
                videoDurationAlertMessage = message
            })
        }
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) { Button("OK", role: .cancel) { } } message: { Text(videoDurationAlertMessage) }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { clearScreen() }
            Button("Cancel", role: .cancel) { }
        } message: { Text("This will remove the media and clear the form.") }
    }

    var archivePromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 20) {
                ZStack { Circle().fill(primaryColor.opacity(0.12)).frame(width: 70, height: 70); Image(systemName: "folder.badge.plus").font(.system(size: 32)).foregroundStyle(primaryColor) }
                VStack(spacing: 8) {
                    Text("Media Captured").font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    Text("What would you like to do with this media? You can upload it now or save it to your archive.")
                        .font(.subheadline).foregroundStyle(Color.white.opacity(0.5)).multilineTextAlignment(.center).padding(.horizontal, 8)
                }
                VStack(spacing: 10) {
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showArchivePrompt = false }; applyPendingMedia() } label: { Text("Upload Now").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color(red: 0.22, green: 0.49, blue: 1.00)).cornerRadius(14) }
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showArchivePrompt = false }; saveToArchiveFromPrompt() } label: { Text("Save to Offline Archive").font(.system(size: 15)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.15)).cornerRadius(14) }
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showArchivePrompt = false }; discardPendingMedia() } label: { Text("Discard").font(.system(size: 15)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.red.opacity(0.15)).cornerRadius(14) }
                }
            }.padding(24).background(Color(red: 0.11, green: 0.11, blue: 0.16)).cornerRadius(24).overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.07), lineWidth: 0.5)).padding(.horizontal, 28)
        }
    }

    private var instructionCard: some View {
        RoundedRectangle(cornerRadius: 25).stroke(Color(red: 0.75, green: 0.85, blue: 1.00)).fill(Color(red: 0.94, green: 0.96, blue: 1.00)).frame(height: 140)
            .overlay { Text("Record one short video or take one photo of a nearby landmark. Choose one of the valid landmarks returned for your location, then upload media to help improve recognition.").padding().multilineTextAlignment(.center).foregroundStyle(primaryColor) }.padding(.horizontal)
    }

    private var captureButtons: some View {
        HStack(spacing: 12) {
            Button { showVideoPicker = true } label: { Label("Record", systemImage: "video").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(uploadService.isUploading || archivedMedia != nil).opacity(uploadService.isUploading || archivedMedia != nil ? 0.6 : 1)
            Button { PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in DispatchQueue.main.async { showGalleryPicker = true } } } label: { Label("Gallery", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(uploadService.isUploading || archivedMedia != nil).opacity(uploadService.isUploading || archivedMedia != nil ? 0.6 : 1)
            Button { showPhotoPicker = true } label: { Label("Photo", systemImage: "camera").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(uploadService.isUploading || archivedMedia != nil).opacity(uploadService.isUploading || archivedMedia != nil ? 0.6 : 1)
        }.padding(.horizontal)
    }

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude {
                Text("Location: \(lat), \(lon) (±\(Int(locationManager.horizontalAccuracy ?? 0))m)").font(.footnote).foregroundStyle(.secondary)
            } else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted { Text("Location: Off — permission denied").font(.footnote).foregroundStyle(.secondary) } else { Text("Location: Requesting permission…").font(.footnote).foregroundStyle(.secondary) }
            HStack(spacing: 12) {
                Button("Enable Location") { locationManager.requestPermissionIfNeeded() }.font(.footnote).disabled(uploadService.isUploading)
                Button("Refresh Nearby") { Task { await refreshNearbyIfPossible(force: true) } }.font(.footnote).disabled(!locationManager.isAuthorized || uploadService.isUploading)
            }
        }.padding(.horizontal)
    }

    private var nearbyLandmarksSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Nearby landmarks").font(.headline).padding(.horizontal)
            nearbyLandmarkResults
            if let selectedLandmark { selectedLandmarkCard(selectedLandmark) }
        }
        .padding(.top, 24)
    }

    @ViewBuilder private var nearbyLandmarkResults: some View {
        if nearbyService.isLoading { ProgressView("Looking for nearby landmarks…").padding(.horizontal)
        } else if let errorMessage = nearbyService.errorMessage { Text("Could not load: \(errorMessage)").font(.footnote).foregroundStyle(.red).padding(.horizontal)
        } else if !hasUsableLocation { Text("Nearby landmarks will appear once location is available.").font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
        } else if nearbyService.items.isEmpty { Text("No landmarks found within \(Int(radiusMeters)) meters.").font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
        } else { VStack(spacing: 10) { ForEach(nearbyService.items) { landmark in nearbyLandmarkButton(landmark) } }.padding(.horizontal) }
    }

    private func nearbyLandmarkButton(_ landmark: NearbyLandmark) -> some View {
        let isSelected = selectedLandmark?.landmarkId == landmark.landmarkId
        return Button { selectedLandmark = landmark; if initialLandmarkId != nil && !hasAppliedInitialLandmark { hasAppliedInitialLandmark = true; onInitialLandmarkConsumed() }; uploadService.reset() } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle").foregroundStyle(primaryColor).padding(.top, 2)
                VStack(alignment: .leading, spacing: 4) { HStack { Text(landmark.label).font(.headline); Spacer(); Text("\(Int(landmark.distanceMeters))m").font(.footnote).foregroundStyle(.secondary) }; Text(landmark.shortDescription).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.leading) }
            }.padding().frame(maxWidth: .infinity, alignment: .leading).background { RoundedRectangle(cornerRadius: 16).fill(isSelected ? Color(red: 0.90, green: 0.94, blue: 1.00) : Color(uiColor: .systemGray6)) }.overlay { RoundedRectangle(cornerRadius: 16).stroke(isSelected ? primaryColor : Color.clear, lineWidth: 1.5) }
        }.buttonStyle(.plain).disabled(uploadService.isUploading)
    }

    private func selectedLandmarkCard(_ landmark: NearbyLandmark) -> some View {
        VStack(alignment: .leading, spacing: 6) { Text("Selected landmark").font(.headline); Text(landmark.label).font(.subheadline).bold(); Text("Distance: \(Int(landmark.distanceMeters)) meters").font(.footnote).foregroundStyle(.secondary) }
        .padding().frame(maxWidth: .infinity, alignment: .leading).background(Color(uiColor: .systemGray6)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
    }

    private var uploadDetailsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            negativePhotoSection
            uploadButtonRow
            uploadStatusCard
            negativeUploadStatusCard
        }
    }

    private var negativePhotoSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Negative Background Video", systemImage: "video.fill").font(.headline); Spacer()
                Image(systemName: capturedNegativeVideo != nil ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .foregroundStyle(capturedNegativeVideo != nil ? Color.green : Color.orange)
            }
            Text("Optional: Add a >= 10s pan of the surrounding area to improve recognition.").font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            
            HStack(spacing: 12) {
                Button { showNegativeCamera = true } label: {
                    Label(capturedNegativeVideo == nil ? "Record" : "Re-record", systemImage: "camera.fill").frame(maxWidth: .infinity).padding(.vertical, 13)
                }
                .foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))

                Button { PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in DispatchQueue.main.async { showNegativeGalleryPicker = true } } } label: {
                    Label("Gallery", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).padding(.vertical, 13)
                }
                .foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .disabled(areNegativePhotosLocked).opacity(areNegativePhotosLocked ? 0.6 : 1)
            
            if let video = capturedNegativeVideo {
                ZStack(alignment: .topTrailing) {
                    VideoPlayer(player: AVPlayer(url: video.fileURL)).frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15))
                    Button { video.deleteLocalFile(); capturedNegativeVideo = nil } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.white, .red) }.padding(12).disabled(areNegativePhotosLocked)
                }.padding(.top, 8)
            }
        }.padding().background(Color(uiColor: .systemGray6)).cornerRadius(18).padding(.horizontal)
    }

    private var uploadButtonRow: some View {
        HStack(spacing: 12) {
            
            if archivedMedia != nil {
                Button(role: .cancel) { saveDraftAndDismiss() } label: { Image(systemName: "arrow.uturn.backward").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(Color.white.opacity(0.2)).clipShape(RoundedRectangle(cornerRadius: 15)) }
            } else if (hasMedia || capturedNegativeVideo != nil) && !uploadService.isUploading {
                Button { saveToArchiveFromForm() } label: { Image(systemName: "folder.badge.plus").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)) }
            }
            
            Button { startUpload() } label: {
                HStack(spacing: 10) {
                    if uploadService.isUploading || hardNegativeUploadService.isUploading { ProgressView().tint(.white); Text("Uploading…").fontWeight(.semibold)
                    } else { Label("Upload Media", systemImage: "arrow.up.circle").fontWeight(.semibold) }
                }.frame(maxWidth: .infinity).padding(.vertical, 14)
            }.foregroundStyle(.white).background(canSubmitUpload ? primaryColor : Color.gray).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(!canSubmitUpload)
            
            if archivedMedia == nil && (hasMedia || capturedNegativeVideo != nil) && !uploadService.isUploading {
                Button(role: .destructive) { showDiscardAlert = true } label: { Image(systemName: "trash.fill").font(.title2).foregroundStyle(.red).frame(width: 54, height: 52).background(Color.red.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 15)) }
            }
        }.padding(.horizontal)
    }

    @ViewBuilder private var uploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if uploadService.isUploading { ProgressView().padding(.top, 2) } else { Image(systemName: uploadService.stage.systemImage).font(.title3).foregroundStyle(uploadService.stage == .complete ? .green : (uploadService.stage == .failed ? .red : primaryColor)) }
                    VStack(alignment: .leading, spacing: 4) { Text(uploadService.status).font(.headline); Text(uploadService.detail).font(.footnote).foregroundStyle(.secondary) }
                    Spacer()
                }
            }.padding().background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
        }
    }

    @ViewBuilder private var negativeUploadStatusCard: some View {
        if hardNegativeUploadService.status != "Idle" {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if hardNegativeUploadService.isUploading { ProgressView().padding(.top, 2) } else { Image(systemName: "video.fill").font(.title3).foregroundStyle(primaryColor) }
                    VStack(alignment: .leading, spacing: 4) { Text(hardNegativeUploadService.status).font(.footnote).foregroundStyle(.secondary) }
                    Spacer()
                }
                if hardNegativeUploadService.isUploading { ProgressView(value: hardNegativeUploadService.progress, total: 1) }
            }.padding().background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
        }
    }

    private var videoPicker: some View {
        VideoPicker(useCamera: true, onPicked: { url, location in withAnimation { pendingArchiveURL = url; pendingArchiveImage = nil; pendingArchiveLocation = location; showArchivePrompt = true } }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }
    
    private var galleryPicker: some View {
        VideoPicker(useCamera: false, onPicked: { url, location in pendingArchiveURL = url; pendingArchiveImage = nil; pendingArchiveLocation = location; applyPendingMedia() }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }

    private var photoPicker: some View {
        PhotoPicker { image in withAnimation { pendingArchiveImage = image; pendingArchiveURL = nil; pendingArchiveLocation = nil; showArchivePrompt = true } }
    }
    
    private func applyPendingMedia() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        if let url = pendingArchiveURL { pickedVideoURL = url; pickedImage = nil; statusText = "Selected video." } else if let img = pendingArchiveImage { pickedImage = img; pickedVideoURL = nil; statusText = "Selected photo." }
        extractedLatitude = pendingArchiveLocation?.latitude ?? locationManager.latitude; extractedLongitude = pendingArchiveLocation?.longitude ?? locationManager.longitude
        uploadService.reset(); pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil
    }
    
    private func saveToArchiveFromPrompt() {
        let lat = pendingArchiveLocation?.latitude ?? locationManager.latitude ?? 0.0
        let lon = pendingArchiveLocation?.longitude ?? locationManager.longitude ?? 0.0
        if let url = pendingArchiveURL { _ = OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon, isTier2: true) } else if let img = pendingArchiveImage { _ = OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, isTier2: true) }
        discardPendingMedia(); statusText = "Media securely saved to Offline Archive."
    }
    
    private func saveToArchiveFromForm() {
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0
        let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        
        if let url = pickedVideoURL { _ = OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon, isTier2: true)
        } else if let img = pickedImage { _ = OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, isTier2: true)
        } else { return }
        
        clearScreen(); statusText = "Media securely saved to Offline Archive."
    }
    
    private func saveDraftAndDismiss() {
        dismiss()
    }
    
    private func discardPendingMedia() {
        if let url = pendingArchiveURL { deleteTemporaryVideoIfNeeded(url) }
        pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil; pickedVideoURL = nil; pickedImage = nil; statusText = "No media selected."
    }
    
    private func clearScreen() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        capturedNegativeVideo?.deleteLocalFile()
        pickedVideoURL = nil; pickedImage = nil; extractedLatitude = nil; extractedLongitude = nil; selectedLandmark = nil; capturedNegativeVideo = nil
        if initialLandmarkId != nil { hasAppliedInitialLandmark = false; Task { await refreshNearbyIfPossible(force: true) } }
        statusText = "No media selected."; uploadService.reset(); hardNegativeUploadService.reset()
    }

    private func startUpload() {
        guard let selectedLandmark else { return }
        Task {
            await vm.fetchUserEmail()
            do {
                if hasMedia {
                    _ = try await uploadService.upload(
                        userEmail: vm.userEmail, label: selectedLandmark.label, landmarkId: selectedLandmark.landmarkId, landmarkLabel: selectedLandmark.label, shortDescription: selectedLandmark.shortDescription, userDescription: nil,
                        latitude: extractedLatitude ?? locationManager.latitude, longitude: extractedLongitude ?? locationManager.longitude, horizontalAccuracy: locationManager.horizontalAccuracy, videoURL: pickedVideoURL, image: pickedImage
                    )
                }
                
                if let negativeVideo = capturedNegativeVideo {
                    _ = try await hardNegativeUploadService.upload(landmarkId: selectedLandmark.landmarkId, video: negativeVideo)
                }
                
                print("✅ Tier-2 upload completed!")
                if let media = archivedMedia { OfflineMediaManager.shared.deleteArchive(media: media) }
                clearScreen()
                statusText = "Media uploaded successfully."
            } catch { print("❌ Tier-2 upload failed:", error.localizedDescription) }
        }
    }

    private func refreshNearbyIfPossible(force: Bool = false) async {
        guard locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude, let acc = locationManager.horizontalAccuracy, acc > 0, acc <= maxAllowedAccuracy else { return }
        await nearbyService.fetchNearby(latitude: lat, longitude: lon, radiusMeters: radiusMeters)
        if !hasAppliedInitialLandmark, let initId = initialLandmarkId, let match = nearbyService.items.first(where: { $0.landmarkId == initId }) {
            selectedLandmark = match; deleteTemporaryVideoIfNeeded(pickedVideoURL); pickedVideoURL = nil; pickedImage = nil; statusText = "No media selected."; uploadService.reset(); hasAppliedInitialLandmark = true; onInitialLandmarkConsumed()
        }
    }

    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) {
        guard let url = videoURL, archivedMedia == nil else { return }
        if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) { try? FileManager.default.removeItem(at: url) }
    }
}
