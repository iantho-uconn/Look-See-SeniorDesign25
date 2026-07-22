import SwiftUI
import CoreLocation
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
    @State private var showVideoPicker = false
    @State private var extractedLatitude: Double? = nil
    @State private var extractedLongitude: Double? = nil
    @State private var statusText = "No media selected."
    @State private var showArchivePrompt = false
    @State private var showDiscardAlert = false
    @State private var pendingArchiveURL: URL?
    @State private var pendingArchiveLocation: CLLocationCoordinate2D?
    @State private var showAutoQueueAlert = false
    @State private var capturedNegativeVideo: CapturedNegativeVideo? = nil
    @State private var showNegativeCamera = false

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

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    private var hasMedia: Bool { pickedVideoURL != nil }
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
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    if archivedMedia == nil {
                        instructionCard
                        captureButton
                    }
                    if let url = pickedVideoURL {
                        UploadFormVideoPlayer(url: url)
                            .equatable()
                            .id(url.absoluteString)
                            .frame(maxWidth: .infinity)
                            .frame(height: 240)
                            .clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .shadow(color: .black.opacity(0.1), radius: 10, x: 0, y: 4)
                            .padding(.horizontal)
                            .ignoresSafeArea(.keyboard)
                    }
                    
                    if !statusText.isEmpty {
                        Text(statusText)
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }
                    
                    locationSection
                    nearbyLandmarksSection
                    if selectedLandmark != nil { uploadDetailsSection }
                    Spacer(minLength: 40)
                }
                .padding(.top, 16)
            }
            .defaultScrollAnchor(.top)
            .scrollDismissesKeyboard(.immediately)
            .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }

            if showArchivePrompt { archivePromptOverlay }
        }
        .task {
            if let archive = archivedMedia {
                pickedVideoURL = OfflineMediaManager.shared.getFileURL(for: archive)
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
        .fullScreenCover(isPresented: $showNegativeCamera) {
            NegativeVideoCameraView(onDone: { video in
                capturedNegativeVideo = video
                if !hardNegativeUploadService.isUploading { hardNegativeUploadService.reset() }
            })
        }
        .alert("Invalid Video Length", isPresented: $showVideoDurationAlert) { Button("OK", role: .cancel) { } } message: { Text(videoDurationAlertMessage) }
        .alert("Discard this upload?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { clearScreen() }
            Button("Cancel", role: .cancel) { }
        } message: { Text("This will remove the media and clear the form.") }
        .alert("Connection Offline", isPresented: $showAutoQueueAlert) {
            Button("OK", role: .cancel) { }
        } message: { Text("You currently have no internet connection. This media has been securely added to your Upload Queue and will automatically sync when service returns!") }
    }

    var archivePromptOverlay: some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 24) {
                ZStack {
                    Circle().fill(primaryColor.opacity(0.15)).frame(width: 70, height: 70)
                    Image(systemName: "folder.badge.plus").font(.system(size: 32)).foregroundStyle(primaryColor)
                }
                
                VStack(spacing: 8) {
                    Text("Media Captured")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Text("What would you like to do with this media? You can upload it now or save it to your offline archive.")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color.white.opacity(0.6))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                
                VStack(spacing: 12) {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        withAnimation(.spring()) { showArchivePrompt = false }
                        applyPendingMedia()
                    } label: {
                        Text("Upload Now")
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
                        saveToArchiveFromPrompt()
                    } label: {
                        Text("Save to Offline Archive")
                            .font(.system(size: 17, weight: .semibold, design: .rounded))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color.white.opacity(0.15))
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
            .background(Color(red: 0.11, green: 0.11, blue: 0.16))
            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).stroke(Color.white.opacity(0.1), lineWidth: 0.5))
            .padding(.horizontal, 24)
        }
    }

    private var instructionCard: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: "video.badge.plus")
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(primaryColor)
                .padding(.top, 4)
            
            VStack(alignment: .leading, spacing: 6) {
                Text("Tier 2 Upload")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("Record a short video of a nearby landmark. Choose a valid landmark from the list below to help improve model recognition.")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineSpacing(2)
            }
            Spacer()
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
        .padding(.horizontal)
    }

    private var captureButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            showVideoPicker = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "video.fill")
                Text("Record Media")
            }
            .font(.system(size: 17, weight: .bold, design: .rounded))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
        }
        .foregroundStyle(.white)
        .background(primaryColor)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .disabled(uploadService.isUploading || archivedMedia != nil)
        .opacity(uploadService.isUploading || archivedMedia != nil ? 0.6 : 1)
        .padding(.horizontal)
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
                        .font(.system(size: 12, weight: .medium))
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
            
            VStack(alignment: .trailing, spacing: 8) {
                if !locationManager.isAuthorized {
                    Button("Enable") {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        locationManager.requestPermissionIfNeeded()
                    }
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(primaryColor)
                    .clipShape(Capsule())
                }
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    Task { await refreshNearbyIfPossible(force: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(primaryColor)
                        .padding(8)
                        .background(primaryColor.opacity(0.1))
                        .clipShape(Circle())
                }
                .disabled(!locationManager.isAuthorized || uploadService.isUploading)
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
        .padding(.horizontal)
    }

    private var nearbyLandmarksSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Nearby Landmarks")
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .padding(.horizontal, 20)
            
            nearbyLandmarkResults
            
            if let selectedLandmark { selectedLandmarkCard(selectedLandmark) }
        }
        .padding(.top, 16)
    }

    @ViewBuilder private var nearbyLandmarkResults: some View {
        if nearbyService.items.isEmpty && nearbyService.isLoading {
            ProgressView("Scanning area…").padding(.horizontal, 20)
        } else if let errorMessage = nearbyService.errorMessage {
            Text("Error: \(errorMessage)").font(.system(size: 14, weight: .medium)).foregroundStyle(.red).padding(.horizontal, 20)
        } else if !hasUsableLocation {
            Text("Nearby landmarks will appear once location is available.").font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary).padding(.horizontal, 20)
        } else if nearbyService.items.isEmpty {
            Text("No landmarks found within \(Int(radiusMeters)) meters.").font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary).padding(.horizontal, 20)
        } else {
            VStack(spacing: 12) {
                ForEach(nearbyService.items) { landmark in nearbyLandmarkButton(landmark) }
            }.padding(.horizontal)
        }
    }

    private func nearbyLandmarkButton(_ landmark: NearbyLandmark) -> some View {
        let isSelected = selectedLandmark?.landmarkId == landmark.landmarkId
        return Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            selectedLandmark = landmark
            if initialLandmarkId != nil && !hasAppliedInitialLandmark {
                hasAppliedInitialLandmark = true
                onInitialLandmarkConsumed()
            }
            uploadService.reset()
        } label: {
            HStack(alignment: .top, spacing: 16) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? primaryColor : Color(uiColor: .tertiaryLabel))
                    .padding(.top, 2)
                
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(landmark.label)
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(.primary)
                        Spacer()
                        Text("\(Int(landmark.distanceMeters))m")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    Text(landmark.shortDescription)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
            }
            .padding(16)
            .background(isSelected ? primaryColor.opacity(0.1) : Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(isSelected ? primaryColor : Color.clear, lineWidth: 2))
            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
        }
        .buttonStyle(.plain)
        .disabled(uploadService.isUploading)
    }

    private func selectedLandmarkCard(_ landmark: NearbyLandmark) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Selected Target")
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .foregroundStyle(.secondary)
                .textCase(.uppercase)
            Text(landmark.label)
                .font(.system(size: 18, weight: .bold, design: .rounded))
            Text("\(Int(landmark.distanceMeters)) meters away")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(primaryColor)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var uploadDetailsSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            negativePhotoSection
            uploadButtonRow
            uploadStatusCard
            negativeUploadStatusCard
        }
    }

    private var negativePhotoSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Negative Background")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                    Text("Optional: Add a >= 10s pan of the surrounding area to improve model recognition.")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Image(systemName: capturedNegativeVideo != nil ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(capturedNegativeVideo != nil ? Color.green : Color.orange)
            }
            
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                showNegativeCamera = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "camera.fill")
                    Text(capturedNegativeVideo == nil ? "Record Negative" : "Re-record Negative")
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
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
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
                            .overlay(Circle().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
                    }
                    .padding(12)
                    .disabled(areNegativePhotosLocked)
                }
            }
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
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
                        .shadow(color: .black.opacity(0.05), radius: 8, x: 0, y: 2)
                }
            } else if hasMedia && !uploadService.isUploading {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    saveToArchiveFromForm()
                } label: {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 60, height: 60)
                        .background(primaryColor)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: primaryColor.opacity(0.3), radius: 8, x: 0, y: 4)
                }
            }
            
            Button {
                UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                startUpload()
            } label: {
                HStack(spacing: 10) {
                    if uploadService.isUploading || hardNegativeUploadService.isUploading {
                        ProgressView().tint(.white)
                        Text("Uploading...").fontWeight(.semibold)
                    } else {
                        Image(systemName: "arrow.up.circle.fill")
                        Text("Upload Media").fontWeight(.semibold)
                    }
                }
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
            }
            .foregroundStyle(.white)
            .background(canSubmitUpload ? primaryColor : Color.gray.opacity(0.3))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: canSubmitUpload ? primaryColor.opacity(0.3) : .clear, radius: 10, x: 0, y: 5)
            .disabled(!canSubmitUpload)
            
            if archivedMedia == nil && hasMedia && !uploadService.isUploading {
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

    @ViewBuilder private var uploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 16) {
                    if uploadService.isUploading { ProgressView().padding(.top, 2) }
                    else { Image(systemName: uploadService.stage.systemImage).font(.system(size: 24)).foregroundStyle(uploadService.stage == .complete ? .green : (uploadService.stage == .failed ? .red : primaryColor)) }
                    
                    VStack(alignment: .leading, spacing: 4) {
                        Text(uploadService.status).font(.system(size: 16, weight: .bold, design: .rounded))
                        Text(uploadService.detail).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
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
                        Text("Reference Video").font(.system(size: 16, weight: .bold, design: .rounded))
                        Text(hardNegativeUploadService.status).font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if hardNegativeUploadService.isUploading { ProgressView(value: hardNegativeUploadService.progress, total: 1).tint(primaryColor) }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
            .padding(.horizontal)
        }
    }

    private var videoPicker: some View {
        VideoPicker(useCamera: true, onPicked: { url, location in
            pendingArchiveURL = url; pendingArchiveLocation = location
            withAnimation(.spring()) { showArchivePrompt = true }
        }, onInvalidDuration: { message in showVideoDurationAlert = true; videoDurationAlertMessage = message })
    }
    
    private func applyPendingMedia() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        if let url = pendingArchiveURL { pickedVideoURL = url; statusText = "Selected video." }
        extractedLatitude = pendingArchiveLocation?.latitude ?? locationManager.latitude; extractedLongitude = pendingArchiveLocation?.longitude ?? locationManager.longitude
        uploadService.reset(); pendingArchiveURL = nil; pendingArchiveLocation = nil
    }
    
    private func saveToArchiveFromPrompt() {
        let lat = pendingArchiveLocation?.latitude ?? locationManager.latitude ?? 0.0
        let lon = pendingArchiveLocation?.longitude ?? locationManager.longitude ?? 0.0
        let id = selectedLandmark?.landmarkId
        let label = selectedLandmark?.label ?? "Tier 2 Media"
        let desc = selectedLandmark?.shortDescription ?? ""
        let negURL = capturedNegativeVideo?.fileURL
        Task.detached {
            let urlToSave = await MainActor.run { pendingArchiveURL }
            if let url = urlToSave { _ = await OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon, landmarkId: id, label: label, shortDesc: desc, userDesc: nil, negativeVideoURL: negURL, isTier2: true) }
            await MainActor.run { discardPendingMedia(); statusText = "Media securely saved to Offline Archive." }
        }
    }
    
    private func saveToArchiveFromForm() {
        guard let selectedLandmark, let url = pickedVideoURL else { return }
        let lat = extractedLatitude ?? locationManager.latitude ?? 0.0
        let lon = extractedLongitude ?? locationManager.longitude ?? 0.0
        let lId = selectedLandmark.landmarkId
        let lLabel = selectedLandmark.label
        let sDesc = selectedLandmark.shortDescription
        let negURL = capturedNegativeVideo?.fileURL
        Task.detached {
            _ = await OfflineMediaManager.shared.archiveVideo(tempURL: url, lat: lat, lon: lon, landmarkId: lId, label: lLabel, shortDesc: sDesc, userDesc: nil, negativeVideoURL: negURL, isTier2: true)
            await MainActor.run { clearScreen(); statusText = "Media securely saved to Upload Queue." }
        }
    }
    
    private func saveDraftAndDismiss() { dismiss() }
    private func discardPendingMedia() {
        if let url = pendingArchiveURL { deleteTemporaryVideoIfNeeded(url) }
        pendingArchiveURL = nil; pendingArchiveLocation = nil; pickedVideoURL = nil; statusText = "No media selected."
    }
    
    private func clearScreen() {
        deleteTemporaryVideoIfNeeded(pickedVideoURL)
        capturedNegativeVideo?.deleteLocalFile()
        pickedVideoURL = nil; extractedLatitude = nil; extractedLongitude = nil; selectedLandmark = nil; capturedNegativeVideo = nil
        if initialLandmarkId != nil { hasAppliedInitialLandmark = false; Task { await refreshNearbyIfPossible(force: true) } }
        statusText = "No media selected."; uploadService.reset(); hardNegativeUploadService.reset()
    }

    private func startUpload() {
        guard let selectedLandmark, let url = pickedVideoURL else { return }
        if !NetworkMonitor.shared.isConnected { saveToArchiveFromForm(); showAutoQueueAlert = true; return }
        Task {
            await vm.fetchUserEmail(); let idToken = await vm.fetchIdToken()
            do {
                _ = try await uploadService.upload(userEmail: vm.userEmail, idToken: idToken, label: selectedLandmark.label, landmarkId: selectedLandmark.landmarkId, landmarkLabel: selectedLandmark.label, shortDescription: selectedLandmark.shortDescription, userDescription: nil, latitude: extractedLatitude ?? locationManager.latitude, longitude: extractedLongitude ?? locationManager.longitude, horizontalAccuracy: locationManager.horizontalAccuracy, videoURLs: [url], image: nil)
                if let negativeVideo = capturedNegativeVideo { _ = try await hardNegativeUploadService.upload(landmarkId: selectedLandmark.landmarkId, idToken: idToken, video: negativeVideo) }
                clearScreen(); statusText = "Media uploaded successfully."
            } catch { print("Tier-2 upload failed:", error.localizedDescription) }
        }
    }

    private func refreshNearbyIfPossible(force: Bool = false) async {
        guard locationManager.isAuthorized, let lat = locationManager.latitude, let lon = locationManager.longitude, let acc = locationManager.horizontalAccuracy, acc > 0, acc <= maxAllowedAccuracy else { return }
        await nearbyService.fetchNearby(latitude: lat, longitude: lon, radiusMeters: radiusMeters)
        if !hasAppliedInitialLandmark, let initId = initialLandmarkId, let match = nearbyService.items.first(where: { $0.landmarkId == initId }) {
            selectedLandmark = match; deleteTemporaryVideoIfNeeded(pickedVideoURL); pickedVideoURL = nil; statusText = "No media selected."; uploadService.reset(); hasAppliedInitialLandmark = true; onInitialLandmarkConsumed()
        }
    }

    private func deleteTemporaryVideoIfNeeded(_ videoURL: URL?) {
        guard let url = videoURL else { return }
        if url.standardizedFileURL.path.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path) { try? FileManager.default.removeItem(at: url) }
    }
}
