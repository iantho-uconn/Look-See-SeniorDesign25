//
//  LandmarkRecord.swift
//  LookSeeProto
//


import SwiftUI
import CoreLocation
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
    
    @State private var showVideoCamera = false
    
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = "No landmark media selected."
    @State private var showArchivePrompt = false
    @State private var showDiscardAlert = false
    
    @State private var showLimitAlert = false
    @State private var limitAlertTitle = ""
    @State private var limitAlertMessage = ""
    
    @State private var pendingArchiveURLs: [URL] = []
    @State private var isStitchingVideos = false
    
    @State private var showAutoQueueAlert = false
    @State private var capturedNegativeVideo: CapturedNegativeVideo? = nil
    @State private var showNegativeCamera = false
    
    @State private var completedPositiveResult: PositiveSubmissionResult?
    @State private var completedLandmarkId: String?
    @State private var isFullSubmissionComplete = false
    @State private var showCompletionPopup = false
    
    @State private var isFormVisible = false

    @StateObject private var uploadService = UploadService()
    @StateObject private var hardNegativeUploadService = HardNegativeUploadService()
    @StateObject private var locationManager = LocationManager()

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    private let minimumCombinedVideoDuration: Double = 15.0

    private var hasPositiveMedia: Bool { !pickedVideoURLs.isEmpty || pickedImage != nil }
    private var hasLabel: Bool { !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredShortDescription: Bool { !shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredNegativeVideo: Bool { capturedNegativeVideo != nil }
    private var totalClipDuration: Double { pickedVideoURLs.reduce(0) { $0 + (clipDurations[$1] ?? 0) } }
    private var hasMinimumClipDuration: Bool { pickedImage != nil || totalClipDuration >= minimumCombinedVideoDuration }
    private var isSubmissionRunning: Bool { uploadService.isUploading || hardNegativeUploadService.isUploading || isStitchingVideos }
    private var canUpload: Bool {
        guard !isSubmissionRunning, !isFullSubmissionComplete else { return false }
        if completedPositiveResult != nil { return hasRequiredNegativeVideo }
        return hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativeVideo && hasMinimumClipDuration
    }
    private var arePositiveDetailsLocked: Bool { isSubmissionRunning || completedPositiveResult != nil || isFullSubmissionComplete }
    private var areNegativePhotosLocked: Bool { isSubmissionRunning || isFullSubmissionComplete }

    var body: some View {
        ZStack {
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 20) {
                    if !hasPositiveMedia {
                        positiveMediaInstructions
                        positiveMediaButtons
                    }
                    
                    positiveMediaPreview
                    
                    if !statusText.isEmpty {
                        Text(statusText)
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }
                    
                    locationSection
                    
                    if isFormVisible {
                        landmarkForm
                        uploadButtonRow
                        positiveUploadStatusCard
                        negativeUploadStatusCard
                        overallCompletionCard
                    }
                    
                    Spacer(minLength: 40)
                }.padding(.top, 16)
            }
            .scrollDismissesKeyboard(.immediately)
            .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }
            
            if showArchivePrompt { archivePromptOverlay }
            if isStitchingVideos { processingOverlay }
        }
        .task {
            if let archive = archivedMedia {
                isFormVisible = true
                Task.detached {
                    let fileURL = OfflineMediaManager.shared.getFileURL(for: archive)
                    var videoURLs: [URL] = []
                    var loadedImage: UIImage? = nil
                    var negVideo: CapturedNegativeVideo? = nil

                    if archive.isVideo { videoURLs = [fileURL] }
                    else { if let savedImage = UIImage(contentsOfFile: fileURL.path) { loadedImage = savedImage } }

                    if let negURL = OfflineMediaManager.shared.getNegativeVideoURL(for: archive) {
                        if FileManager.default.fileExists(atPath: negURL.path) { negVideo = CapturedNegativeVideo(fileURL: negURL) }
                    }

                    let finalURLs = videoURLs
                    let finalImage = loadedImage
                    let finalNeg = negVideo

                    await MainActor.run {
                        self.pickedVideoURLs = finalURLs
                        self.pickedImage = finalImage
                        self.capturedNegativeVideo = finalNeg
                        self.extractedLatitude = archive.latitude
                        self.extractedLongitude = archive.longitude
                        self.labelText = archive.savedLabel ?? ""
                        self.shortDescription = archive.savedDescription ?? ""
                        if self.businessLandmarkId == nil { self.businessLandmarkId = self.makeBusinessLandmarkId() }
                        self.statusText = "Loaded archived media."
                    }

                    for url in finalURLs { await self.loadDuration(for: url) }
                }
            }
        }
        .fullScreenCover(isPresented: $showVideoCamera) {
            PositiveVideoCameraView { urls in
                withAnimation(.spring()) {
                    pendingArchiveURLs = urls
                    showArchivePrompt = true
                }
            }
        }
        .sheet(isPresented: $showTextScanner) { ScannerSheet(scannedText: $shortDescription) }
        .fullScreenCover(isPresented: $showNegativeCamera) { NegativeVideoCameraView(onDone: { video in capturedNegativeVideo = video; if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() } }) }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) { Button("Discard", role: .destructive) { clearScreen() }; Button("Cancel", role: .cancel) { } } message: { Text("This will remove the media and clear the form.") }
        .alert("Landmark Uploaded!", isPresented: $showCompletionPopup) {
            if archivedMedia != nil { Button("Done", role: .cancel) { dismiss() } }
            else { Button("Create Another Landmark") { resetForAnotherLandmark() }; Button("Add More Photos or Videos") { openAdditionalMediaUpload() } }
        } message: { Text("Your landmark media and negative reference video were uploaded successfully.") }
        .alert("Connection Offline", isPresented: $showAutoQueueAlert) { Button("OK", role: .cancel) { if archivedMedia != nil { dismiss() } } } message: { Text("You currently have no internet connection. This landmark has been securely added to your Upload Queue and will automatically sync when service returns!") }
        .alert(limitAlertTitle, isPresented: $showLimitAlert) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(limitAlertMessage)
        }
    }

    var processingOverlay: some View {
        ZStack {
            Color.black.opacity(0.8).ignoresSafeArea(.all)
            VStack(spacing: 20) {
                ProgressView().tint(.primary)
                Text("Processing videos")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("Please wait a moment.")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(30)
            .background(Color(uiColor: .systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
        }
        .zIndex(100)
    }

    var archivePromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 24) {
                ZStack {
                    Circle().fill(primaryColor.opacity(0.15)).frame(width: 70, height: 70)
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 32)).foregroundStyle(primaryColor)
                }
                VStack(spacing: 8) {
                    Text("Capture Complete")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("Continue to fill out the landmark details. You can upload it when you're done.")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                VStack(spacing: 12) {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        withAnimation(.spring()) { showArchivePrompt = false }
                        processAndStitchPendingMedia()
                    } label: {
                        Text("Continue")
                            .font(.system(size: 17, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(primaryColor)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        withAnimation(.spring()) { showArchivePrompt = false }
                        discardPendingMedia()
                    } label: {
                        Text("Discard")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color.red.opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
            }
            .padding(30)
            .background(Color(uiColor: .systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
            .padding(.horizontal, 24)
        }
    }

    private var positiveMediaInstructions: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(primaryColor)
                .padding(.top, 4)
            
            VStack(alignment: .leading, spacing: 6) {
                Text("Capture Positive Media")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("Follow the on-screen steps to capture the different angles of the landmark. This video should be from a typical place where a user may see the landmark.")
                    .font(.system(size: 15, weight: .regular))
                    .foregroundStyle(.secondary)
                    .lineSpacing(2)
            }
            Spacer()
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var positiveMediaButtons: some View {
        HStack {
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                showVideoCamera = true
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "video.fill")
                    Text("Start Recording Process")
                }
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
            }
            .foregroundStyle(.white)
            .background(primaryColor)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .disabled(arePositiveDetailsLocked)
            .opacity(arePositiveDetailsLocked ? 0.6 : 1)
        }
        .padding(.horizontal)
    }

    @ViewBuilder private var positiveMediaPreview: some View {
        if !pickedVideoURLs.isEmpty {
            VStack(spacing: 16) {
                durationSummaryBanner
                ForEach(Array(pickedVideoURLs.enumerated()), id: \.element) { index, url in
                    ZStack(alignment: .topTrailing) {
                        UploadFormVideoPlayer(url: url)
                            .equatable()
                            .id(url.absoluteString)
                            .frame(maxWidth: .infinity)
                            .frame(height: 240)
                            .clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .ignoresSafeArea(.keyboard)
                        
                        if !arePositiveDetailsLocked {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                removeClip(url: url)
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 32, height: 32)
                                    .background(.black.opacity(0.6))
                                    .clipShape(Circle())
                            }
                            .padding(12)
                        }
                    }
                    .overlay(alignment: .bottomLeading) {
                        Text(clipLabel(index: index, url: url))
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial)
                            .environment(\.colorScheme, .dark)
                            .clipShape(Capsule())
                            .padding(12)
                    }
                }
            }.padding(.horizontal)
        } else if let img = pickedImage {
            Image(uiImage: img).resizable().scaledToFill().frame(height: 240).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.horizontal)
        }
    }

    private var durationSummaryBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: hasMinimumClipDuration ? "checkmark.circle.fill" : "clock.fill")
                .foregroundStyle(hasMinimumClipDuration ? Color.green : Color.orange)
                .font(.system(size: 16))
            Text(durationSummaryText)
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundStyle(hasMinimumClipDuration ? Color.green : Color.orange)
            Spacer()
        }
        .padding(.horizontal, 8)
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
        HStack {
            Image(systemName: "location.fill")
                .font(.system(size: 16))
                .foregroundStyle(primaryColor)
            
            VStack(alignment: .leading, spacing: 2) {
                if locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude {
                    Text("\(lat), \(lon)")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(.primary)
                    Text("Accuracy: ±\(Int(locationManager.horizontalAccuracy ?? 0))m")
                        .font(.system(size: 13, weight: .regular))
                        .foregroundStyle(.secondary)
                } else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted {
                    Text("Location Denied")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.red)
                } else {
                    Text("Requesting location…")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            
            if !locationManager.isAuthorized {
                Button("Enable") {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    locationManager.requestPermissionIfNeeded()
                }
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(primaryColor)
                .clipShape(Capsule())
                .disabled(arePositiveDetailsLocked)
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var landmarkForm: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Landmark Label")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                
                TextField("e.g., Gampel Pavilion", text: $labelText)
                    .font(.system(size: 16, weight: .medium))
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .disabled(arePositiveDetailsLocked)
                
                if let businessLandmarkId {
                    Text("ID: \(businessLandmarkId)")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(.tertiary)
                        .padding(.top, 2)
                }
            }
            .padding(.horizontal)

            VStack(alignment: .leading, spacing: 8) {
                Text("Short Description")
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                
                ZStack(alignment: .bottomTrailing) {
                    TextField("e.g., Front entrance", text: $shortDescription, axis: .vertical)
                        .lineLimit(3...6)
                        .font(.system(size: 16, weight: .medium))
                        .padding(16)
                        .padding(.bottom, 30)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .disabled(arePositiveDetailsLocked)
                    
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        showTextScanner = true
                    } label: {
                        Image(systemName: "text.viewfinder")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 36, height: 36)
                            .background(primaryColor)
                            .clipShape(Circle())
                    }
                    .padding(12)
                    .disabled(arePositiveDetailsLocked)
                    .opacity(arePositiveDetailsLocked ? 0.5 : 1)
                }
            }
            .padding(.horizontal)

            if completedPositiveResult != nil && !isFullSubmissionComplete { positiveAlreadySavedCard }
            
            negativePhotoSection
        }
    }

    private var positiveAlreadySavedCard: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: "checkmark.seal.fill")
                .foregroundStyle(.green)
                .font(.system(size: 24))
            
            VStack(alignment: .leading, spacing: 4) {
                Text("Landmark Saved")
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("Your landmark and positive media were successfully uploaded to the cloud.")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(.secondary)
                    .lineSpacing(2)
            }
            Spacer()
        }
        .padding(20)
        .background(Color.green.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var negativePhotoSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Negative Background")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("Record a >= 10s video panning the area. Do NOT include the landmark.")
                        .font(.system(size: 15, weight: .regular))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Image(systemName: hasRequiredNegativeVideo ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(hasRequiredNegativeVideo ? Color.green : Color.orange)
            }
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                showNegativeCamera = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "camera.fill")
                    Text(capturedNegativeVideo == nil ? "Record Negative" : "Retake Negative")
                }
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(Color(red: 0.11, green: 0.11, blue: 0.16))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .disabled(areNegativePhotosLocked)
            .opacity(areNegativePhotosLocked ? 0.6 : 1)
            
            if let video = capturedNegativeVideo {
                ZStack(alignment: .topTrailing) {
                    UploadFormVideoPlayer(url: video.fileURL)
                        .equatable()
                        .id(video.fileURL.absoluteString)
                        .frame(maxWidth: .infinity)
                        .frame(height: 220)
                        .clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .ignoresSafeArea(.keyboard)
                    
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        video.deleteLocalFile()
                        capturedNegativeVideo = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(.black.opacity(0.6))
                            .clipShape(Circle())
                    }
                    .padding(12)
                    .disabled(areNegativePhotosLocked)
                }
            }
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var uploadButtonRow: some View {
        HStack(spacing: 12) {
            if archivedMedia != nil {
                Button(role: .cancel) {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    saveDraftAndDismiss()
                } label: {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.primary)
                        .frame(width: 60, height: 60)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            
            if hasPositiveMedia || completedPositiveResult != nil {
                Button {
                    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                    startFullSubmission()
                } label: {
                    HStack(spacing: 10) {
                        if isSubmissionRunning {
                            ProgressView().tint(.white)
                            Text(hardNegativeUploadService.isUploading ? "Uploading reference..." : (isStitchingVideos ? "Processing..." : uploadService.status))
                        } else {
                            Image(systemName: isFullSubmissionComplete ? "checkmark.circle.fill" : "arrow.up.circle.fill")
                            Text(isFullSubmissionComplete ? "Complete" : (completedPositiveResult != nil ? "Retry Negative" : "Upload Landmark"))
                        }
                    }
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
                }
                .foregroundStyle(.white)
                .background(canUpload ? primaryColor : Color.gray.opacity(0.3))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .disabled(!canUpload)
            } else if archivedMedia != nil {
                Spacer()
            }
            
            if archivedMedia == nil && !uploadService.isUploading && !isFullSubmissionComplete {
                Button(role: .destructive) {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    showDiscardAlert = true
                } label: {
                    Image(systemName: "trash.fill")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.red)
                        .frame(width: 60, height: 60)
                        .background(Color.red.opacity(0.1))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .padding(.horizontal)
    }

    private func startFullSubmission() {
        if completedPositiveResult == nil {
            if vm.activeLandmarksCount >= vm.maxLandmarksCapacity {
                limitAlertTitle = "Capacity Reached"
                limitAlertMessage = "You have reached your tier's maximum active landmarks. Delete an old landmark or upgrade your plan."
                showLimitAlert = true
                return
            }
            if vm.tokenBalance <= 0 {
                limitAlertTitle = "Out of Swap Tokens"
                limitAlertMessage = "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                showLimitAlert = true
                return
            }
        }
        
        if !NetworkMonitor.shared.isConnected {
            if let archive = archivedMedia { OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, shortDesc: shortDescription, userDesc: nil) }
            else { saveToArchiveFromForm() }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 300_000_000)
                showAutoQueueAlert = true
            }
            return
        }
        
        Task {
            guard !isSubmissionRunning, !isFullSubmissionComplete else { return }
            if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
            guard let generatedLandmarkId = businessLandmarkId else { return }
            do {
                let positiveResult: PositiveSubmissionResult
                await vm.fetchUserDetails(); let idToken = await vm.fetchIdToken()
                
                if let existingResult = completedPositiveResult {
                    positiveResult = existingResult
                } else {
                    let trimmedLabel = labelText.trimmingCharacters(in: .whitespacesAndNewlines)
                    let trimmedShortDescription = shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
                    
                    guard !trimmedLabel.isEmpty, !trimmedShortDescription.isEmpty else { return }
                    
                    positiveResult = try await uploadService.upload(userEmail: vm.userEmail, idToken: idToken, label: trimmedLabel, landmarkId: generatedLandmarkId, landmarkLabel: trimmedLabel, shortDescription: trimmedShortDescription, userDescription: nil, latitude: extractedLatitude ?? locationManager.latitude, longitude: extractedLongitude ?? locationManager.longitude, horizontalAccuracy: locationManager.horizontalAccuracy, videoURLs: pickedVideoURLs, image: pickedImage)
                    
                    completedPositiveResult = positiveResult
                    statusText = "Landmark media saved. Uploading reference video…"
                    
                    await MainActor.run {
                        vm.tokenBalance -= 1
                        vm.activeLandmarksCount += 1
                    }
                }
                
                let finalLandmarkId = positiveResult.landmarkId ?? generatedLandmarkId
                
                if let negativeVideo = capturedNegativeVideo {
                    _ = try await hardNegativeUploadService.upload(landmarkId: finalLandmarkId, idToken: idToken, video: negativeVideo)
                }
                
                completedLandmarkId = finalLandmarkId
                isFullSubmissionComplete = true
                statusText = "Landmark and reference video uploaded successfully."
                showCompletionPopup = true
                
                if let media = archivedMedia { OfflineMediaManager.shared.deleteArchive(media: media) }
                
            } catch {
                print("Full landmark submission failed:", error.localizedDescription)
            }
        }
    }

    @ViewBuilder private var positiveUploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 16) {
                    if uploadService.isUploading { ProgressView().padding(.top, 2) }
                    else { Image(systemName: uploadService.stage.systemImage).font(.system(size: 24)).foregroundStyle(uploadService.stage == .complete ? .green : (uploadService.stage == .failed ? .red : primaryColor)) }
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text(uploadService.status).font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                        Text(uploadService.detail).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if uploadService.isUploading {
                    ProgressView(value: uploadService.progress, total: 1).tint(primaryColor)
                }
                if uploadService.stage == .failed {
                    Button { uploadService.reset() } label: { Label("Dismiss", systemImage: "xmark.circle").font(.system(size: 14, weight: .bold)) }
                }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal)
        }
    }

    @ViewBuilder private var negativeUploadStatusCard: some View {
        if hardNegativeUploadService.status != "Idle" {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 16) {
                    if hardNegativeUploadService.isUploading { ProgressView().padding(.top, 2) }
                    else { Image(systemName: "video.fill").font(.system(size: 24)).foregroundStyle(primaryColor) }
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Reference Video").font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                        Text(hardNegativeUploadService.status).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if hardNegativeUploadService.isUploading { ProgressView(value: hardNegativeUploadService.progress, total: 1).tint(primaryColor) }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal)
        }
    }

    @ViewBuilder private var overallCompletionCard: some View {
        if isFullSubmissionComplete {
            HStack(spacing: 16) {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                    .font(.system(size: 24))
                Text("Submission Complete")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Spacer()
            }
            .padding(20)
            .background(Color.green.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal)
        }
    }

    private func loadDuration(for url: URL) async {
        let asset = AVURLAsset(url: url)
        do { let duration = try await asset.load(.duration); let seconds = CMTimeGetSeconds(duration); guard seconds.isFinite else { return }; await MainActor.run { clipDurations[url] = seconds } } catch { print("Could not read duration for \(url.lastPathComponent): \(error)") }
    }

    private func processAndStitchPendingMedia() {
        guard !pendingArchiveURLs.isEmpty else { return }
        isFormVisible = false
        isStitchingVideos = true
        let urlsToStitch = pendingArchiveURLs
        Task.detached {
            if urlsToStitch.count > 1 {
                if let stitchedURL = await self.stitchVideos(urls: urlsToStitch) {
                    await MainActor.run { self.deleteAllTemporaryVideos(self.pickedVideoURLs); self.deleteAllTemporaryVideos(self.pendingArchiveURLs); self.pickedVideoURLs = [stitchedURL]; self.statusText = "Selected combined video." }
                } else {
                    await MainActor.run { self.pickedVideoURLs = urlsToStitch; self.statusText = "Selected \(self.pickedVideoURLs.count) videos." }
                }
            } else {
                await MainActor.run { self.pickedVideoURLs = urlsToStitch; self.statusText = "Selected video." }
            }
            let finalURLs = await MainActor.run { self.pickedVideoURLs }
            for url in finalURLs { await self.loadDuration(for: url) }
            await MainActor.run {
                if self.extractedLatitude == nil { self.extractedLatitude = self.locationManager.latitude }
                if self.extractedLongitude == nil { self.extractedLongitude = self.locationManager.longitude }
                if self.businessLandmarkId == nil { self.businessLandmarkId = self.makeBusinessLandmarkId() }
                self.uploadService.reset(); self.pendingArchiveURLs = []; self.isStitchingVideos = false; self.isFormVisible = true
            }
        }
    }

    private func stitchVideos(urls: [URL]) async -> URL? {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        var currentTime = CMTime.zero
        var renderSize = CGSize(width: 1080, height: 1920)
        for url in urls {
            let asset = AVURLAsset(url: url)
            do {
                guard let assetVideoTrack = try await asset.loadTracks(withMediaType: .video).first else { continue }
                let duration = try await asset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
                let transform = try await assetVideoTrack.load(.preferredTransform)
                videoTrack.preferredTransform = transform
                let naturalSize = try await assetVideoTrack.load(.naturalSize)
                if transform.a == 0 && transform.d == 0 && (transform.b == 1.0 || transform.b == -1.0) { renderSize = CGSize(width: naturalSize.height, height: naturalSize.width)
                } else { renderSize = naturalSize }
                currentTime = CMTimeAdd(currentTime, duration)
            } catch { return nil }
        }
        composition.naturalSize = renderSize
        let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_stitched.mov")
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1920x1080) else { return nil }
        exporter.outputURL = outputURL; exporter.outputFileType = .mov; exporter.shouldOptimizeForNetworkUse = true
        await exporter.export()
        return exporter.status == .completed ? outputURL : nil
    }

    private func saveToArchiveFromForm() {
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0
        let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        Task.detached {
            let firstURL = await MainActor.run { pickedVideoURLs.first }; let img = await MainActor.run { pickedImage }; let id = await MainActor.run { businessLandmarkId }; let lbl = await MainActor.run { labelText }; let desc = await MainActor.run { shortDescription }; let negURL = await MainActor.run { capturedNegativeVideo?.fileURL }
            if let firstURL = firstURL { _ = await OfflineMediaManager.shared.archiveVideo(tempURL: firstURL, lat: lat, lon: lon, landmarkId: id, label: lbl, shortDesc: desc, userDesc: nil, negativeVideoURL: negURL, isTier2: false)
            } else if let img = img { _ = await OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, landmarkId: id, label: lbl, shortDesc: desc, userDesc: nil, negativeVideoURL: negURL, isTier2: false)
            } else { return }
            await MainActor.run { clearScreen(); statusText = "Landmark safely queued in Outbox for upload." }
        }
    }

    private func saveDraftAndDismiss() {
        if let archive = archivedMedia { OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, shortDesc: shortDescription, userDesc: nil) }
        dismiss()
    }

    private func discardPendingMedia() { deleteAllTemporaryVideos(pendingArchiveURLs); pendingArchiveURLs = [] }

    private func clearScreen() {
        deleteAllTemporaryVideos(pickedVideoURLs); capturedNegativeVideo?.deleteLocalFile(); pickedVideoURLs = []; clipDurations = [:]
        pickedImage = nil; extractedLatitude = nil; extractedLongitude = nil; labelText = ""; shortDescription = ""
        businessLandmarkId = nil; capturedNegativeVideo = nil; completedPositiveResult = nil; completedLandmarkId = nil
        isFullSubmissionComplete = false; isFormVisible = false; statusText = "No landmark media selected."
        uploadService.reset(); hardNegativeUploadService.reset()
    }
    
    private func resetForAnotherLandmark() { clearScreen() }
    private func openAdditionalMediaUpload() { guard let id = completedLandmarkId else { return }; resetForAnotherLandmark(); onAddMoreMedia(id) }

    private func makeBusinessLandmarkId() -> String { "landmark_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))" }
    
    private func removeClip(url: URL) {
        guard let index = pickedVideoURLs.firstIndex(of: url) else { return }
        deleteTemporaryVideoIfNeeded(url); clipDurations.removeValue(forKey: url); pickedVideoURLs.remove(at: index)
        statusText = pickedVideoURLs.isEmpty ? "No media selected." : "Removed clip. \(pickedVideoURLs.count) remaining."
    }
    
    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) { guard let url = videoURL, archivedMedia == nil else { return }; if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) { try? FileManager.default.removeItem(at: url) } }
    private func deleteAllTemporaryVideos(_ urls: [URL]) { urls.forEach { deleteTemporaryVideoIfNeeded($0) } }
}

struct UploadFormVideoPlayer: View, Equatable {
    let url: URL
    @State private var player: AVPlayer?

    static func == (lhs: UploadFormVideoPlayer, rhs: UploadFormVideoPlayer) -> Bool { return lhs.url == rhs.url }

    var body: some View {
        Group {
            if let player = player { VideoPlayer(player: player) }
            else { ZStack { Color(uiColor: .systemBackground); ProgressView().tint(.primary) } }
        }
        .onAppear {
            if player == nil {
                DispatchQueue.global(qos: .userInitiated).async {
                    let newPlayer = AVPlayer(url: url)
                    DispatchQueue.main.async { self.player = newPlayer }
                }
            }
        }
        .onDisappear { player?.pause(); player?.replaceCurrentItem(with: nil) }
    }
}
