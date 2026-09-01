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

    @Binding var isNavVisible: Bool
    var isActive: Bool

    private let onAddMoreMedia: (String) -> Void
    var archivedMedia: ArchivedMedia?
    
    var existingLandmarkId: String?
    var existingLabel: String?
    var existingDescription: String?
    var existingSecondsNeeded: Double?

    init(
        isNavVisible: Binding<Bool> = .constant(true),
        isActive: Bool = true,
        archivedMedia: ArchivedMedia? = nil,
        existingLandmarkId: String? = nil,
        existingLabel: String? = nil,
        existingDescription: String? = nil,
        existingSecondsNeeded: Double? = nil,
        onAddMoreMedia: @escaping (String) -> Void = { _ in }
    ) {
        self._isNavVisible = isNavVisible
        self.isActive = isActive
        self.archivedMedia = archivedMedia
        self.existingLandmarkId = existingLandmarkId
        self.existingLabel = existingLabel
        self.existingDescription = existingDescription
        self.existingSecondsNeeded = existingSecondsNeeded
        self.onAddMoreMedia = onAddMoreMedia
        
        self._businessLandmarkId = State(initialValue: existingLandmarkId)
        self._labelText = State(initialValue: existingLabel ?? "")
        self._shortDescription = State(initialValue: existingDescription ?? "")
    }

    @State private var labelText = ""
    @State private var businessLandmarkId: String?
    @State private var shortDescription = ""
    @State private var showTextScanner = false
    @State private var pickedVideoURLs: [URL] = []
    @State private var pickedImage: UIImage?
    @State private var clipDurations: [URL: Double] = [:]
    
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = String(localized: "No landmark media selected.")
    @State private var showDiscardAlert = false
    
    @State private var showLimitAlert = false
    @State private var limitAlertTitle = ""
    @State private var limitAlertMessage = ""
    
    @State private var pendingArchiveURLs: [URL] = []
    @State private var isStitchingVideos = false
    @State private var startrecording = false
    
    @State private var showBackgroundUploadAlert = false
    @State private var capturedNegativeVideo: CapturedNegativeVideo? = nil
    @State private var showNegativeCamera = false
    
    @State private var completedPositiveResult: PositiveSubmissionResult?
    @State private var completedLandmarkId: String?
    @State private var isFullSubmissionComplete = false
    
    @State private var isFormVisible = false

    @StateObject private var uploadService = UploadService()
    @StateObject private var hardNegativeUploadService = HardNegativeUploadService()
    @StateObject private var locationManager = LocationManager()
    
    @FocusState private var IsKeyboard: Bool

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    private var uiTargetDuration: Int {
        if let needed = existingSecondsNeeded {
            return Int(ceil(needed))
        } else if existingLandmarkId != nil {
            return 1
        }
        return 30
    }
    
    private var negativeTargetDuration: Int {
        if existingLandmarkId != nil { return 1 }
        return 10
    }
    
    private var minimumCombinedVideoDuration: Double {
        return 1.0
    }

    private var hasPositiveMedia: Bool { !pickedVideoURLs.isEmpty || pickedImage != nil }
    private var hasLabel: Bool { !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasRequiredShortDescription: Bool { !shortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    
    private var hasRequiredNegativeVideo: Bool {
        if existingLandmarkId != nil { return true }
        return capturedNegativeVideo != nil
    }
    
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
            if !hasPositiveMedia && !isFormVisible {
                PositiveVideoCameraView(
                    isActive: isActive,
                    isNavVisible: $isNavVisible,
                    uiTargetDuration: 90,
                    minTotalTimeLimit: uiTargetDuration
                ) { urls in
                    pendingArchiveURLs = urls
                    processAndStitchPendingMedia()
                } onCancel: {
                    dismiss()
                }
                .ignoresSafeArea()
                .transition(.opacity)
            } else {
                Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 20) {
                        positiveMediaPreview
                        
                        if !statusText.isEmpty {
                            HStack {
                                Text(statusText)
                                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                                    .foregroundStyle(.secondary)
                                
                                Spacer()
                                
                                if statusText.contains("Outbox") || statusText.contains("queued") {
                                    Button {
                                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                        if let archive = archivedMedia {
                                            OfflineMediaManager.shared.prioritizeAndRetry(media: archive)
                                            AutoUploadManager.shared.forceRetry()
                                        }
                                    } label: {
                                        Text("Retry")
                                            .font(.system(size: 13, weight: .bold))
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 6)
                                            .background(Color.blue.opacity(0.15))
                                            .foregroundStyle(Color.blue)
                                            .clipShape(Capsule())
                                    }
                                }
                            }
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
                    }
                    .padding(.top, 16)
                }
                .transition(.opacity)
                .scrollDismissesKeyboard(.immediately)
                .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }
                .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 90) }
            }
            
            if isStitchingVideos { processingOverlay }
        }
        .animation(.easeInOut(duration: 0.3), value: isFormVisible)
        .task {
            if let archive = archivedMedia {
                withAnimation { isFormVisible = true }
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
                        self.statusText = String(localized: "Loaded archived media.")
                    }

                    for url in finalURLs { await self.loadDuration(for: url) }
                }
            }
        }
        .sheet(isPresented: $showTextScanner) { ScannerSheet(scannedText: $shortDescription) }
        .fullScreenCover(isPresented: $showNegativeCamera) {
            NegativeVideoCameraView(
                uiTargetDuration: 30,
                minTotalTimeLimit: negativeTargetDuration
            ) { video in
                capturedNegativeVideo = video
                if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() }
            }
        }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) { Button("Discard", role: .destructive) { clearScreen() }; Button("Cancel", role: .cancel) { } } message: { Text("This will remove the media and clear the form.") }
        
        .alert("Upload Queued!", isPresented: $showBackgroundUploadAlert) {
            Button("Done", role: .cancel) {
                clearScreen()
                dismiss()
            }
            Button("Record Another Landmark") {
                resetForAnotherLandmark()
            }
        } message: {
            Text("Your landmark has been securely queued! It will upload in the background. Feel free to keep using the app, but please make sure to leave it open until the upload finishes.")
        }
        
        .alert(limitAlertTitle, isPresented: $showLimitAlert) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(limitAlertMessage)
        }
        .onChange(of: startrecording) {
            updateIdleTimer(recording: startrecording)
        }
    }
    
    var processingOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 20) {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
                Text("Processing Video")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text("Please wait a moment.")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
            }
            .padding(32)
            .background(Color(red: 0.12, green: 0.12, blue: 0.16))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.3), radius: 15, x: 0, y: 8)
        }
        .zIndex(100)
    }

    @ViewBuilder private var positiveMediaPreview: some View {
        if !pickedVideoURLs.isEmpty {
            VStack(spacing: 16) {
                durationSummaryBanner
                
                ForEach(pickedVideoURLs.indices, id: \.self) { index in
                    let url = pickedVideoURLs[index]
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
                                if pickedVideoURLs.count == 1 {
                                    clearScreen()
                                } else {
                                    removeClip(url: url)
                                }
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
            }
            .padding(.horizontal)
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
        return String(localized: "\(String(format: "%.1f", total))s total — ready to upload")
    }

    private func clipLabel(index: Int, url: URL) -> String {
        if let duration = clipDurations[url] { return String(localized: "Clip \(index + 1) · \(String(format: "%.1f", duration))s") }
        return String(localized: "Clip \(index + 1) · loading…")
    }
    
    private func updateIdleTimer(recording: Bool) {
        let shouldDisableAutoLock = startrecording
        if shouldDisableAutoLock {
            if !UIApplication.shared.isIdleTimerDisabled {
                UIApplication.shared.isIdleTimerDisabled = true
            }
        } else {
            if UIApplication.shared.isIdleTimerDisabled {
                UIApplication.shared.isIdleTimerDisabled = false
            }
        }
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
                    .focused($IsKeyboard)
                    .font(.system(size: 16, weight: .medium))
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .disabled(arePositiveDetailsLocked || existingLandmarkId != nil)
                
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
                        .focused($IsKeyboard)
                        .lineLimit(3...6)
                        .font(.system(size: 16, weight: .medium))
                        .padding(16)
                        .padding(.bottom, 30)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .disabled(arePositiveDetailsLocked || existingLandmarkId != nil)
                    
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
                    .disabled(arePositiveDetailsLocked || existingLandmarkId != nil)
                    .opacity((arePositiveDetailsLocked || existingLandmarkId != nil) ? 0.5 : 1)
                }
            }
            .padding(.horizontal)

            if completedPositiveResult != nil && !isFullSubmissionComplete { positiveAlreadySavedCard }
            
            if existingLandmarkId == nil {
                negativePhotoSection
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            IsKeyboard = false
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
                    Text("Record a >= \(negativeTargetDuration)s video panning the area. Do NOT include the landmark.")
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
                        
                        // 🚀 THE FIX: Only delete it if it is a fresh camera recording, not a saved draft.
                        if archivedMedia == nil {
                            video.deleteLocalFile()
                        }
                        
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
                        Image(systemName: "arrow.up.circle.fill")
                        Text(archivedMedia != nil ? "Upload Draft" : (existingLandmarkId != nil ? "Upload Additional Media" : "Upload Landmark"))
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
            
            if archivedMedia == nil {
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
            if !vm.hasActiveSubscription {
                limitAlertTitle = String(localized: "Subscription Required")
                limitAlertMessage = String(localized: "You need an active subscription or Free Trial to upload landmarks.")
                showLimitAlert = true
                return
            }
            if existingLandmarkId == nil && vm.tokenBalance <= 0 {
                limitAlertTitle = String(localized: "Out of Tokens")
                limitAlertMessage = String(localized: "You need 1 token to upload a new landmark. Purchase a token pack in Settings.")
                showLimitAlert = true
                return
            }
        }
        
        if let archive = archivedMedia {
            OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, shortDesc: shortDescription, userDesc: nil)
        } else {
            if businessLandmarkId == nil { businessLandmarkId = makeBusinessLandmarkId() }
            saveToArchiveFromForm()
            
            if existingLandmarkId == nil {
                Task { @MainActor in
                    vm.tokenBalance -= 1
                    vm.activeLandmarksCount += 1
                }
            }
        }
        
        AutoUploadManager.shared.forceRetry()
        showBackgroundUploadAlert = true
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
        if hardNegativeUploadService.status != String(localized: "Idle") {
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
        isStitchingVideos = true
        let urlsToStitch = pendingArchiveURLs
        Task.detached {
            if urlsToStitch.count > 1 {
                do {
                    let stitchedURL = try await VideoMerger.mergeAndValidate(clipURLs: urlsToStitch, minimumDuration: 1.0)
                    await MainActor.run {
                        self.deleteAllTemporaryVideos(self.pickedVideoURLs)
                        self.deleteAllTemporaryVideos(self.pendingArchiveURLs)
                        self.pickedVideoURLs = [stitchedURL]
                        self.statusText = String(localized: "Selected combined video.")
                    }
                } catch {
                    await MainActor.run {
                        self.pickedVideoURLs = urlsToStitch
                        self.statusText = String(localized: "Selected \(self.pickedVideoURLs.count) videos.")
                    }
                }
            } else {
                await MainActor.run { self.pickedVideoURLs = urlsToStitch; self.statusText = String(localized: "Selected video.") }
            }
            let finalURLs = await MainActor.run { self.pickedVideoURLs }
            for url in finalURLs { await self.loadDuration(for: url) }
            await MainActor.run {
                if self.extractedLatitude == nil { self.extractedLatitude = self.locationManager.latitude }
                if self.extractedLongitude == nil { self.extractedLongitude = self.locationManager.longitude }
                if self.businessLandmarkId == nil { self.businessLandmarkId = self.makeBusinessLandmarkId() }
                self.uploadService.reset(); self.pendingArchiveURLs = []; self.isStitchingVideos = false
                
                withAnimation(.easeInOut(duration: 0.4)) {
                    self.isFormVisible = true
                }
            }
        }
    }

    private func saveToArchiveFromForm() {
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0
        let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        let idToSave = businessLandmarkId ?? makeBusinessLandmarkId()
        
        Task.detached {
            let firstURL = await MainActor.run { pickedVideoURLs.first }
            let img = await MainActor.run { pickedImage }
            let lbl = await MainActor.run { labelText }
            let desc = await MainActor.run { shortDescription }
            let negURL = await MainActor.run { capturedNegativeVideo?.fileURL }
            
            if let firstURL = firstURL {
                _ = await OfflineMediaManager.shared.archiveVideo(tempURL: firstURL, lat: lat, lon: lon, landmarkId: idToSave, label: lbl, shortDesc: desc, userDesc: nil, negativeVideoURL: negURL, isTier2: false)
            } else if let img = img {
                _ = await OfflineMediaManager.shared.archivePhoto(image: img, lat: lat, lon: lon, landmarkId: idToSave, label: lbl, shortDesc: desc, userDesc: nil, negativeVideoURL: negURL, isTier2: false)
            }
        }
    }

    private func saveDraftAndDismiss() {
        if let archive = archivedMedia { OfflineMediaManager.shared.updateDraft(media: archive, label: labelText, shortDesc: shortDescription, userDesc: nil) }
        dismiss()
    }

    private func discardPendingMedia() { deleteAllTemporaryVideos(pendingArchiveURLs); pendingArchiveURLs = [] }

    // 🚀 THE FIX: Safely handles Draft vs Temp files during cleanup
    private func clearScreen() {
        deleteAllTemporaryVideos(pickedVideoURLs)
        
        // Only delete the negative video if it's a temporary camera file!
        // This prevents the draft from deleting its own permanent negative video from the hard drive.
        deleteTemporaryVideoIfNeeded(capturedNegativeVideo?.fileURL)
        
        pickedVideoURLs = []
        clipDurations = [:]
        pickedImage = nil
        extractedLatitude = nil
        extractedLongitude = nil
        
        if existingLandmarkId == nil {
            businessLandmarkId = nil
            labelText = ""
            shortDescription = ""
        } else {
            businessLandmarkId = existingLandmarkId
            labelText = existingLabel ?? ""
            shortDescription = existingDescription ?? ""
        }
        
        capturedNegativeVideo = nil
        completedPositiveResult = nil
        completedLandmarkId = nil
        isFullSubmissionComplete = false
        withAnimation(.easeInOut(duration: 0.3)) { isFormVisible = false }
        statusText = String(localized: "No landmark media selected.")
        uploadService.reset()
        hardNegativeUploadService.reset()
    }
    
    private func resetForAnotherLandmark() { clearScreen() }

    private func makeBusinessLandmarkId() -> String { "landmark_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8))" }
    
    private func removeClip(url: URL) {
        guard let index = pickedVideoURLs.firstIndex(of: url) else { return }
        deleteTemporaryVideoIfNeeded(url); clipDurations.removeValue(forKey: url); pickedVideoURLs.remove(at: index)
        statusText = pickedVideoURLs.isEmpty ? String(localized: "No media selected.") : String(localized: "Removed clip. \(pickedVideoURLs.count) remaining.")
    }
    
    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) {
        guard let url = videoURL, archivedMedia == nil else { return }
        if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) {
            try? FileManager.default.removeItem(at: url)
        }
    }
    
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
