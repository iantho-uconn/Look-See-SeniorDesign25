//
//  LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import Photos
import UIKit
import AVKit
import AVFoundation

struct LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel
    @Environment(\.dismiss) var dismiss

    private let onAddMoreMedia: (String) -> Void
    var archivedMedia: ArchivedMedia?

    init(archivedMedia: ArchivedMedia? = nil, onAddMoreMedia: @escaping (String) -> Void = { _ in }) {
        self.archivedMedia = archivedMedia
        self.onAddMoreMedia = onAddMoreMedia
    }

    @State private var labelText = ""
    @State private var businessLandmarkId: String?
    @State private var shortDescription = ""
    @State private var showTextScanner = false
    @State private var pickedVideoURLs: [URL] = []
    @State private var pickedImage: UIImage?
    @State private var clipDurations: [URL: Double] = [:]
    
    @State private var showVideoPicker = false
    // @State private var showPhotoPicker = false
    // @State private var showGalleryPicker = false
    
    @State private var isAddingAdditionalClip = false
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = "No landmark media selected."
    @State private var showArchivePrompt = false
    @State private var showDiscardAlert = false
    @State private var pendingArchiveURL: URL?
    @State private var pendingArchiveImage: UIImage?
    @State private var pendingArchiveLocation: CLLocationCoordinate2D?
    @State private var showAutoQueueAlert = false
    @State private var capturedNegativeVideo: CapturedNegativeVideo? = nil
    @State private var showNegativeCamera = false
    // @State private var showNegativeGalleryPicker = false
    
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
    private let minimumCombinedVideoDuration: Double = 15

    private var hasPositiveMedia: Bool { !pickedVideoURLs.isEmpty || pickedImage != nil }
    private var hasLabel: Bool { !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredShortDescription: Bool { !shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredNegativeVideo: Bool { capturedNegativeVideo != nil }
    private var totalClipDuration: Double { pickedVideoURLs.reduce(0) { $0 + (clipDurations[$1] ?? 0) } }
    private var hasMinimumClipDuration: Bool { pickedImage != nil || totalClipDuration >= minimumCombinedVideoDuration }
    private var isSubmissionRunning: Bool { uploadService.isUploading || hardNegativeUploadService.isUploading }
    private var canUpload: Bool {
        guard !isSubmissionRunning, !isFullSubmissionComplete else { return false }
        if completedPositiveResult != nil { return hasRequiredNegativeVideo }
        return hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativeVideo && hasMinimumClipDuration
    }
    private var arePositiveDetailsLocked: Bool { isSubmissionRunning || completedPositiveResult != nil || isFullSubmissionComplete }
    private var areNegativePhotosLocked: Bool { isSubmissionRunning || isFullSubmissionComplete }

    var body: some View {
        ZStack {
            ScrollView {
                VStack(spacing: 18) {
                    if archivedMedia == nil { positiveMediaInstructions; positiveMediaButtons }
                    positiveMediaPreview
                    Text(statusText).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                    locationSection
                    if hasPositiveMedia || completedPositiveResult != nil { landmarkForm }
                    Spacer(minLength: 30)
                }.padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }
            if showArchivePrompt { archivePromptOverlay }
        }
        .task {
            if let archive = archivedMedia {
                if archive.isVideo {
                    let url = OfflineMediaManager.shared.getFileURL(for: archive)
                    pickedVideoURLs = [url]; await loadDuration(for: url)
                } else {
                    if let savedImage = UIImage(contentsOfFile: OfflineMediaManager.shared.getFileURL(for: archive).path) { pickedImage = savedImage }
                }
                extractedLatitude = archive.latitude; extractedLongitude = archive.longitude
                labelText = archive.savedLabel ?? ""; shortDescription = archive.savedDescription ?? ""
                if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
                statusText = "Loaded archived media."
            }
        }
        .sheet(isPresented: $showVideoPicker) { videoPicker }
        // .sheet(isPresented: $showGalleryPicker) { galleryPicker }
        // .sheet(isPresented: $showPhotoPicker) { photoPicker }
        .sheet(isPresented: $showTextScanner) { ScannerSheet(scannedText: $shortDescription) }
        .fullScreenCover(isPresented: $showNegativeCamera) { NegativeVideoCameraView(onDone: { video in capturedNegativeVideo = video; if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() } }) }
        // .sheet(isPresented: $showNegativeGalleryPicker) { NegativeGalleryPicker(onPicked: { url in capturedNegativeVideo = CapturedNegativeVideo(fileURL: url); if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() } }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message }) }
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) { Button("OK", role: .cancel) { } } message: { Text(videoDurationAlertMessage) }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) { Button("Discard", role: .destructive) { clearScreen() }; Button("Cancel", role: .cancel) { } } message: { Text("This will remove the media and clear the form.") }
        .alert("Landmark Uploaded!", isPresented: $showCompletionPopup) { Button("Create Another Landmark") { resetForAnotherLandmark() }; Button("Add More Photos or Videos") { openAdditionalMediaUpload() } } message: { Text("Your landmark media and negative reference video were uploaded successfully.") }
        .alert("Connection Offline", isPresented: $showAutoQueueAlert) { Button("OK", role: .cancel) { } } message: { Text("You currently have no internet connection. This landmark has been securely added to your Upload Queue and will automatically sync when service returns!") }
    }

    var archivePromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 20) {
                ZStack { Circle().fill(primaryColor.opacity(0.12)).frame(width: 70, height: 70); Image(systemName: "checkmark.circle").font(.system(size: 32)).foregroundStyle(primaryColor) }
                VStack(spacing: 8) {
                    Text("Media Captured").font(.system(size: 20, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    Text("Continue to fill out the landmark details. You can upload it or queue it for later when you're done.")
                        .font(.subheadline).foregroundStyle(Color.white.opacity(0.5)).multilineTextAlignment(.center).padding(.horizontal, 8)
                }
                VStack(spacing: 10) {
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showArchivePrompt = false }; applyPendingMedia() } label: { Text("Continue").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color(red: 0.22, green: 0.49, blue: 1.00)).cornerRadius(14) }
                    Button { withAnimation(.easeInOut(duration: 0.2)) { showArchivePrompt = false }; discardPendingMedia() } label: { Text("Discard").font(.system(size: 15)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.red.opacity(0.15)).cornerRadius(14) }
                }
            }.padding(24).background(Color(red: 0.11, green: 0.11, blue: 0.16)).cornerRadius(24).overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.07), lineWidth: 0.5)).padding(.horizontal, 28)
        }
    }

    private var positiveMediaInstructions: some View {
        RoundedRectangle(cornerRadius: 25).stroke(Color(red: 0.75, green: 0.85, blue: 1.00)).fill(Color(red: 0.94, green: 0.96, blue: 1.00)).frame(height: 135)
            .overlay { Text("Record one or more short videos (combined at least 15s) of the landmark. This will be used as positive recognition data.").padding().multilineTextAlignment(.center).foregroundStyle(primaryColor) }.padding(.horizontal)
    }

    // FIX: ONLY the Record button exists here now, making it full width.
    private var positiveMediaButtons: some View {
        HStack(spacing: 12) {
            Button { isAddingAdditionalClip = false; showVideoPicker = true } label: {
                Label("Record", systemImage: "video").frame(maxWidth: .infinity).padding(.vertical, 14)
            }
            .foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15))
            .disabled(arePositiveDetailsLocked || archivedMedia != nil)
            .opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
            
            // NOTE: Gallery and Photo buttons kept commented out in case you want them back quickly
            // Button { PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in DispatchQueue.main.async { isAddingAdditionalClip = false; showGalleryPicker = true } } } label: { Label("Gallery", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked || archivedMedia != nil).opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
            // Button { showPhotoPicker = true } label: { Label("Photo", systemImage: "camera").frame(maxWidth: .infinity).padding(.vertical, 14) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked || archivedMedia != nil).opacity(arePositiveDetailsLocked || archivedMedia != nil ? 0.6 : 1)
        }.padding(.horizontal)
    }

    @ViewBuilder private var positiveMediaPreview: some View {
        if !pickedVideoURLs.isEmpty {
            VStack(spacing: 10) {
                durationSummaryBanner
                ForEach(Array(pickedVideoURLs.enumerated()), id: \.offset) { index, url in
                    ZStack(alignment: .topTrailing) {
                        VideoPlayer(player: AVPlayer(url: url)).frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 15))
                        if !arePositiveDetailsLocked { Button { removeClip(at: index) } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.white, .red) }.padding(8) }
                    }
                    .overlay(alignment: .bottomLeading) { Text(clipLabel(index: index, url: url)).font(.caption.bold()).padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.6)).foregroundStyle(.white).clipShape(Capsule()).padding(8) }
                }
                if archivedMedia == nil {
                    Button { isAddingAdditionalClip = true; showVideoPicker = true } label: { Label("Record Another Video", systemImage: "plus.circle").frame(maxWidth: .infinity).padding(.vertical, 12) }.foregroundStyle(primaryColor).background(primaryColor.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(arePositiveDetailsLocked).opacity(arePositiveDetailsLocked ? 0.6 : 1)
                }
            }.padding(.horizontal)
        } else if let img = pickedImage { Image(uiImage: img).resizable().scaledToFill().frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15)).padding(.horizontal) }
    }

    private var durationSummaryBanner: some View {
        HStack {
            Image(systemName: hasMinimumClipDuration ? "checkmark.circle.fill" : "exclamationmark.circle.fill").foregroundStyle(hasMinimumClipDuration ? Color.green : Color.orange)
            Text(durationSummaryText).font(.footnote.bold()).foregroundStyle(hasMinimumClipDuration ? Color.green : Color.orange)
            Spacer()
        }.padding(.horizontal, 4)
    }

    private var durationSummaryText: String {
        let total = totalClipDuration
        if hasMinimumClipDuration { return "\(String(format: "%.1f", total))s total — ready to upload" }
        else { return "\(String(format: "%.1f", total))s total — need \(String(format: "%.1f", max(0, minimumCombinedVideoDuration - total)))s more" }
    }

    private func clipLabel(index: Int, url: URL) -> String {
        if let duration = clipDurations[url] { return "Clip \(index + 1) · \(String(format: "%.1f", duration))s" }
        return "Clip \(index + 1) · loading…"
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
                Button { showTextScanner = true } label: { Image(systemName: "text.viewfinder").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white).padding(9).background(primaryColor).clipShape(Circle()).shadow(radius: 2) }.padding(.trailing, 8).padding(.bottom, 8).disabled(arePositiveDetailsLocked).opacity(arePositiveDetailsLocked ? 0.5 : 1)
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
            HStack { Label("Negative Background Video", systemImage: "video.fill").font(.headline); Spacer(); Image(systemName: hasRequiredNegativeVideo ? "checkmark.circle.fill" : "exclamationmark.circle.fill").foregroundStyle(hasRequiredNegativeVideo ? Color.green : Color.orange) }
            Text("Record a >= 10s video panning around the area. Do NOT include the landmark in the frame.").font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            
            // FIX: Only the Record button is here, stretching across the full width.
            HStack(spacing: 12) {
                Button { showNegativeCamera = true } label: {
                    Label(capturedNegativeVideo == nil ? "Record" : "Re-record", systemImage: "camera.fill").frame(maxWidth: .infinity).padding(.vertical, 13)
                }
                .foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                
                // NOTE: Gallery button commented out in case you want it back later
                // Button { PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in DispatchQueue.main.async { showNegativeGalleryPicker = true } } } label: { Label("Gallery", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).padding(.vertical, 13) }.foregroundStyle(.white).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
            }.disabled(areNegativePhotosLocked).opacity(areNegativePhotosLocked ? 0.6 : 1)
            
            if let video = capturedNegativeVideo {
                ZStack(alignment: .topTrailing) {
                    VideoPlayer(player: AVPlayer(url: video.fileURL)).frame(height: 220).clipShape(RoundedRectangle(cornerRadius: 15))
                    Button { video.deleteLocalFile(); capturedNegativeVideo = nil } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.white, .red) }.padding(12).disabled(areNegativePhotosLocked)
                }.padding(.top, 8)
            }
        }.padding().background(Color(red: 0.11, green: 0.11, blue: 0.16)).clipShape(RoundedRectangle(cornerRadius: 18)).overlay { RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.15)) }.padding(.horizontal).padding(.top, 8)
    }

    private var uploadButtonRow: some View {
        HStack(spacing: 12) {
            if archivedMedia != nil {
                Button(role: .cancel) { saveDraftAndDismiss() } label: { Image(systemName: "arrow.uturn.backward").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(Color.white.opacity(0.2)).clipShape(RoundedRectangle(cornerRadius: 15)) }
            } else if hasPositiveMedia && !isSubmissionRunning && !isFullSubmissionComplete {
                Button { saveToArchiveFromForm() } label: { Image(systemName: "tray.and.arrow.up.fill").font(.title2).foregroundStyle(.white).frame(width: 54, height: 52).background(canUpload ? primaryColor : Color.gray).clipShape(RoundedRectangle(cornerRadius: 15)) }.disabled(!canUpload)
            }
            Button { startFullSubmission() } label: {
                HStack(spacing: 10) {
                    if isSubmissionRunning { ProgressView().tint(.white); Text(hardNegativeUploadService.isUploading ? "Uploading reference video…" : uploadService.status).fontWeight(.semibold)
                    } else { Label(isFullSubmissionComplete ? "Submission Complete" : (completedPositiveResult != nil ? "Retry Negative Video" : "Upload Landmark"), systemImage: isFullSubmissionComplete ? "checkmark.circle.fill" : "arrow.up.circle").fontWeight(.semibold) }
                }.frame(maxWidth: .infinity).padding(.vertical, 14)
            }.foregroundStyle(.white).background(canUpload ? primaryColor : Color.gray).clipShape(RoundedRectangle(cornerRadius: 15)).disabled(!canUpload)
            if archivedMedia == nil && hasPositiveMedia && !uploadService.isUploading && !isFullSubmissionComplete {
                Button(role: .destructive) { showDiscardAlert = true } label: { Image(systemName: "trash.fill").font(.title2).foregroundStyle(.red).frame(width: 54, height: 52).background(Color.red.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 15)) }
            }
        }.padding(.horizontal)
    }

    private func startFullSubmission() {
        if !NetworkMonitor.shared.isConnected { saveToArchiveFromForm(); showAutoQueueAlert = true; return }
        Task {
            guard !isSubmissionRunning, !isFullSubmissionComplete else { return }
            if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
            guard let generatedLandmarkId = businessLandmarkId else { return }
            do {
                let positiveResult: PositiveSubmissionResult
                await vm.fetchUserEmail(); let idToken = await vm.fetchIdToken()
                if let existingResult = completedPositiveResult { positiveResult = existingResult } else {
                    let trimmedLabel = labelText.trimmingCharacters(in: .whitespacesAndNewlines); let trimmedShortDescription = shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmedLabel.isEmpty, !trimmedShortDescription.isEmpty else { return }
                    positiveResult = try await uploadService.upload(userEmail: vm.userEmail, idToken: idToken, label: trimmedLabel, landmarkId: generatedLandmarkId, landmarkLabel: trimmedLabel, shortDescription: trimmedShortDescription, userDescription: nil, latitude: extractedLatitude ?? locationManager.latitude, longitude: extractedLongitude ?? locationManager.longitude, horizontalAccuracy: locationManager.horizontalAccuracy, videoURLs: pickedVideoURLs, image: pickedImage)
                    completedPositiveResult = positiveResult; statusText = "Landmark media saved. Uploading negative reference video…"
                }
                let finalLandmarkId = positiveResult.landmarkId ?? generatedLandmarkId
                if let negativeVideo = capturedNegativeVideo { _ = try await hardNegativeUploadService.upload(landmarkId: finalLandmarkId, idToken: idToken, video: negativeVideo) }
                completedLandmarkId = finalLandmarkId; isFullSubmissionComplete = true; statusText = "Landmark and reference video uploaded successfully."; showCompletionPopup = true
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
                    if hardNegativeUploadService.isUploading { ProgressView().padding(.top, 2) } else { Image(systemName: "video.fill").font(.title3).foregroundStyle(primaryColor) }
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
            if isAddingAdditionalClip { isAddingAdditionalClip = false; appendAdditionalClip(url: url, location: location) }
            else { withAnimation { pendingArchiveURL = url; pendingArchiveImage = nil; pendingArchiveLocation = location; showArchivePrompt = true } }
        }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }

    // private var galleryPicker: some View { ... } // Kept out

    // private var photoPicker: some View { ... } // Kept out

    private func loadDuration(for url: URL) async {
        let asset = AVURLAsset(url: url)
        do { let duration = try await asset.load(.duration); let seconds = CMTimeGetSeconds(duration); guard seconds.isFinite else { return }; await MainActor.run { clipDurations[url] = seconds } } catch { print("⚠️ Could not read duration for \(url.lastPathComponent): \(error)") }
    }

    private func appendAdditionalClip(url: URL, location: CLLocationCoordinate2D?) {
        pickedVideoURLs.append(url)
        if extractedLatitude == nil, let loc = location { extractedLatitude = loc.latitude; extractedLongitude = loc.longitude }
        statusText = "Added clip \(pickedVideoURLs.count)."; Task { await loadDuration(for: url) }
    }

    private func removeClip(at index: Int) {
        guard pickedVideoURLs.indices.contains(index) else { return }
        let url = pickedVideoURLs[index]; deleteTemporaryVideoIfNeeded(url); clipDurations.removeValue(forKey: url); pickedVideoURLs.remove(at: index); statusText = pickedVideoURLs.isEmpty ? "No media selected." : "Removed clip. \(pickedVideoURLs.count) remaining."
    }

    private func applyPendingMedia() {
        deleteAllTemporaryVideos(pickedVideoURLs); pickedVideoURLs = []; clipDurations = [:]; pickedImage = nil
        if let url = pendingArchiveURL { pickedVideoURLs = [url]; statusText = "Selected video."; Task { await loadDuration(for: url) } } else if let img = pendingArchiveImage { pickedImage = img; statusText = "Selected photo." }
        extractedLatitude = pendingArchiveLocation?.latitude ?? locationManager.latitude; extractedLongitude = pendingArchiveLocation?.longitude ?? locationManager.longitude
        labelText = ""; shortDescription = ""; businessLandmarkId = makeBusinessLandmarkId(); uploadService.reset(); hardNegativeUploadService.reset(); pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil
    }

    private func saveToArchiveFromForm() {
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0; let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        if let firstURL = pickedVideoURLs.first { _ = OfflineMediaManager.shared.archiveVideo(tempURL: firstURL, lat: lat, lon: lon, landmarkId: businessLandmarkId, label: labelText, shortDesc: shortDescription, userDesc: nil, negativeVideoURL: capturedNegativeVideo?.fileURL, isTier2: false) }
        else if let img = pickedImage { _ = OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, landmarkId: businessLandmarkId, label: labelText, shortDesc: shortDescription, userDesc: nil, negativeVideoURL: capturedNegativeVideo?.fileURL, isTier2: false) }
        else { return }
        clearScreen(); statusText = "Landmark safely queued in Outbox for upload."
    }

    private func saveDraftAndDismiss() {
        if let archive = archivedMedia { OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, shortDesc: shortDescription, userDesc: nil) }
        dismiss()
    }

    private func discardPendingMedia() {
        if let url = pendingArchiveURL { deleteTemporaryVideoIfNeeded(url) }; pendingArchiveURL = nil; pendingArchiveImage = nil; pendingArchiveLocation = nil
    }

    private func clearScreen() {
        deleteAllTemporaryVideos(pickedVideoURLs); capturedNegativeVideo?.deleteLocalFile(); pickedVideoURLs = []; clipDurations = [:]; pickedImage = nil
        extractedLatitude = nil; extractedLongitude = nil; labelText = ""; shortDescription = ""; businessLandmarkId = nil; capturedNegativeVideo = nil; completedPositiveResult = nil; completedLandmarkId = nil; isFullSubmissionComplete = false
        statusText = "No landmark media selected."; uploadService.reset(); hardNegativeUploadService.reset()
    }
    
    private func resetForAnotherLandmark() { clearScreen(); showVideoDurationAlert = false }
    private func openAdditionalMediaUpload() { guard let id = completedLandmarkId else { return }; resetForAnotherLandmark(); onAddMoreMedia(id) }

    private func makeBusinessLandmarkId() -> String { "landmark_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))" }
    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) { guard let url = videoURL, archivedMedia == nil else { return }; if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) { try? FileManager.default.removeItem(at: url) } }
    private func deleteAllTemporaryVideos(_ urls: [URL]) { urls.forEach { deleteTemporaryVideoIfNeeded($0) } }
}
