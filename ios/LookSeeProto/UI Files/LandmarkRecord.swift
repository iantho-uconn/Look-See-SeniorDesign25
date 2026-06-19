//
//  LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import Photos
import UIKit
import AVKit

struct LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel
    @Environment(\.dismiss) var dismiss

    private let onAddMoreMedia: (String) -> Void
    var archivedMedia: ArchivedMedia?

    init(
        archivedMedia: ArchivedMedia? = nil,
        onAddMoreMedia: @escaping (String) -> Void = { _ in }
    ) {
        self.archivedMedia = archivedMedia
        self.onAddMoreMedia = onAddMoreMedia
    }

    @State private var labelText = ""
    @State private var businessLandmarkId: String?
    @State private var shortDescription = ""
    @State private var showTextScanner = false

    @State private var pickedVideoURL: URL?
    @State private var pickedImage: UIImage?
    
    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false
    @State private var showGalleryPicker = false
    
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = "No landmark media selected."
    
    @State private var showArchivePrompt = false
    @State private var showDiscardAlert = false
    @State private var pendingArchiveURL: URL?
    @State private var pendingArchiveImage: UIImage?
    @State private var pendingArchiveLocation: CLLocationCoordinate2D?

    @State private var capturedNegativePhotos: [CapturedNegativePhoto] = []
    @State private var showNegativeCamera = false
    private let minimumNegativePhotoCount = 5
    private let maximumNegativePhotoCount = 10

    @State private var completedPositiveResult: PositiveSubmissionResult?
    @State private var completedLandmarkId: String?
    @State private var isFullSubmissionComplete = false
    @State private var showCompletionPopup = false

    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""

    @StateObject private var uploadService = UploadService()
    @StateObject private var hardNegativeUploadService = HardNegativeUploadService()
    @StateObject private var locationManager = LocationManager()

    private let primaryColor = Color(red: 0.11, green: 0.22, blue: 0.55)

    private var hasPositiveMedia: Bool { pickedVideoURL != nil || pickedImage != nil }
    private var hasLabel: Bool { !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredShortDescription: Bool { !shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredNegativePhotos: Bool { capturedNegativePhotos.count >= minimumNegativePhotoCount && capturedNegativePhotos.count <= maximumNegativePhotoCount }
    private var isSubmissionRunning: Bool { uploadService.isUploading || hardNegativeUploadService.isUploading }
    
    private var canUpload: Bool {
        guard !isSubmissionRunning, !isFullSubmissionComplete else { return false }
        if completedPositiveResult != nil { return hasRequiredNegativePhotos }
        return hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativePhotos
    }
    
    private var arePositiveDetailsLocked: Bool { isSubmissionRunning || completedPositiveResult != nil || isFullSubmissionComplete }
    private var areNegativePhotosLocked: Bool { isSubmissionRunning || isFullSubmissionComplete }

    var body: some View {
        ZStack {
            ScrollView {
                VStack(spacing: 18) {
                    
                    if archivedMedia == nil {
                        positiveMediaInstructions
                        positiveMediaButtons
                    }

                    if let url = pickedVideoURL {
                        VideoPlayer(player: AVPlayer(url: url)).frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15)).padding(.horizontal)
                    } else if let img = pickedImage {
                        Image(uiImage: img).resizable().scaledToFill().frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15)).padding(.horizontal)
                    }

                    Text(statusText).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                    locationSection

                    if hasPositiveMedia || completedPositiveResult != nil {
                        landmarkForm
                    }
                    Spacer(minLength: 30)
                }.padding(.top, 8)
            }
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
                
                // LOAD DRAFTS
                labelText = archive.savedLabel ?? ""
                shortDescription = archive.savedDescription ?? ""
                if let cachedNegs = OfflineMediaManager.shared.negativeCache[archive.id] {
                    capturedNegativePhotos = cachedNegs
                }
                
                if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
                statusText = "Loaded archived media."
            }
        }
        .sheet(isPresented: $showVideoPicker) { videoPicker }
        .sheet(isPresented: $showGalleryPicker) { galleryPicker }
        .sheet(isPresented: $showPhotoPicker) { photoPicker }
        .sheet(isPresented: $showTextScanner) { ScannerSheet(scannedText: $shortDescription) }
        .fullScreenCover(isPresented: $showNegativeCamera) {
            MultiPhotoCameraView(existingPhotos: capturedNegativePhotos, minimumPhotoCount: minimumNegativePhotoCount, maximumPhotoCount: maximumNegativePhotoCount) { photos in
                capturedNegativePhotos = photos
                if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() }
            }
        }
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) { Button("OK", role: .cancel) { } } message: { Text(videoDurationAlertMessage) }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { clearScreen() }
            Button("Cancel", role: .cancel) { }
        } message: { Text("This will remove the media and clear the form.") }
        .alert("Landmark Uploaded!", isPresented: $showCompletionPopup) {
            Button("Create Another Landmark") { resetForAnotherLandmark() }
            Button("Add More Photos or Videos") { openAdditionalMediaUpload() }
        } message: { Text("Your landmark media and negative reference photos were uploaded successfully.") }
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

    private var positiveMediaInstructions: some View {
        RoundedRectangle(cornerRadius: 25).stroke(Color(red: 0.75, green: 0.85, blue: 1.00)).fill(Color(red: 0.94, green: 0.96, blue: 1.00)).frame(height: 135)
            .overlay { Text("Record one short video or take one photo of the landmark. This will be used as positive recognition data.").padding().multilineTextAlignment(.center).foregroundStyle(primaryColor) }.padding(.horizontal)
    }

    private var positiveMediaButtons: some View {
        HStack(spacing: 12) {
            Button { showVideoPicker = true } label: { Label("Record", systemImage: "video").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked || archivedMedia != nil).opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
            Button { PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in DispatchQueue.main.async { showGalleryPicker = true } } } label: { Label("Gallery", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked || archivedMedia != nil).opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
            Button { showPhotoPicker = true } label: { Label("Photo", systemImage: "camera").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked || archivedMedia != nil).opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
        }.padding(.horizontal)
    }

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            if locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude { Text("Location: \(lat), \(lon) (±\(Int(locationManager.horizontalAccuracy ?? 0))m)").font(.footnote).foregroundStyle(.secondary)
            } else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted { Text("Location: Off — permission denied").font(.footnote).foregroundStyle(.secondary)
            } else { Text("Location: Requesting permission…").font(.footnote).foregroundStyle(.secondary) }
            Button("Enable Location") { locationManager.requestPermissionIfNeeded() }.font(.footnote).disabled(arePositiveDetailsLocked)
        }.padding(.horizontal)
    }

    private var landmarkForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Label (required)").padding(.horizontal)
            TextField("e.g., Gampel Pavilion", text: $labelText).textFieldStyle(.roundedBorder).padding(.horizontal).disabled(arePositiveDetailsLocked)
            if let businessLandmarkId { Text("Landmark ID: \(businessLandmarkId)").font(.footnote).foregroundStyle(.secondary).padding(.horizontal) }
            Text("Short description (required)").padding(.horizontal)
            ZStack(alignment: .bottomTrailing) {
                TextField("e.g., Front entrance", text: $shortDescription, axis: .vertical).lineLimit(3...6).textFieldStyle(.roundedBorder).disabled(arePositiveDetailsLocked)
                Button { showTextScanner = true } label: { Image(systemName: "text.viewfinder").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white).padding(9).background(primaryColor).clipShape(Circle()).shadow(radius: 2) }
                .padding(.trailing, 8).padding(.bottom, 8).disabled(arePositiveDetailsLocked).opacity(arePositiveDetailsLocked ? 0.5 : 1)
            }.padding(.horizontal)

            if completedPositiveResult != nil && !isFullSubmissionComplete { positiveAlreadySavedCard }

            negativePhotoSection
            uploadButtonRow
            positiveUploadStatusCard
            negativeUploadStatusCard
            overallCompletionCard
        }
    }

    private var positiveAlreadySavedCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
            VStack(alignment: .leading, spacing: 4) { Text("Landmark media saved").font(.headline); Text("Your landmark and positive media were already uploaded.").font(.footnote).foregroundStyle(.secondary) }
            Spacer()
        }.padding().background(Color.green.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 16)).overlay { RoundedRectangle(cornerRadius: 16).stroke(Color.green.opacity(0.25)) }.padding(.horizontal)
    }

    private var negativePhotoSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Negative Reference Photos", systemImage: "photo.stack").font(.headline); Spacer()
                Text("\(capturedNegativePhotos.count)/\(maximumNegativePhotoCount)").font(.subheadline.bold()).foregroundStyle(hasRequiredNegativePhotos ? Color.green : Color.orange)
            }
            Text("Take 5–10 photos of nearby walls or angles that should not be recognized as this landmark.").font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Button { showNegativeCamera = true } label: { Label(capturedNegativePhotos.isEmpty ? "Take Negative Photos" : "Continue Taking Photos", systemImage: "camera.fill").frame(maxWidth: .infinity).padding(.vertical, 13) }
            .foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14)).disabled(areNegativePhotosLocked).opacity(areNegativePhotosLocked ? 0.6 : 1)
            if capturedNegativePhotos.isEmpty { Text("No negative photos captured yet.").font(.footnote).foregroundStyle(.secondary) } else { negativeThumbnailStrip }
            Label(capturedNegativePhotos.count < minimumNegativePhotoCount ? "\(minimumNegativePhotoCount - capturedNegativePhotos.count) more required." : "Required negative photos captured.", systemImage: hasRequiredNegativePhotos ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .font(.footnote.bold()).foregroundStyle(hasRequiredNegativePhotos ? Color.green : Color.orange)
        }.padding().background(Color(red: 0.11, green: 0.11, blue: 0.16)).clipShape(RoundedRectangle(cornerRadius: 18)).overlay { RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.15)) }.padding(.horizontal).padding(.top, 8)
    }

    private var negativeThumbnailStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(capturedNegativePhotos) { photo in
                    ZStack(alignment: .topTrailing) {
                        Image(uiImage: photo.thumbnail).resizable().scaledToFill().frame(width: 78, height: 78).clipShape(RoundedRectangle(cornerRadius: 10)).clipped()
                        Button { capturedNegativePhotos.removeAll { $0.id == photo.id }; photo.deleteLocalFile() } label: { Image(systemName: "xmark.circle.fill").font(.title3).symbolRenderingMode(.palette).foregroundStyle(.white, .red) }.offset(x: 6, y: -6).disabled(areNegativePhotosLocked)
                    }
                }
            }.padding(.vertical, 6).padding(.horizontal, 4)
        }
    }

    private var uploadButtonRow: some View {
        HStack(spacing: 12) {
            
            // LEFT BUTTON
            if archivedMedia != nil {
                Button(role: .cancel) { saveDraftAndDismiss() } label: {
                    Image(systemName: "arrow.uturn.backward").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(Color.white.opacity(0.2)).clipShape(RoundedRectangle(cornerRadius: 15))
                }
            } else if hasPositiveMedia && !isSubmissionRunning && !isFullSubmissionComplete {
                Button { saveToArchiveFromForm() } label: {
                    Image(systemName: "folder.badge.plus").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15))
                }
            }
            
            // CENTER BUTTON
            Button { startFullSubmission() } label: {
                HStack(spacing: 10) {
                    if isSubmissionRunning { ProgressView().tint(.white); Text(hardNegativeUploadService.isUploading ? "Uploading reference photos…" : "Uploading landmark…").fontWeight(.semibold)
                    } else { Label(isFullSubmissionComplete ? "Submission Complete" : (completedPositiveResult != nil ? "Retry Negative Photos" : "Upload Landmark"), systemImage: isFullSubmissionComplete ? "checkmark.circle.fill" : "arrow.up.circle").fontWeight(.semibold) }
                }.frame(maxWidth: .infinity).padding(.vertical, 14)
            }.foregroundStyle(.white).background(canUpload ? primaryColor : Color.gray).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(!canUpload)
            
            // RIGHT BUTTON
            if archivedMedia == nil && hasPositiveMedia && !uploadService.isUploading && !isFullSubmissionComplete {
                Button(role: .destructive) { showDiscardAlert = true } label: {
                    Image(systemName: "trash.fill").font(.title2).foregroundStyle(.red).frame(width: 54, height: 52).background(Color.red.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 15))
                }
            }
        }.padding(.horizontal)
    }

    private func startFullSubmission() {
        Task {
            guard !isSubmissionRunning, !isFullSubmissionComplete else { return }
            if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
            guard let generatedLandmarkId = businessLandmarkId else { return }

            do {
                let positiveResult: PositiveSubmissionResult
                if let existingResult = completedPositiveResult {
                    positiveResult = existingResult
                } else {
                    let trimmedLabel = labelText.trimmingCharacters(in: .whitespacesAndNewlines)
                    let trimmedShortDescription = shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmedLabel.isEmpty, !trimmedShortDescription.isEmpty else { return }
                    await vm.fetchUserEmail()
                    positiveResult = try await uploadService.upload(
                        userEmail: vm.userEmail, label: trimmedLabel, landmarkId: generatedLandmarkId, landmarkLabel: trimmedLabel, shortDescription: trimmedShortDescription, userDescription: nil,
                        latitude: extractedLatitude ?? locationManager.latitude, longitude: extractedLongitude ?? locationManager.longitude, horizontalAccuracy: locationManager.horizontalAccuracy, videoURL: pickedVideoURL, image: pickedImage
                    )
                    completedPositiveResult = positiveResult
                    statusText = "Landmark media saved. Uploading negative reference photos…"
                }

                let finalLandmarkId = positiveResult.landmarkId ?? generatedLandmarkId
                let negativeResult = try await hardNegativeUploadService.upload(landmarkId: finalLandmarkId, photos: capturedNegativePhotos)
                completedLandmarkId = finalLandmarkId
                isFullSubmissionComplete = true
                statusText = "Landmark and reference photos uploaded successfully."
                showCompletionPopup = true
                
                if let media = archivedMedia { OfflineMediaManager.shared.deleteArchive(media: media) }

            } catch { print("❌ Full landmark submission failed:", error.localizedDescription) }
        }
    }

    @ViewBuilder private var positiveUploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if uploadService.isUploading { ProgressView().padding(.top, 2) } else { Image(systemName: uploadService.stage.systemImage).font(.title3).foregroundStyle(uploadService.stage == .complete ? .green : (uploadService.stage == .failed ? .red : primaryColor)) }
                    VStack(alignment: .leading, spacing: 4) { Text(uploadService.status).font(.headline); Text(uploadService.detail).font(.footnote).foregroundStyle(.secondary) }
                    Spacer()
                }
                if uploadService.isUploading { ProgressView(value: uploadService.progress, total: 1); Text("\(Int(uploadService.progress * 100))% complete").font(.caption.bold()).foregroundStyle(.secondary) }
                if uploadService.stage == .failed { Button { uploadService.reset() } label: { Label("Dismiss", systemImage: "xmark.circle") }.font(.footnote.bold()) }
            }.padding().background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
        }
    }

    @ViewBuilder private var negativeUploadStatusCard: some View {
        if hardNegativeUploadService.status != "Idle" {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if hardNegativeUploadService.isUploading { ProgressView().padding(.top, 2) } else { Image(systemName: "photo.stack").font(.title3).foregroundStyle(primaryColor) }
                    VStack(alignment: .leading, spacing: 4) { Text(hardNegativeUploadService.status).font(.footnote).foregroundStyle(.secondary) }
                    Spacer()
                }
                if hardNegativeUploadService.isUploading { ProgressView(value: hardNegativeUploadService.progress, total: 1) }
            }.padding().background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal)
        }
    }

    @ViewBuilder private var overallCompletionCard: some View {
        if isFullSubmissionComplete {
            HStack { Image(systemName: "checkmark.seal.fill").foregroundStyle(.green); Text("Submission complete").font(.headline); Spacer() }
            .padding().background(Color.green.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.green.opacity(0.3))).padding(.horizontal)
        }
    }

    private var videoPicker: some View {
        VideoPicker(useCamera: true, onPicked: { url, location in
            withAnimation { pendingArchiveURL = url; pendingArchiveImage = nil; pendingArchiveLocation = location; showArchivePrompt = true }
        }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }
    
    private var galleryPicker: some View {
        VideoPicker(useCamera: false, onPicked: { url, location in
            pendingArchiveURL = url; pendingArchiveImage = nil; pendingArchiveLocation = location; applyPendingMedia()
        }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }

    private var photoPicker: some View {
        PhotoPicker { image in
            withAnimation { pendingArchiveImage = image; pendingArchiveURL = nil; pendingArchiveLocation = nil; showArchivePrompt = true }
        }
    }
    
    private func applyPendingMedia() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        if let url = pendingArchiveURL { pickedVideoURL = url; pickedImage = nil; statusText = "Selected video." }
        else if let img = pendingArchiveImage { pickedImage = img; pickedVideoURL = nil; statusText = "Selected photo." }
        
        extractedLatitude = pendingArchiveLocation?.latitude ?? locationManager.latitude
        extractedLongitude = pendingArchiveLocation?.longitude ?? locationManager.longitude
        
        labelText = ""; shortDescription = ""; businessLandmarkId = makeBusinessLandmarkId()
        uploadService.reset(); hardNegativeUploadService.reset(); pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil
    }
    
    private func saveToArchiveFromPrompt() {
        let lat = pendingArchiveLocation?.latitude ?? locationManager.latitude ?? 0.0
        let lon = pendingArchiveLocation?.longitude ?? locationManager.longitude ?? 0.0
        if let url = pendingArchiveURL { _ = OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon) } else if let img = pendingArchiveImage { _ = OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon) }
        discardPendingMedia(); statusText = "Media securely saved to Offline Archive."
    }
    
    private func saveToArchiveFromForm() {
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0
        let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        let newArchive: ArchivedMedia?
        
        if let url = pickedVideoURL { newArchive = OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon, label: labelText, desc: shortDescription)
        } else if let img = pickedImage { newArchive = OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, label: labelText, desc: shortDescription)
        } else { return }
        
        if let archive = newArchive { OfflineMediaManager.shared.negativeCache[archive.id] = capturedNegativePhotos }
        clearScreen(); statusText = "Media securely saved to Offline Archive."
    }
    
    private func saveDraftAndDismiss() {
        if let archive = archivedMedia {
            OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, desc: shortDescription)
            OfflineMediaManager.shared.negativeCache[archive.id] = capturedNegativePhotos
        }
        dismiss()
    }
    
    private func discardPendingMedia() {
        if let url = pendingArchiveURL { deleteTemporaryVideoIfNeeded(url) }
        pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil; pickedVideoURL = nil; pickedImage = nil; statusText = "No media selected."
    }
    
    private func clearScreen() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        for photo in capturedNegativePhotos { photo.deleteLocalFile() }
        pickedVideoURL = nil; pickedImage = nil; extractedLatitude = nil; extractedLongitude = nil; labelText = ""; shortDescription = ""; businessLandmarkId = nil; capturedNegativePhotos = []
        completedPositiveResult = nil; completedLandmarkId = nil; isFullSubmissionComplete = false
        statusText = "No landmark media selected."; uploadService.reset(); hardNegativeUploadService.reset()
    }

    private func openAdditionalMediaUpload() { guard let id = completedLandmarkId else { return }; resetForAnotherLandmark(); onAddMoreMedia(id) }
    private func resetForAnotherLandmark() { clearScreen(); showVideoDurationAlert = false }
    private func makeBusinessLandmarkId() -> String { "landmark_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))" }
    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) {
        guard let url = videoURL, archivedMedia == nil else { return }
        if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) { try? FileManager.default.removeItem(at: url) }
    }
}
