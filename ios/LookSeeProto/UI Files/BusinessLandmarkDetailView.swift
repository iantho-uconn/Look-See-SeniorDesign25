//
//  BusinessLandmarkDetailView.swift
//  LookSeeProto
//
//  Detail/edit page for one business-owned landmark.
//

import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct BusinessLandmarkDetailView: View {
    let landmark: BusinessLandmark
    let onLandmarkUpdated: (BusinessLandmark) -> Void

    @State private var displayedShortDescription: String
    @State private var draftShortDescription: String
    @State private var isEditingDescription = false
    @State private var isSavingDescription = false
    @State private var saveErrorMessage: String?

    @State private var displayedIsActive: Bool
    @State private var displayedPromotionEnabled: Bool
    @State private var isSavingManagement = false
    @State private var managementErrorMessage: String?

    @State private var showPositivePicker = false
    @State private var showNegativePicker = false
    @State private var selectedPositiveMediaItems: [PhotosPickerItem] = []
    @State private var selectedNegativeMediaItems: [PhotosPickerItem] = []

    @State private var isUploadingMedia = false
    @State private var activeUploadRole: BusinessDatasetRole?
    @State private var uploadStatusMessage: String?
    @State private var uploadErrorMessage: String?
    @State private var uploadProgressText: String?

    private let service = BusinessLandmarkService()
    private let maxSelectionCount = 10

    init(
        landmark: BusinessLandmark,
        onLandmarkUpdated: @escaping (BusinessLandmark) -> Void = { _ in }
    ) {
        self.landmark = landmark
        self.onLandmarkUpdated = onLandmarkUpdated

        let initialDescription = landmark.shortDescription?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        _displayedShortDescription = State(initialValue: initialDescription)
        _draftShortDescription = State(initialValue: initialDescription)
        _displayedIsActive = State(initialValue: landmark.isActive ?? true)
        _displayedPromotionEnabled = State(initialValue: landmark.promotionEnabled ?? false)
    }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                        .font(.title2.weight(.bold))

                    Text(displayDescription)
                        .font(.body)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section(header: Text("Landmark Info")) {
                Button {
                    draftShortDescription = displayedShortDescription
                    saveErrorMessage = nil
                    isEditingDescription = true
                } label: {
                    HStack {
                        Label("Edit Short Description", systemImage: "square.and.pencil")

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                if let saveErrorMessage {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)

                        Text(saveErrorMessage)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Section(
                header: Text("Management"),
                footer: Text("Active landmarks can be used by the app. Promotions Enabled controls whether this landmark is allowed to show promotions once promotion records are added.")
            ) {
                Toggle(isOn: Binding(
                    get: { displayedIsActive },
                    set: { newValue in
                        updateManagementSetting(
                            isActive: newValue,
                            promotionEnabled: nil
                        )
                    }
                )) {
                    Label(
                        displayedIsActive ? "Active Landmark" : "Inactive Landmark",
                        systemImage: displayedIsActive ? "checkmark.circle" : "pause.circle"
                    )
                }
                .disabled(isSavingManagement)

                Toggle(isOn: Binding(
                    get: { displayedPromotionEnabled },
                    set: { newValue in
                        updateManagementSetting(
                            isActive: nil,
                            promotionEnabled: newValue
                        )
                    }
                )) {
                    Label(
                        displayedPromotionEnabled ? "Promotions Enabled" : "Promotions Disabled",
                        systemImage: displayedPromotionEnabled ? "tag.fill" : "tag"
                    )
                }
                .disabled(isSavingManagement)

                if isSavingManagement {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Saving management setting...")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }

                if let managementErrorMessage {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)

                        Text(managementErrorMessage)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Section(header: Text("Location")) {
                detailRow(
                    title: "Latitude",
                    value: formattedCoordinate(landmark.latitude)
                )

                detailRow(
                    title: "Longitude",
                    value: formattedCoordinate(landmark.longitude)
                )
            }

            if let promotion = landmark.promotion,
               !promotion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Section(header: Text("Promotion")) {
                    Text(promotion)
                        .foregroundColor(.primary)
                }
            }

            Section(
                header: Text("Media Uploads"),
                footer: Text("Choose media first, confirm your selection with the blue checkmark in the photo picker, then submit when ready.")
            ) {
                positivePickerButton
                positiveSelectionControls

                negativePickerButton
                negativeSelectionControls

                uploadStatusArea
            }

            Section(header: Text("Identifiers")) {
                detailRow(title: "Landmark ID", value: landmark.landmarkId)

                if let ownerUserId = landmark.ownerUserId,
                   !ownerUserId.isEmpty {
                    detailRow(title: "Owner User ID", value: ownerUserId)
                }

                if let userEmail = landmark.userEmail,
                   !userEmail.isEmpty {
                    detailRow(title: "Owner Email", value: userEmail)
                }

                if let updatedAt = landmark.updatedAt,
                   !updatedAt.isEmpty {
                    detailRow(title: "Updated At", value: updatedAt)
                }
            }
        }
        .navigationTitle("Landmark Details")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isEditingDescription) {
            editDescriptionSheet
        }
    }

    // MARK: - Picker Buttons

    private var positivePickerButton: some View {
        Button {
            print("📸 Opening positive media picker")
            showPositivePicker = true
        } label: {
            uploadRow(
                title: "Choose Positive Media",
                subtitle: selectedMediaSubtitle(
                    count: selectedPositiveMediaItems.count,
                    emptyText: "Select photos or videos of this landmark."
                ),
                systemImage: "plus.circle"
            )
        }
        .buttonStyle(.plain)
        .disabled(isUploadingMedia)
        .photosPicker(
            isPresented: $showPositivePicker,
            selection: $selectedPositiveMediaItems,
            maxSelectionCount: maxSelectionCount,
            matching: .any(of: [.images, .videos]),
            photoLibrary: .shared()
        )
    }

    private var negativePickerButton: some View {
        Button {
            print("📸 Opening negative media picker")
            showNegativePicker = true
        } label: {
            uploadRow(
                title: "Choose Negative Examples",
                subtitle: selectedMediaSubtitle(
                    count: selectedNegativeMediaItems.count,
                    emptyText: "Select nearby objects that are not this landmark."
                ),
                systemImage: "minus.circle"
            )
        }
        .buttonStyle(.plain)
        .disabled(isUploadingMedia)
        .photosPicker(
            isPresented: $showNegativePicker,
            selection: $selectedNegativeMediaItems,
            maxSelectionCount: maxSelectionCount,
            matching: .any(of: [.images, .videos]),
            photoLibrary: .shared()
        )
    }

    private var positiveSelectionControls: some View {
        Group {
            if !selectedPositiveMediaItems.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(selectedPositiveMediaItems.count) positive item\(selectedPositiveMediaItems.count == 1 ? "" : "s") selected")
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    Button {
                        print("🚀 Submit positive tapped with \(selectedPositiveMediaItems.count) item(s)")

                        Task {
                            await uploadSelectedMediaItems(
                                items: selectedPositiveMediaItems,
                                datasetRole: .positive
                            )
                        }
                    } label: {
                        Label("Submit Positive Upload", systemImage: "arrow.up.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isUploadingMedia)

                    Button(role: .destructive) {
                        print("🧹 Clearing positive selection")
                        selectedPositiveMediaItems.removeAll()
                    } label: {
                        Label("Clear Positive Selection", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isUploadingMedia)
                }
                .padding(.vertical, 6)
            }
        }
    }

    private var negativeSelectionControls: some View {
        Group {
            if !selectedNegativeMediaItems.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(selectedNegativeMediaItems.count) negative item\(selectedNegativeMediaItems.count == 1 ? "" : "s") selected")
                        .font(.footnote)
                        .foregroundColor(.secondary)

                    Button {
                        print("🚀 Submit negative tapped with \(selectedNegativeMediaItems.count) item(s)")

                        Task {
                            await uploadSelectedMediaItems(
                                items: selectedNegativeMediaItems,
                                datasetRole: .hardNegative
                            )
                        }
                    } label: {
                        Label("Submit Negative Upload", systemImage: "arrow.up.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isUploadingMedia)

                    Button(role: .destructive) {
                        print("🧹 Clearing negative selection")
                        selectedNegativeMediaItems.removeAll()
                    } label: {
                        Label("Clear Negative Selection", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isUploadingMedia)
                }
                .padding(.vertical, 6)
            }
        }
    }

    private var uploadStatusArea: some View {
        Group {
            if isUploadingMedia {
                HStack(spacing: 10) {
                    ProgressView()

                    VStack(alignment: .leading, spacing: 2) {
                        Text(uploadingText)
                            .font(.footnote)
                            .foregroundColor(.secondary)

                        if let uploadProgressText {
                            Text(uploadProgressText)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }

            if let uploadStatusMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)

                    Text(uploadStatusMessage)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }

            if let uploadErrorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)

                    Text(uploadErrorMessage)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    // MARK: - Description Editing

    private var displayDescription: String {
        let cleaned = displayedShortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "No description available." : cleaned
    }

    private var editDescriptionSheet: some View {
        NavigationStack {
            Form {
                Section(
                    header: Text("Short Description"),
                    footer: Text("This description is shown to users when LookSee identifies this landmark.")
                ) {
                    TextEditor(text: $draftShortDescription)
                        .frame(minHeight: 160)
                        .disabled(isSavingDescription)
                }

                if let saveErrorMessage {
                    Section {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)

                            Text(saveErrorMessage)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Edit Description")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        isEditingDescription = false
                    }
                    .disabled(isSavingDescription)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        saveDescription()
                    } label: {
                        if isSavingDescription {
                            ProgressView()
                        } else {
                            Text("Save")
                        }
                    }
                    .disabled(
                        isSavingDescription ||
                        draftShortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )
                }
            }
        }
    }

    private func saveDescription() {
        guard !isSavingDescription else { return }

        let cleanedDescription = draftShortDescription.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanedDescription.isEmpty else {
            saveErrorMessage = "Short description cannot be empty."
            return
        }

        isSavingDescription = true
        saveErrorMessage = nil

        Task {
            do {
                let updatedLandmark = try await service.updateShortDescription(
                    landmarkId: landmark.landmarkId,
                    shortDescription: cleanedDescription
                )

                await MainActor.run {
                    displayedShortDescription = updatedLandmark.shortDescription ?? cleanedDescription
                    draftShortDescription = displayedShortDescription
                    onLandmarkUpdated(updatedLandmark)
                    isSavingDescription = false
                    isEditingDescription = false
                }
            } catch {
                await MainActor.run {
                    saveErrorMessage = error.localizedDescription
                    isSavingDescription = false
                }
            }
        }
    }

    // MARK: - Management Settings

    private func updateManagementSetting(
        isActive: Bool?,
        promotionEnabled: Bool?
    ) {
        guard !isSavingManagement else { return }

        let previousIsActive = displayedIsActive
        let previousPromotionEnabled = displayedPromotionEnabled

        if let isActive {
            displayedIsActive = isActive
        }

        if let promotionEnabled {
            displayedPromotionEnabled = promotionEnabled
        }

        isSavingManagement = true
        managementErrorMessage = nil

        Task {
            do {
                let updatedLandmark = try await service.updateLandmarkSettings(
                    landmarkId: landmark.landmarkId,
                    isActive: isActive,
                    promotionEnabled: promotionEnabled
                )

                await MainActor.run {
                    displayedIsActive = updatedLandmark.isActive ?? displayedIsActive
                    displayedPromotionEnabled = updatedLandmark.promotionEnabled ?? displayedPromotionEnabled
                    onLandmarkUpdated(updatedLandmark)
                    isSavingManagement = false
                }
            } catch {
                await MainActor.run {
                    displayedIsActive = previousIsActive
                    displayedPromotionEnabled = previousPromotionEnabled
                    managementErrorMessage = error.localizedDescription
                    isSavingManagement = false
                }
            }
        }
    }

    // MARK: - Media Upload Logic

    private var uploadingText: String {
        if let activeUploadRole {
            return "Uploading \(activeUploadRole.displayName.lowercased())..."
        }

        return "Uploading media..."
    }

    private func uploadSelectedMediaItems(
        items: [PhotosPickerItem],
        datasetRole: BusinessDatasetRole
    ) async {
        guard !isUploadingMedia else {
            print("⚠️ Upload already in progress")
            return
        }

        guard !items.isEmpty else {
            print("⚠️ Submit tapped but no items were selected")
            return
        }

        await MainActor.run {
            isUploadingMedia = true
            activeUploadRole = datasetRole
            uploadStatusMessage = nil
            uploadErrorMessage = nil
            uploadProgressText = "Preparing \(items.count) item\(items.count == 1 ? "" : "s")..."
        }

        print("🚀 Starting \(datasetRole.rawValue) upload for \(items.count) item(s)")

        var completedCount = 0
        var failedCount = 0
        var lastSubmissionId: String?

        for index in items.indices {
            let item = items[index]

            await MainActor.run {
                uploadProgressText = "Uploading item \(index + 1) of \(items.count)..."
            }

            do {
                guard let mediaData = try await item.loadTransferable(type: Data.self) else {
                    throw MediaSelectionError.couldNotLoadMedia
                }

                let contentType = item.supportedContentTypes.first ?? .data
                let mediaKind = inferMediaKind(from: contentType)
                let mimeType = contentType.preferredMIMEType ?? "application/octet-stream"

                let filename = makeUploadFilename(
                    datasetRole: datasetRole,
                    mediaKind: mediaKind,
                    contentType: contentType,
                    index: index + 1
                )

                print("⬆️ Business media upload starting")
                print("   item: \(index + 1)/\(items.count)")
                print("   landmarkId: \(landmark.landmarkId)")
                print("   datasetRole: \(datasetRole.rawValue)")
                print("   mediaKind: \(mediaKind.rawValue)")
                print("   filename: \(filename)")
                print("   contentType: \(mimeType)")
                print("   bytes: \(mediaData.count)")

                let response = try await service.uploadBusinessMedia(
                    landmarkId: landmark.landmarkId,
                    datasetRole: datasetRole,
                    mediaKind: mediaKind,
                    filename: filename,
                    contentType: mimeType,
                    data: mediaData
                )

                completedCount += 1
                lastSubmissionId = response.submissionId

                print("✅ Business media upload complete: \(response.submissionId)")

            } catch {
                failedCount += 1
                print("❌ Business media upload failed on item \(index + 1): \(error.localizedDescription)")
            }
        }

        await MainActor.run {
            isUploadingMedia = false
            activeUploadRole = nil
            uploadProgressText = nil

            if failedCount == 0 {
                uploadStatusMessage = successSummaryMessage(
                    datasetRole: datasetRole,
                    completedCount: completedCount,
                    lastSubmissionId: lastSubmissionId
                )

                uploadErrorMessage = nil

                switch datasetRole {
                case .positive:
                    selectedPositiveMediaItems.removeAll()
                case .hardNegative:
                    selectedNegativeMediaItems.removeAll()
                }
            } else {
                uploadStatusMessage = completedCount > 0
                    ? "\(completedCount) item\(completedCount == 1 ? "" : "s") uploaded successfully."
                    : nil

                uploadErrorMessage = "\(failedCount) item\(failedCount == 1 ? "" : "s") failed to upload. Please try again."
            }
        }
    }

    private func successSummaryMessage(
        datasetRole: BusinessDatasetRole,
        completedCount: Int,
        lastSubmissionId: String?
    ) -> String {
        let base: String

        switch datasetRole {
        case .positive:
            base = "\(completedCount) positive item\(completedCount == 1 ? "" : "s") uploaded successfully."
        case .hardNegative:
            base = "\(completedCount) negative example\(completedCount == 1 ? "" : "s") uploaded successfully."
        }

        if let lastSubmissionId {
            return "\(base) Last submission: \(lastSubmissionId)"
        }

        return base
    }

    private func selectedMediaSubtitle(
        count: Int,
        emptyText: String
    ) -> String {
        if count == 0 {
            return emptyText
        }

        return "\(count) item\(count == 1 ? "" : "s") selected. Tap Submit when ready."
    }

    private func inferMediaKind(from contentType: UTType) -> BusinessMediaKind {
        if contentType.conforms(to: .movie) || contentType.conforms(to: .video) {
            return .video
        }

        return .photo
    }

    private func makeUploadFilename(
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        contentType: UTType,
        index: Int
    ) -> String {
        let fallbackExtension = mediaKind == .video ? "mov" : "jpg"
        let fileExtension = contentType.preferredFilenameExtension ?? fallbackExtension

        let cleanedLabel = landmark.label
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "/", with: "_")

        let labelComponent = cleanedLabel.isEmpty ? landmark.landmarkId : cleanedLabel

        return "\(labelComponent)_\(datasetRole.filenameComponent)_\(index)_\(UUID().uuidString).\(fileExtension)"
    }

    // MARK: - Small UI Helpers

    private func uploadRow(
        title: String,
        subtitle: String,
        systemImage: String
    ) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundColor(.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundColor(.primary)

                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .contentShape(Rectangle())
    }

    private func formattedCoordinate(_ value: Double?) -> String {
        guard let value else {
            return "Not available"
        }

        return String(format: "%.6f", value)
    }

    private func detailRow(title: String, value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .foregroundColor(.secondary)

            Spacer(minLength: 16)

            Text(value)
                .multilineTextAlignment(.trailing)
                .foregroundColor(.primary)
        }
    }
}

private enum MediaSelectionError: LocalizedError {
    case couldNotLoadMedia

    var errorDescription: String? {
        switch self {
        case .couldNotLoadMedia:
            return "Could not load the selected media."
        }
    }
}
