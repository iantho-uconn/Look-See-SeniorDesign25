//
//  LandmarkRecord.swift
//  LookSeeProto
//

import SwiftUI
import CoreLocation
import UIKit

struct LandmarkRecord: View {
    @EnvironmentObject var vm: AuthViewModel
    

    private let onAddMoreMedia: (String) -> Void

    init(
        onAddMoreMedia: @escaping (String) -> Void = { _ in }
    ) {
        self.onAddMoreMedia = onAddMoreMedia
    }

    // MARK: - Landmark information

    @State private var labelText = ""
    @State private var businessLandmarkId: String?

    @State private var shortDescription = ""
    @State private var userDescription = ""

    // MARK: - Positive media

    // Exactly one positive item is allowed.
    @State private var pickedVideoURL: URL?
    @State private var pickedImage: UIImage?

    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false

    @State private var statusText =
        "No landmark media selected."

    // MARK: - Negative reference photos

    @State private var capturedNegativePhotos:
        [CapturedNegativePhoto] = []

    @State private var showNegativeCamera = false

    private let minimumNegativePhotoCount = 5
    private let maximumNegativePhotoCount = 10

    // MARK: - Upload workflow state

    /*
     Once this is non-nil, the positive landmark submission has
     already succeeded.

     If the negative upload later fails, we retain this result so
     that retrying does not create another landmark or upload the
     positive media again.
     */
    @State private var completedPositiveResult:
        PositiveSubmissionResult?

    @State private var isFullSubmissionComplete = false
    
    @State private var showCompletionPopup = false
    @State private var completedLandmarkId: String?

    // MARK: - Video validation

    @State private var showVideoDurationAlert = false
    @State private var videoDurationAlertMessage = ""

    // MARK: - Services

    @StateObject private var uploadService =
        UploadService()

    @StateObject private var hardNegativeUploadService =
        HardNegativeUploadService()

    @StateObject private var locationManager =
        LocationManager()

    // MARK: - Appearance

    private let primaryColor = Color(
        red: 0.11,
        green: 0.22,
        blue: 0.55
    )

    // MARK: - Validation

    private var hasPositiveMedia: Bool {
        pickedVideoURL != nil || pickedImage != nil
    }

    private var hasLabel: Bool {
        !labelText
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )
            .isEmpty
    }

    private var hasRequiredDescriptions: Bool {
        let trimmedShort = shortDescription
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        let trimmedFrame = userDescription
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        return !trimmedShort.isEmpty &&
               !trimmedFrame.isEmpty
    }

    private var hasRequiredNegativePhotos: Bool {
        capturedNegativePhotos.count >=
            minimumNegativePhotoCount
        &&
        capturedNegativePhotos.count <=
            maximumNegativePhotoCount
    }

    private var isSubmissionRunning: Bool {
        uploadService.isUploading ||
        hardNegativeUploadService.isUploading
    }

    /*
     Before the positive submission succeeds, all landmark fields
     are required.

     After it succeeds, a retry only needs the negative photos.
     */
    private var canUpload: Bool {
        guard !isSubmissionRunning,
              !isFullSubmissionComplete else {
            return false
        }

        if completedPositiveResult != nil {
            return hasRequiredNegativePhotos
        }

        return hasPositiveMedia &&
               hasLabel &&
               hasRequiredDescriptions &&
               hasRequiredNegativePhotos
    }

    /*
     Landmark details must not change after the positive submission
     succeeds, because those details have already been written to
     the backend.

     Negative photos may still be adjusted if their upload fails.
     */
    private var arePositiveDetailsLocked: Bool {
        isSubmissionRunning ||
        completedPositiveResult != nil ||
        isFullSubmissionComplete
    }

    private var areNegativePhotosLocked: Bool {
        isSubmissionRunning ||
        isFullSubmissionComplete
    }

    private var negativePhotoStatusText: String {
        if capturedNegativePhotos.count <
            minimumNegativePhotoCount {

            let remaining =
                minimumNegativePhotoCount -
                capturedNegativePhotos.count

            return "\(remaining) more negative photo\(remaining == 1 ? "" : "s") required."
        }

        if capturedNegativePhotos.count >=
            maximumNegativePhotoCount {

            return "Maximum of \(maximumNegativePhotoCount) negative photos captured."
        }

        return "Required negative photos captured."
    }

    // MARK: - Body

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

                if hasPositiveMedia ||
                    completedPositiveResult != nil {

                    landmarkForm
                }

                Spacer(minLength: 30)
            }
            .padding(.top, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .top) {
            Color.clear
                .frame(height: 50)
        }
        .sheet(isPresented: $showVideoPicker) {
            videoPicker
        }
        .sheet(isPresented: $showPhotoPicker) {
            photoPicker
        }
        .fullScreenCover(
            isPresented: $showNegativeCamera
        ) {
            MultiPhotoCameraView(
                existingPhotos:
                    capturedNegativePhotos,
                minimumPhotoCount:
                    minimumNegativePhotoCount,
                maximumPhotoCount:
                    maximumNegativePhotoCount
            ) { photos in
                capturedNegativePhotos = photos

                /*
                 Changing the photo selection after a failed
                 negative upload should clear the old negative
                 service message before retrying.
                 */
                if !hardNegativeUploadService.isUploading {
                    hardNegativeUploadService.reset()
                }
            }
        }
        .alert(
            "Invalid Video Length",
            isPresented:
                $showVideoDurationAlert
        ) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(videoDurationAlertMessage)
        }
        .alert(
            "Landmark Uploaded!",
            isPresented: $showCompletionPopup
        ) {
            Button("Create Another Landmark") {
                resetForAnotherLandmark()
            }

            Button("Add More Photos or Videos") {
                openAdditionalMediaUpload()
            }
        } message: {
            Text(
                "Your landmark media and negative reference photos were uploaded successfully. What would you like to do next?"
            )
        }
    }

    // MARK: - Positive media section

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
            .frame(height: 135)
            .overlay {
                Text(
                    "Record one short video or take one photo of the landmark. This will be used as positive recognition data."
                )
                .padding()
                .multilineTextAlignment(.center)
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
            .clipShape(
                RoundedRectangle(cornerRadius: 15)
            )
            .disabled(arePositiveDetailsLocked)
            .opacity(
                arePositiveDetailsLocked ? 0.6 : 1
            )

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
            .disabled(arePositiveDetailsLocked)
            .opacity(
                arePositiveDetailsLocked ? 0.6 : 1
            )
        }
        .padding(.horizontal)
    }

    // MARK: - Location section

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            if locationManager.isAuthorized,
               let latitude =
                    locationManager.latitude,
               let longitude =
                    locationManager.longitude {

                Text(
                    "Location: \(latitude), \(longitude) " +
                    "(±\(Int(locationManager.horizontalAccuracy ?? 0))m)"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

            } else if
                locationManager.authorizationStatus ==
                    .denied
                ||
                locationManager.authorizationStatus ==
                    .restricted {

                Text(
                    "Location: Off — permission denied"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

            } else {
                Text(
                    "Location: Requesting permission…"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            Button("Enable Location") {
                locationManager
                    .requestPermissionIfNeeded()
            }
            .font(.footnote)
            .disabled(arePositiveDetailsLocked)
        }
        .padding(.horizontal)
    }

    // MARK: - Landmark form

    private var landmarkForm: some View {
        VStack(
            alignment: .leading,
            spacing: 12
        ) {
            Text("Label (required)")
                .padding(.horizontal)

            TextField(
                "e.g., Gampel Pavilion, Jonathan Statue, The Dairy Bar…",
                text: $labelText
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)
            .disabled(arePositiveDetailsLocked)

            if let businessLandmarkId {
                Text(
                    "Landmark ID: \(businessLandmarkId)"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
            }

            Text("Short description (required)")
                .padding(.horizontal)

            TextField(
                "e.g., Front entrance, scoreboard, statue base",
                text: $shortDescription
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)
            .disabled(arePositiveDetailsLocked)

            Text("What’s in the frame? (required)")
                .padding(.horizontal)

            TextField(
                "e.g., UConn logo, scoreboard, blue seats",
                text: $userDescription,
                axis: .vertical
            )
            .lineLimit(
                3,
                reservesSpace: true
            )
            .textFieldStyle(.roundedBorder)
            .padding(.horizontal)
            .disabled(arePositiveDetailsLocked)

            if completedPositiveResult != nil &&
                !isFullSubmissionComplete {

                positiveAlreadySavedCard
            }

            negativePhotoSection

            uploadButton

            positiveUploadStatusCard

            negativeUploadStatusCard

            overallCompletionCard
        }
    }

    // MARK: - Positive submission checkpoint

    private var positiveAlreadySavedCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(
                systemName: "checkmark.circle.fill"
            )
            .foregroundStyle(.green)
            .font(.title3)

            VStack(alignment: .leading, spacing: 4) {
                Text("Landmark media saved")
                    .font(.headline)

                Text(
                    "Your landmark and positive media were already uploaded. Retrying will upload only the negative reference photos."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(
            Color.green.opacity(0.08)
        )
        .clipShape(
            RoundedRectangle(cornerRadius: 16)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(
                    Color.green.opacity(0.25)
                )
        }
        .padding(.horizontal)
    }

    // MARK: - Negative photo section

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
                    hasRequiredNegativePhotos
                    ? Color.green
                    : Color.orange
                )
            }

            Text(
                "Take 5–10 photos of nearby walls, hallways, objects, or angles that should not be recognized as this landmark. Do not include the landmark itself."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(
                horizontal: false,
                vertical: true
            )

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
            .clipShape(
                RoundedRectangle(cornerRadius: 14)
            )
            .disabled(areNegativePhotosLocked)
            .opacity(
                areNegativePhotosLocked ? 0.6 : 1
            )

            if capturedNegativePhotos.isEmpty {
                Text(
                    "No negative photos captured yet."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            } else {
                negativeThumbnailStrip
            }

            Label(
                negativePhotoStatusText,
                systemImage:
                    hasRequiredNegativePhotos
                    ? "checkmark.circle.fill"
                    : "exclamationmark.circle.fill"
            )
            .font(.footnote.bold())
            .foregroundStyle(
                hasRequiredNegativePhotos
                ? Color.green
                : Color.orange
            )

            Text(
                "These photos will be uploaded as negative training examples after the landmark media is saved."
            )
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(
                horizontal: false,
                vertical: true
            )
        }
        .padding()
        .background(
            Color(
                red: 0.96,
                green: 0.97,
                blue: 1.00
            )
        )
        .clipShape(
            RoundedRectangle(cornerRadius: 18)
        )
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
        ScrollView(
            .horizontal,
            showsIndicators: false
        ) {
            HStack(spacing: 10) {
                ForEach(
                    capturedNegativePhotos
                ) { photo in

                    ZStack(alignment: .topTrailing) {
                        Image(
                            uiImage: photo.thumbnail
                        )
                        .resizable()
                        .scaledToFill()
                        .frame(
                            width: 78,
                            height: 78
                        )
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 10
                            )
                        )
                        .clipped()

                        Button {
                            removeNegativePhoto(photo)
                        } label: {
                            Image(
                                systemName:
                                    "xmark.circle.fill"
                            )
                            .font(.title3)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(
                                .white,
                                .red
                            )
                        }
                        .offset(x: 6, y: -6)
                        .disabled(
                            areNegativePhotosLocked
                        )
                    }
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 4)
        }
    }

    // MARK: - Main upload button

    private var uploadButton: some View {
        Button {
            startFullSubmission()
        } label: {
            HStack(spacing: 10) {
                if isSubmissionRunning {
                    ProgressView()
                        .tint(.white)

                    Text(activeUploadButtonText)
                        .fontWeight(.semibold)

                } else {
                    Label(
                        idleUploadButtonText,
                        systemImage:
                            uploadButtonSystemImage
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
            canUpload
            ? primaryColor
            : Color.gray
        )
        .clipShape(
            RoundedRectangle(cornerRadius: 15)
        )
        .disabled(!canUpload)
    }

    private var activeUploadButtonText: String {
        if hardNegativeUploadService.isUploading {
            return "Uploading reference photos…"
        }

        return "Uploading landmark…"
    }

    private var idleUploadButtonText: String {
        if isFullSubmissionComplete {
            return "Submission Complete"
        }

        if completedPositiveResult != nil {
            return "Retry Negative Photos"
        }

        return "Upload Landmark"
    }

    private var uploadButtonSystemImage: String {
        if isFullSubmissionComplete {
            return "checkmark.circle.fill"
        }

        if completedPositiveResult != nil {
            return "arrow.clockwise.circle"
        }

        return "arrow.up.circle"
    }

    // MARK: - Full submission workflow

    private func startFullSubmission() {
        Task {
            guard !isSubmissionRunning,
                  !isFullSubmissionComplete else {
                return
            }

            if businessLandmarkId == nil {
                businessLandmarkId =
                    makeBusinessLandmarkId()
            }

            guard let generatedLandmarkId =
                    businessLandmarkId else {
                return
            }

            do {
                let positiveResult:
                    PositiveSubmissionResult

                /*
                 Run the positive upload only when it has not
                 already succeeded.
                 */
                if let existingResult =
                    completedPositiveResult {

                    positiveResult = existingResult

                } else {
                    let trimmedLabel = labelText
                        .trimmingCharacters(
                            in: .whitespacesAndNewlines
                        )

                    guard !trimmedLabel.isEmpty else {
                        return
                    }

                    await vm.fetchUserEmail()

                    positiveResult =
                        try await uploadService.upload(
                            userEmail: vm.userEmail,
                            label: trimmedLabel,
                            landmarkId:
                                generatedLandmarkId,
                            landmarkLabel:
                                trimmedLabel,
                            shortDescription:
                                shortDescription,
                            userDescription:
                                userDescription,
                            latitude:
                                locationManager.latitude,
                            longitude:
                                locationManager.longitude,
                            horizontalAccuracy:
                                locationManager
                                    .horizontalAccuracy,
                            videoURL:
                                pickedVideoURL,
                            image:
                                pickedImage
                        )

                    completedPositiveResult =
                        positiveResult

                    statusText =
                        "Landmark media saved. Uploading negative reference photos…"
                }

                /*
                 Prefer the ID returned by the positive result.
                 The locally generated ID remains the fallback.
                 */
                let finalLandmarkId =
                    positiveResult.landmarkId ??
                    generatedLandmarkId

                let negativeResult =
                    try await hardNegativeUploadService
                        .upload(
                            landmarkId:
                                finalLandmarkId,
                            photos:
                                capturedNegativePhotos
                        )

                print(
                    "✅ Negative upload completed:",
                    negativeResult.batchId,
                    negativeResult.processedCount
                )

                completedLandmarkId = finalLandmarkId
                isFullSubmissionComplete = true

                statusText =
                    "Landmark and reference photos uploaded successfully."

                showCompletionPopup = true

            } catch {
                print(
                    "❌ Full landmark submission failed:",
                    error.localizedDescription
                )

                /*
                 UploadService and HardNegativeUploadService each
                 maintain their own user-facing failure message.

                 completedPositiveResult intentionally remains set
                 when only the negative upload fails.
                 */
            }
        }
    }

    // MARK: - Positive upload status

    @ViewBuilder
    private var positiveUploadStatusCard: some View {
        if uploadService.stage != .idle {
            VStack(
                alignment: .leading,
                spacing: 12
            ) {
                HStack(
                    alignment: .top,
                    spacing: 12
                ) {
                    if uploadService.isUploading {
                        ProgressView()
                            .controlSize(.regular)
                            .padding(.top, 2)
                    } else {
                        Image(
                            systemName:
                                uploadService
                                    .stage
                                    .systemImage
                        )
                        .font(.title3)
                        .foregroundStyle(
                            positiveUploadStatusColor
                        )
                        .padding(.top, 1)
                    }

                    VStack(
                        alignment: .leading,
                        spacing: 4
                    ) {
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
                        value:
                            uploadService.progress,
                        total: 1
                    )
                    .progressViewStyle(.linear)

                    Text(
                        "\(Int(uploadService.progress * 100))% complete"
                    )
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                    Text(
                        "Please keep LookSee open until this step finishes."
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
                            systemImage:
                                "xmark.circle"
                        )
                    }
                    .font(.footnote.bold())
                }
            }
            .padding()
            .background(
                Color(
                    uiColor:
                        .secondarySystemBackground
                )
            )
            .clipShape(
                RoundedRectangle(cornerRadius: 16)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        positiveUploadStatusColor
                            .opacity(0.3)
                    )
            }
            .padding(.horizontal)
        }
    }

    private var positiveUploadStatusColor: Color {
        switch uploadService.stage {
        case .complete:
            return .green

        case .failed:
            return .red

        default:
            return primaryColor
        }
    }

    // MARK: - Negative upload status

    @ViewBuilder
    private var negativeUploadStatusCard: some View {
        if hardNegativeUploadService.status != "Idle" {
            VStack(
                alignment: .leading,
                spacing: 12
            ) {
                HStack(
                    alignment: .top,
                    spacing: 12
                ) {
                    if hardNegativeUploadService
                        .isUploading {

                        ProgressView()
                            .controlSize(.regular)
                            .padding(.top, 2)

                    } else {
                        Image(
                            systemName:
                                negativeStatusSystemImage
                        )
                        .font(.title3)
                        .foregroundStyle(
                            negativeStatusColor
                        )
                    }

                    VStack(
                        alignment: .leading,
                        spacing: 4
                    ) {
                        Text(
                            negativeStatusTitle
                        )
                        .font(.headline)

                        Text(
                            hardNegativeUploadService
                                .status
                        )
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(
                            horizontal: false,
                            vertical: true
                        )
                    }

                    Spacer()
                }

                if hardNegativeUploadService
                    .isUploading {

                    ProgressView(
                        value:
                            hardNegativeUploadService
                                .progress,
                        total: 1
                    )
                    .progressViewStyle(.linear)

                    Text(
                        "\(Int(hardNegativeUploadService.progress * 100))% complete"
                    )
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                    Text(
                        "Uploading \(capturedNegativePhotos.count) negative reference photos. Please keep LookSee open."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                if negativeUploadHasFailed {
                    Button {
                        hardNegativeUploadService
                            .reset()
                    } label: {
                        Label(
                            "Dismiss Error",
                            systemImage:
                                "xmark.circle"
                        )
                    }
                    .font(.footnote.bold())
                }
            }
            .padding()
            .background(
                Color(
                    uiColor:
                        .secondarySystemBackground
                )
            )
            .clipShape(
                RoundedRectangle(cornerRadius: 16)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        negativeStatusColor
                            .opacity(0.3)
                    )
            }
            .padding(.horizontal)
        }
    }

    private var negativeUploadHasFailed: Bool {
        hardNegativeUploadService.status
            .lowercased()
            .contains("failed")
    }

    private var negativeUploadCompleted: Bool {
        hardNegativeUploadService.progress >= 1 &&
        !hardNegativeUploadService.isUploading &&
        !negativeUploadHasFailed
    }

    private var negativeStatusTitle: String {
        if negativeUploadHasFailed {
            return "Reference photos need attention"
        }

        if negativeUploadCompleted {
            return "Reference photos uploaded"
        }

        return "Uploading reference photos"
    }

    private var negativeStatusSystemImage: String {
        if negativeUploadHasFailed {
            return "exclamationmark.triangle.fill"
        }

        if negativeUploadCompleted {
            return "checkmark.circle.fill"
        }

        return "photo.stack"
    }

    private var negativeStatusColor: Color {
        if negativeUploadHasFailed {
            return .red
        }

        if negativeUploadCompleted {
            return .green
        }

        return primaryColor
    }

    // MARK: - Overall success

    @ViewBuilder
    private var overallCompletionCard: some View {
        if isFullSubmissionComplete {
            HStack(
                alignment: .top,
                spacing: 12
            ) {
                Image(
                    systemName:
                        "checkmark.seal.fill"
                )
                .font(.title2)
                .foregroundStyle(.green)

                VStack(
                    alignment: .leading,
                    spacing: 4
                ) {
                    Text(
                        "Landmark submission complete"
                    )
                    .font(.headline)

                    Text(
                        "Your landmark media and negative reference photos were uploaded successfully."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                Spacer()
            }
            .padding()
            .background(
                Color.green.opacity(0.08)
            )
            .clipShape(
                RoundedRectangle(cornerRadius: 16)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        Color.green.opacity(0.3)
                    )
            }
            .padding(.horizontal)
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

                labelText = ""
                shortDescription = ""
                userDescription = ""

                businessLandmarkId =
                    makeBusinessLandmarkId()

                completedPositiveResult = nil
                isFullSubmissionComplete = false

                uploadService.reset()
                hardNegativeUploadService.reset()
            },
            onInvalidDuration: { message in
                pickedVideoURL = nil

                videoDurationAlertMessage =
                    message

                showVideoDurationAlert = true
                statusText = message

                completedPositiveResult = nil
                isFullSubmissionComplete = false

                uploadService.reset()
                hardNegativeUploadService.reset()
            }
        )
    }

    private var photoPicker: some View {
        PhotoPicker { image in
            pickedImage = image
            pickedVideoURL = nil

            statusText =
                "Selected landmark photo."

            labelText = ""
            shortDescription = ""
            userDescription = ""

            businessLandmarkId =
                makeBusinessLandmarkId()

            completedPositiveResult = nil
            isFullSubmissionComplete = false

            uploadService.reset()
            hardNegativeUploadService.reset()
        }
    }

    // MARK: - Helpers
    
    private func openAdditionalMediaUpload() {
        guard let landmarkId = completedLandmarkId else {
            return
        }

        resetForAnotherLandmark()
        onAddMoreMedia(landmarkId)
    }

    private func resetForAnotherLandmark() {
        deleteTemporaryPositiveVideo()

        for photo in capturedNegativePhotos {
            photo.deleteLocalFile()
        }

        labelText = ""
        businessLandmarkId = nil

        shortDescription = ""
        userDescription = ""

        pickedVideoURL = nil
        pickedImage = nil

        capturedNegativePhotos = []

        completedPositiveResult = nil
        completedLandmarkId = nil

        isFullSubmissionComplete = false
        showCompletionPopup = false

        showVideoPicker = false
        showPhotoPicker = false
        showNegativeCamera = false

        showVideoDurationAlert = false
        videoDurationAlertMessage = ""

        statusText = "No landmark media selected."

        uploadService.reset()
        hardNegativeUploadService.reset()
    }

    private func deleteTemporaryPositiveVideo() {
        guard let videoURL = pickedVideoURL else {
            return
        }

        let temporaryDirectory =
            FileManager.default
                .temporaryDirectory
                .standardizedFileURL
                .path

        let videoPath =
            videoURL
                .standardizedFileURL
                .path

        guard videoPath.hasPrefix(temporaryDirectory) else {
            return
        }

        try? FileManager.default.removeItem(
            at: videoURL
        )
    }

    private func makeBusinessLandmarkId() -> String {
        let suffix = UUID()
            .uuidString
            .replacingOccurrences(
                of: "-",
                with: ""
            )
            .prefix(8)

        return "landmark_\(suffix)"
    }

    private func removeNegativePhoto(
        _ photo: CapturedNegativePhoto
    ) {
        capturedNegativePhotos.removeAll {
            $0.id == photo.id
        }

        photo.deleteLocalFile()

        if !hardNegativeUploadService.isUploading {
            hardNegativeUploadService.reset()
        }
    }
}

#Preview {
    LandmarkRecord()
}
