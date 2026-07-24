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
    let onLandmarkDeleted: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var displayedShortDescription: String
    @State private var draftShortDescription: String
    @State private var isEditingDescription = false
    @State private var isSavingDescription = false
    @State private var saveErrorMessage: String?

    @State private var displayedIsActive: Bool
    @State private var displayedPromotionEnabled: Bool
    @State private var isSavingManagement = false
    @State private var managementErrorMessage: String?

    @State private var promotions: [BusinessPromotion] = []
    @State private var isLoadingPromotions = false
    @State private var promotionErrorMessage: String?
    @State private var promotionEditorContext: BusinessPromotionEditorContext?
    @State private var promotionPendingDelete: BusinessPromotion?
    @State private var savingPromotionIds: Set<String> = []

    @State private var isShowingDeleteLandmarkSheet = false
    @State private var deleteConfirmationText = ""
    @State private var isDeletingLandmark = false
    @State private var deleteErrorMessage: String?

    @State private var showPositivePicker = false
    @State private var showNegativePicker = false
    @State private var selectedPositiveMediaItems: [PhotosPickerItem] = []
    @State private var selectedNegativeMediaItems: [PhotosPickerItem] = []

    @State private var isUploadingMedia = false
    @State private var activeUploadRole: BusinessDatasetRole?
    @State private var uploadStatusMessage: String?
    @State private var uploadErrorMessage: String?
    @State private var uploadProgressText: String?
    
    @State private var displayedWebsiteUrl: String
    @State private var draftWebsiteUrl: String
    @State private var isEditingWebsiteUrl = false
    @State private var isSavingWebsiteUrl = false
    @State private var websiteUrlErrorMessage: String?

    private let service = BusinessLandmarkService()
    private let promotionService = BusinessPromotionService()
    private let maxSelectionCount = 10
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    init(
        landmark: BusinessLandmark,
        onLandmarkUpdated: @escaping (BusinessLandmark) -> Void = { _ in },
        onLandmarkDeleted: @escaping (String) -> Void = { _ in }
    ) {
        self.landmark = landmark
        self.onLandmarkUpdated = onLandmarkUpdated
        self.onLandmarkDeleted = onLandmarkDeleted

        let initialDescription = landmark.shortDescription?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        let initialWebsiteUrl = landmark.websiteUrl?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        _displayedShortDescription = State(initialValue: initialDescription)
        _draftShortDescription = State(initialValue: initialDescription)
        _displayedIsActive = State(initialValue: landmark.isActive ?? true)
        _displayedPromotionEnabled = State(initialValue: landmark.promotionEnabled ?? false)
        _displayedWebsiteUrl = State(initialValue: initialWebsiteUrl)
        _draftWebsiteUrl = State(initialValue: initialWebsiteUrl)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {

                // MARK: - Header
                VStack(alignment: .leading, spacing: 12) {
                    Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)

                    Text(displayDescription)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.secondary)

                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        draftShortDescription = displayedShortDescription
                        saveErrorMessage = nil
                        isEditingDescription = true
                    } label: {
                        HStack {
                            Image(systemName: "square.and.pencil")
                            Text("Edit Description")
                                .fontWeight(.bold)
                        }
                        .font(.system(size: 14, design: .rounded))
                        .foregroundStyle(primaryColor)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(primaryColor.opacity(0.1))
                        .clipShape(Capsule())
                    }
                    .padding(.top, 4)

                    Divider()
                        .padding(.vertical, 4)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "link")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(primaryColor)
                                .frame(width: 22)

                            VStack(alignment: .leading, spacing: 4) {
                                Text("Website")
                                    .font(.system(size: 13, weight: .bold, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .textCase(.uppercase)

                                if displayedWebsiteUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    Text("No website added yet.")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text(displayedWebsiteUrl)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(.primary)
                                        .lineLimit(2)
                                }
                            }

                            Spacer()
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            draftWebsiteUrl = displayedWebsiteUrl
                            websiteUrlErrorMessage = nil
                            isEditingWebsiteUrl = true
                        } label: {
                            HStack {
                                Image(systemName: "link.badge.plus")
                                Text(displayedWebsiteUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Add Website" : "Edit Website")
                                    .fontWeight(.bold)
                            }
                            .font(.system(size: 14, design: .rounded))
                            .foregroundStyle(primaryColor)
                            .padding(.vertical, 8)
                            .padding(.horizontal, 16)
                            .background(primaryColor.opacity(0.1))
                            .clipShape(Capsule())
                        }
                    }

                    if let websiteUrlErrorMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)

                            Text(websiteUrlErrorMessage)
                                .font(.footnote.bold())
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: .black.opacity(0.03), radius: 10, x: 0, y: 4)
                .padding(.horizontal)

                // MARK: - Management
                VStack(alignment: .leading, spacing: 8) {
                    sectionTitle("Management")

                    VStack(spacing: 0) {
                        Toggle(
                            isOn: Binding(
                                get: { displayedIsActive },
                                set: { newValue in
                                    updateManagementSetting(
                                        isActive: newValue,
                                        promotionEnabled: nil
                                    )
                                }
                            )
                        ) {
                            Label(
                                displayedIsActive ? "Active Landmark" : "Inactive Landmark",
                                systemImage: displayedIsActive
                                    ? "checkmark.circle.fill"
                                    : "pause.circle.fill"
                            )
                            .foregroundStyle(displayedIsActive ? .green : .secondary)
                            .font(.system(size: 16, weight: .semibold))
                        }
                        .padding(16)
                        .disabled(isSavingManagement)

                        Divider()
                            .padding(.leading, 50)

                        Toggle(
                            isOn: Binding(
                                get: { displayedPromotionEnabled },
                                set: { newValue in
                                    updateManagementSetting(
                                        isActive: nil,
                                        promotionEnabled: newValue
                                    )
                                }
                            )
                        ) {
                            Label(
                                displayedPromotionEnabled
                                    ? "Promotions Enabled"
                                    : "Promotions Disabled",
                                systemImage: displayedPromotionEnabled ? "tag.fill" : "tag"
                            )
                            .foregroundStyle(
                                displayedPromotionEnabled ? .orange : .secondary
                            )
                            .font(.system(size: 16, weight: .semibold))
                        }
                        .padding(16)
                        .disabled(isSavingManagement)
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)

                    if isSavingManagement {
                        HStack(spacing: 10) {
                            ProgressView()
                                .tint(primaryColor)

                            Text("Saving settings...")
                                .font(.footnote.bold())
                                .foregroundColor(.secondary)
                        }
                        .padding(.horizontal, 20)
                    }

                    if let managementErrorMessage {
                        Text(managementErrorMessage)
                            .font(.footnote.bold())
                            .foregroundColor(.red)
                            .padding(.horizontal, 20)
                    }
                }

                // MARK: - Promotions
                VStack(alignment: .leading, spacing: 8) {
                    sectionTitle("Promotions")

                    VStack(spacing: 0) {
                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            promotionEditorContext = .create
                        } label: {
                            HStack {
                                Image(systemName: "plus.circle.fill")
                                    .font(.system(size: 20))
                                    .foregroundStyle(primaryColor)

                                Text("Add Promotion")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundStyle(primaryColor)

                                Spacer()
                            }
                            .padding(16)
                        }
                        .buttonStyle(.plain)

                        if isLoadingPromotions {
                            Divider()

                            HStack(spacing: 10) {
                                ProgressView()
                                    .tint(primaryColor)

                                Text("Loading promotions...")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(.secondary)

                                Spacer()
                            }
                            .padding(16)
                        } else if promotions.isEmpty {
                            Divider()

                            HStack {
                                Text("No promotions have been added for this landmark yet.")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(.secondary)

                                Spacer()
                            }
                            .padding(16)
                        } else {
                            ForEach(promotions) { promotion in
                                Divider()
                                promotionRow(promotion)
                                    .padding(16)
                            }
                        }
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)

                    Text(
                        displayedPromotionEnabled
                            ? "Promotions can be shown for this landmark when enabled and within their date range."
                            : "Promotions are currently disabled for this landmark. You can still create and edit records here."
                    )
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 20)

                    if let promotionErrorMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)

                            Text(promotionErrorMessage)
                                .font(.footnote.bold())
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 20)
                    }
                }

                // MARK: - Location
                VStack(alignment: .leading, spacing: 8) {
<<<<<<< HEAD
                    Text("Media Uploads")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.horizontal, 20)
                    
                    VStack(spacing: 16) {
                        BusinessMediaHistoryNavigationRow(
                            landmarkId: landmark.landmarkId,
                            landmarkLabel: landmark.label
                        )
                        Divider()
                        positivePickerButton
                        positiveSelectionControls
=======
                    sectionTitle("Location")

                    VStack(spacing: 12) {
                        detailRow(
                            title: "Latitude",
                            value: formattedCoordinate(landmark.latitude)
                        )

>>>>>>> origin/feature-URLWorkshop
                        Divider()

                        detailRow(
                            title: "Longitude",
                            value: formattedCoordinate(landmark.longitude)
                        )
                    }
                    .padding(20)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)
                }

<<<<<<< HEAD
=======
                // MARK: - Legacy Promotion
                if let legacyPromotion = landmark.promotion?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                   !legacyPromotion.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        sectionTitle("Promotion")

                        Text(legacyPromotion)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(20)
                            .background(Color(uiColor: .secondarySystemGroupedBackground))
                            .clipShape(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                            )
                            .shadow(
                                color: .black.opacity(0.03),
                                radius: 8,
                                x: 0,
                                y: 2
                            )
                            .padding(.horizontal)
                    }
                }

                // MARK: - Media Uploads
                VStack(alignment: .leading, spacing: 8) {
                    sectionTitle("Media Uploads")

                    VStack(spacing: 16) {
                        BusinessMediaHistoryNavigationRow(
                            landmarkId: landmark.landmarkId,
                            landmarkLabel: landmark.label
                        )

                        Divider()

                        positivePickerButton
                        positiveSelectionControls

                        Divider()

                        negativePickerButton
                        negativeSelectionControls

                        uploadStatusArea
                    }
                    .padding(20)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)

                    Text(
                        "Choose media first, confirm your selection in the photo picker, then submit when ready."
                    )
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                }

>>>>>>> origin/feature-URLWorkshop
                // MARK: - Danger Zone
                VStack(alignment: .leading, spacing: 8) {
                    sectionTitle("Danger Zone")

                    Button {
                        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                        deleteConfirmationText = ""
                        deleteErrorMessage = nil
                        isShowingDeleteLandmarkSheet = true
                    } label: {
                        HStack {
                            Image(systemName: "trash.fill")
                                .font(.system(size: 18))
                                .foregroundStyle(.red)

                            Text("Delete Landmark")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(.red)

                            Spacer()
                        }
                        .padding(16)
                        .background(Color.red.opacity(0.1))
                        .clipShape(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                        )
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)
                    .disabled(isDeletingLandmark)

                    Text(
                        "Deleting a landmark removes it from your account and starts backend cleanup for cluster mappings, dataset files, and promotions. This cannot be undone."
                    )
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
                }

                // MARK: - Identifiers
                VStack(alignment: .leading, spacing: 8) {
                    sectionTitle("Identifiers")

                    VStack(spacing: 12) {
                        detailRow(
                            title: "Landmark ID",
                            value: landmark.landmarkId
                        )

                        if let ownerUserId = landmark.ownerUserId,
                           !ownerUserId.isEmpty {
                            Divider()
                            detailRow(
                                title: "Owner User ID",
                                value: ownerUserId
                            )
                        }

                        if let userEmail = landmark.userEmail,
                           !userEmail.isEmpty {
                            Divider()
                            detailRow(
                                title: "Owner Email",
                                value: userEmail
                            )
                        }

                        if let updatedAt = landmark.updatedAt,
                           !updatedAt.isEmpty {
                            Divider()
                            detailRow(
                                title: "Updated At",
                                value: updatedAt
                            )
                        }
                    }
                    .padding(20)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                    .padding(.horizontal)
                }

                Spacer(minLength: 40)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Landmark Details")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isEditingDescription) {
            editDescriptionSheet
        }
        .sheet(isPresented: $isEditingWebsiteUrl) {
            editWebsiteUrlSheet
        }
        .sheet(isPresented: $isShowingDeleteLandmarkSheet) {
            deleteLandmarkSheet
        }
        .sheet(item: $promotionEditorContext) { context in
            BusinessPromotionEditor(
                landmark: landmark,
                context: context
            ) {
                Task {
                    await loadPromotions()
                }
            }
        }
        .alert("Delete Promotion?", isPresented: deletePromotionAlertBinding) {
            Button("Cancel", role: .cancel) {
                promotionPendingDelete = nil
            }

            Button("Delete", role: .destructive) {
                if let promotion = promotionPendingDelete {
                    Task {
                        await deletePromotion(promotion)
                    }
                }
            }
        } message: {
            Text("This promotion will be permanently removed.")
        }
        .task {
            await loadPromotions()
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .bold, design: .rounded))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.horizontal, 20)
    }

    // MARK: - Promotions Methods
    private func promotionRow(_ promotion: BusinessPromotion) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(promotion.name.isEmpty ? "Untitled Promotion" : promotion.name)
                        .font(.system(size: 18, weight: .bold, design: .rounded))

                    if !promotion.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(promotion.description)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.secondary)
                    }

                    Text(promotionDateSummary(promotion))
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(promotion.enabled ? Color.orange.opacity(0.15) : Color.gray.opacity(0.15))
                        .foregroundStyle(promotion.enabled ? .orange : .secondary)
                        .clipShape(Capsule())
                        .padding(.top, 4)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { promotion.enabled },
                    set: { newValue in Task { await updatePromotionEnabled(promotion, enabled: newValue) } }
                ))
                .labelsHidden()
                .tint(.orange)
                .disabled(savingPromotionIds.contains(promotion.id))
            }

            HStack(spacing: 12) {
                Button { promotionEditorContext = .edit(promotion) } label: {
                    Text("Edit").font(.system(size: 14, weight: .bold)).frame(maxWidth: .infinity).padding(.vertical, 10).background(Color(uiColor: .tertiarySystemFill)).clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(savingPromotionIds.contains(promotion.id))

                Button { promotionPendingDelete = promotion } label: {
                    Text("Delete").font(.system(size: 14, weight: .bold)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 10).background(Color.red.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(savingPromotionIds.contains(promotion.id))
            }
        }
    }

    private var deletePromotionAlertBinding: Binding<Bool> {
        Binding(get: { promotionPendingDelete != nil }, set: { newValue in if !newValue { promotionPendingDelete = nil } })
    }

    private func loadPromotions() async {
        await MainActor.run { isLoadingPromotions = true; promotionErrorMessage = nil }
        do {
            let response = try await promotionService.fetchPromotions(landmarkId: landmark.landmarkId)
            await MainActor.run { promotions = response.items; isLoadingPromotions = false }
        } catch {
            await MainActor.run { promotionErrorMessage = error.localizedDescription; isLoadingPromotions = false }
        }
    }

    private func updatePromotionEnabled(_ promotion: BusinessPromotion, enabled: Bool) async {
        await MainActor.run {
            savingPromotionIds.insert(promotion.id)
            promotionErrorMessage = nil
            if let index = promotions.firstIndex(where: { $0.id == promotion.id }) { promotions[index] = promotions[index].copy(enabled: enabled) }
        }
        do {
            let updated = try await promotionService.updatePromotion(landmarkId: landmark.landmarkId, promotionId: promotion.id, name: nil, description: nil, imageUrl: nil, startDate: nil, endDate: nil, enabled: enabled)
            await MainActor.run {
                if let index = promotions.firstIndex(where: { $0.id == promotion.id }) { promotions[index] = updated }
                savingPromotionIds.remove(promotion.id)
            }
        } catch {
            await MainActor.run {
                if let index = promotions.firstIndex(where: { $0.id == promotion.id }) { promotions[index] = promotion }
                promotionErrorMessage = error.localizedDescription
                savingPromotionIds.remove(promotion.id)
            }
        }
    }

    private func deletePromotion(_ promotion: BusinessPromotion) async {
        await MainActor.run { savingPromotionIds.insert(promotion.id); promotionErrorMessage = nil }
        do {
            try await promotionService.deletePromotion(landmarkId: landmark.landmarkId, promotionId: promotion.id)
            await MainActor.run { promotions.removeAll { $0.id == promotion.id }; promotionPendingDelete = nil; savingPromotionIds.remove(promotion.id) }
        } catch {
            await MainActor.run { promotionErrorMessage = error.localizedDescription; promotionPendingDelete = nil; savingPromotionIds.remove(promotion.id) }
        }
    }

    private func promotionDateSummary(_ promotion: BusinessPromotion) -> String {
        let start = promotion.startDate.trimmingCharacters(in: .whitespacesAndNewlines)
        let end = promotion.endDate.trimmingCharacters(in: .whitespacesAndNewlines)
        if start.isEmpty && end.isEmpty { return promotion.enabled ? "Active" : "Inactive" }
        let dateText: String
        if !start.isEmpty && !end.isEmpty { dateText = "\(start) to \(end)" }
        else if !start.isEmpty { dateText = "Starts \(start)" }
        else { dateText = "Ends \(end)" }
        return promotion.enabled ? "Active • \(dateText)" : "Inactive • \(dateText)"
    }

    // MARK: - Picker Buttons
    private var positivePickerButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            showPositivePicker = true
        } label: {
            uploadRow(
                title: "Choose Positive Media",
                subtitle: selectedMediaSubtitle(count: selectedPositiveMediaItems.count, emptyText: "Select photos or videos of this landmark."),
                systemImage: "plus.circle.fill",
                color: primaryColor
            )
        }
        .buttonStyle(.plain)
        .disabled(isUploadingMedia)
        .photosPicker(isPresented: $showPositivePicker, selection: $selectedPositiveMediaItems, maxSelectionCount: maxSelectionCount, matching: .any(of: [.images, .videos]), photoLibrary: .shared())
    }

    private var negativePickerButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            showNegativePicker = true
        } label: {
            uploadRow(
                title: "Choose Negative Examples",
                subtitle: selectedMediaSubtitle(count: selectedNegativeMediaItems.count, emptyText: "Select nearby objects that are not this landmark."),
                systemImage: "minus.circle.fill",
                color: .orange
            )
        }
        .buttonStyle(.plain)
        .disabled(isUploadingMedia)
        .photosPicker(isPresented: $showNegativePicker, selection: $selectedNegativeMediaItems, maxSelectionCount: maxSelectionCount, matching: .any(of: [.images, .videos]), photoLibrary: .shared())
    }

    private var positiveSelectionControls: some View {
        Group {
            if !selectedPositiveMediaItems.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("\(selectedPositiveMediaItems.count) positive item\(selectedPositiveMediaItems.count == 1 ? "" : "s") selected").font(.system(size: 13, weight: .bold)).foregroundColor(.secondary)
                    HStack {
                        Button {
                            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                            Task { await uploadSelectedMediaItems(items: selectedPositiveMediaItems, datasetRole: .positive) }
                        } label: {
                            Text("Submit").font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 12).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 12))
                        }.disabled(isUploadingMedia)

                        Button(role: .destructive) {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            selectedPositiveMediaItems.removeAll()
                        } label: {
                            Text("Clear").font(.system(size: 15, weight: .bold)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 12).background(Color.red.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 12))
                        }.disabled(isUploadingMedia)
                    }
                }
                .padding(.top, 8)
            }
        }
    }

    private var negativeSelectionControls: some View {
        Group {
            if !selectedNegativeMediaItems.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("\(selectedNegativeMediaItems.count) negative item\(selectedNegativeMediaItems.count == 1 ? "" : "s") selected").font(.system(size: 13, weight: .bold)).foregroundColor(.secondary)
                    HStack {
                        Button {
                            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                            Task { await uploadSelectedMediaItems(items: selectedNegativeMediaItems, datasetRole: .hardNegative) }
                        } label: {
                            Text("Submit").font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 12).background(.orange).clipShape(RoundedRectangle(cornerRadius: 12))
                        }.disabled(isUploadingMedia)

                        Button(role: .destructive) {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            selectedNegativeMediaItems.removeAll()
                        } label: {
                            Text("Clear").font(.system(size: 15, weight: .bold)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(.vertical, 12).background(Color.red.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 12))
                        }.disabled(isUploadingMedia)
                    }
                }
                .padding(.top, 8)
            }
        }
    }

    private var uploadStatusArea: some View {
        Group {
            if isUploadingMedia {
                HStack(spacing: 12) {
                    ProgressView().tint(primaryColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(uploadingText).font(.system(size: 14, weight: .bold)).foregroundColor(.primary)
                        if let uploadProgressText { Text(uploadProgressText).font(.system(size: 12, weight: .medium)).foregroundColor(.secondary) }
                    }
                }
                .padding(.top, 8)
            }

            if let uploadStatusMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                    Text(uploadStatusMessage).font(.system(size: 13, weight: .bold)).foregroundColor(.green)
                }.padding(.top, 8)
            }

            if let uploadErrorMessage {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
                    Text(uploadErrorMessage).font(.system(size: 13, weight: .bold)).foregroundColor(.orange)
                }.padding(.top, 8)
            }
        }
    }

    private var displayDescription: String {
        let cleaned = displayedShortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "No description available." : cleaned
    }

    private var editDescriptionSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Short Description")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.horizontal, 20)
                    
                    TextEditor(text: $draftShortDescription)
                        .font(.system(size: 16, weight: .medium))
                        .frame(minHeight: 160)
                        .padding(16)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal)
                        .disabled(isSavingDescription)
                    
                    Text("This description is shown to users when LookSee identifies this landmark.")
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary).padding(.horizontal, 20)

                    if let saveErrorMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
                            Text(saveErrorMessage).font(.footnote).foregroundColor(.secondary)
                        }.padding(.horizontal, 20)
                    }
                }
                .padding(.top, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Edit Description")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isEditingDescription = false }.disabled(isSavingDescription)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { saveDescription() } label: {
                        if isSavingDescription { ProgressView() } else { Text("Save").fontWeight(.bold) }
                    }.disabled(isSavingDescription || draftShortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func saveDescription() {
        guard !isSavingDescription else { return }
        let cleanedDescription = draftShortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanedDescription.isEmpty else { saveErrorMessage = "Short description cannot be empty."; return }
        isSavingDescription = true; saveErrorMessage = nil
        Task {
            do {
                let updatedLandmark = try await service.updateShortDescription(landmarkId: landmark.landmarkId, shortDescription: cleanedDescription)
                await MainActor.run {
                    displayedShortDescription = updatedLandmark.shortDescription ?? cleanedDescription
                    draftShortDescription = displayedShortDescription
                    onLandmarkUpdated(updatedLandmark)
                    isSavingDescription = false
                    isEditingDescription = false
                }
            } catch {
                await MainActor.run { saveErrorMessage = error.localizedDescription; isSavingDescription = false }
            }
        }
    }

    // MARK: - Website Editing

    private var editWebsiteUrlSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Website URL")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.horizontal, 20)

                    TextField("example.com", text: $draftWebsiteUrl)
                        .font(.system(size: 16, weight: .medium))
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .padding(16)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal)
                        .disabled(isSavingWebsiteUrl)

                    Text("Users will be able to open this website from the landmark popup. You can enter example.com or a full https:// URL. Leave it blank to clear the website.")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 20)

                    if let websiteUrlErrorMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                            Text(websiteUrlErrorMessage)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                        .padding(.horizontal, 20)
                    }
                }
                .padding(.top, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Edit Website")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isEditingWebsiteUrl = false }
                        .disabled(isSavingWebsiteUrl)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button { saveWebsiteUrl() } label: {
                        if isSavingWebsiteUrl { ProgressView() }
                        else { Text("Save").fontWeight(.bold) }
                    }
                    .disabled(isSavingWebsiteUrl)
                }
            }
        }
    }

    private func saveWebsiteUrl() {
        guard !isSavingWebsiteUrl else { return }

        let cleanedWebsiteUrl = draftWebsiteUrl.trimmingCharacters(in: .whitespacesAndNewlines)

        isSavingWebsiteUrl = true
        websiteUrlErrorMessage = nil

        Task {
            do {
                let updatedLandmark = try await service.updateWebsiteUrl(
                    landmarkId: landmark.landmarkId,
                    websiteUrl: cleanedWebsiteUrl
                )

                await MainActor.run {
                    displayedWebsiteUrl = updatedLandmark.websiteUrl?
                        .trimmingCharacters(in: .whitespacesAndNewlines) ?? cleanedWebsiteUrl

                    draftWebsiteUrl = displayedWebsiteUrl
                    onLandmarkUpdated(updatedLandmark)
                    isSavingWebsiteUrl = false
                    isEditingWebsiteUrl = false
                }
            } catch {
                await MainActor.run {
                    websiteUrlErrorMessage = error.localizedDescription
                    isSavingWebsiteUrl = false
                }
            }
        }
    }

    private func updateManagementSetting(isActive: Bool?, promotionEnabled: Bool?) {
        guard !isSavingManagement else { return }
        let previousIsActive = displayedIsActive; let previousPromotionEnabled = displayedPromotionEnabled
        if let isActive { displayedIsActive = isActive }
        if let promotionEnabled { displayedPromotionEnabled = promotionEnabled }
        isSavingManagement = true; managementErrorMessage = nil
        Task {
            do {
                let updatedLandmark = try await service.updateLandmarkSettings(landmarkId: landmark.landmarkId, isActive: isActive, promotionEnabled: promotionEnabled)
                await MainActor.run {
                    displayedIsActive = updatedLandmark.isActive ?? displayedIsActive
                    displayedPromotionEnabled = updatedLandmark.promotionEnabled ?? displayedPromotionEnabled
                    onLandmarkUpdated(updatedLandmark)
                    isSavingManagement = false
                }
            } catch {
                await MainActor.run {
                    displayedIsActive = previousIsActive; displayedPromotionEnabled = previousPromotionEnabled; managementErrorMessage = error.localizedDescription; isSavingManagement = false
                }
            }
        }
    }

    private var uploadingText: String {
        if let activeUploadRole { return "Uploading \(activeUploadRole.displayName.lowercased())..." }
        return "Uploading media..."
    }

    private func uploadSelectedMediaItems(items: [PhotosPickerItem], datasetRole: BusinessDatasetRole) async {
        guard !isUploadingMedia else { return }
        guard !items.isEmpty else { return }
        await MainActor.run {
            isUploadingMedia = true; activeUploadRole = datasetRole; uploadStatusMessage = nil; uploadErrorMessage = nil
            uploadProgressText = "Preparing \(items.count) item\(items.count == 1 ? "" : "s")..."
        }
        var completedCount = 0; var failedCount = 0; var lastSubmissionId: String?
        for index in items.indices {
            let item = items[index]
            await MainActor.run { uploadProgressText = "Uploading item \(index + 1) of \(items.count)..." }
            do {
                guard let mediaData = try await item.loadTransferable(type: Data.self) else { throw MediaSelectionError.couldNotLoadMedia }
                let contentType = item.supportedContentTypes.first ?? .data
                let mediaKind = inferMediaKind(from: contentType)
                let mimeType = contentType.preferredMIMEType ?? "application/octet-stream"
                let filename = makeUploadFilename(datasetRole: datasetRole, mediaKind: mediaKind, contentType: contentType, index: index + 1)
                let response = try await service.uploadBusinessMedia(landmarkId: landmark.landmarkId, datasetRole: datasetRole, mediaKind: mediaKind, filename: filename, contentType: mimeType, data: mediaData)
                completedCount += 1; lastSubmissionId = response.submissionId
            } catch { failedCount += 1 }
        }
        await MainActor.run {
            isUploadingMedia = false; activeUploadRole = nil; uploadProgressText = nil
            if failedCount == 0 {
                uploadStatusMessage = successSummaryMessage(datasetRole: datasetRole, completedCount: completedCount, lastSubmissionId: lastSubmissionId)
                uploadErrorMessage = nil
                switch datasetRole { case .positive: selectedPositiveMediaItems.removeAll(); case .hardNegative: selectedNegativeMediaItems.removeAll() }
            } else {
                uploadStatusMessage = completedCount > 0 ? "\(completedCount) item\(completedCount == 1 ? "" : "s") uploaded successfully." : nil
                uploadErrorMessage = "\(failedCount) item\(failedCount == 1 ? "" : "s") failed to upload. Please try again."
            }
        }
    }

    private func successSummaryMessage(datasetRole: BusinessDatasetRole, completedCount: Int, lastSubmissionId: String?) -> String {
        let base: String
        switch datasetRole { case .positive: base = "\(completedCount) positive item\(completedCount == 1 ? "" : "s") uploaded successfully."; case .hardNegative: base = "\(completedCount) negative example\(completedCount == 1 ? "" : "s") uploaded successfully." }
        if let lastSubmissionId { return "\(base) Last submission: \(lastSubmissionId)" }
        return base
    }

    private func selectedMediaSubtitle(count: Int, emptyText: String) -> String {
        if count == 0 { return emptyText }
        return "\(count) item\(count == 1 ? "" : "s") selected. Tap Submit when ready."
    }

    private func inferMediaKind(from contentType: UTType) -> BusinessMediaKind {
        if contentType.conforms(to: .movie) || contentType.conforms(to: .video) { return .video }
        return .photo
    }

    private func makeUploadFilename(datasetRole: BusinessDatasetRole, mediaKind: BusinessMediaKind, contentType: UTType, index: Int) -> String {
        let fallbackExtension = mediaKind == .video ? "mov" : "jpg"
        let fileExtension = contentType.preferredFilenameExtension ?? fallbackExtension
        let cleanedLabel = landmark.label.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: " ", with: "_").replacingOccurrences(of: "/", with: "_")
        let labelComponent = cleanedLabel.isEmpty ? landmark.landmarkId : cleanedLabel
        return "\(labelComponent)_\(datasetRole.filenameComponent)_\(index)_\(UUID().uuidString).\(fileExtension)"
    }

    private var deleteLandmarkSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Confirm Landmark Deletion")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .padding(.horizontal, 20)
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                                .font(.system(size: 18, weight: .bold, design: .rounded))

                            Text(landmark.landmarkId)
                                .font(.system(size: 13, weight: .bold, design: .monospaced))
                                .foregroundStyle(.tertiary)
                            
                            Divider().padding(.vertical, 8)
                            
                            TextField("delete landmark", text: $deleteConfirmationText)
                                .font(.system(size: 16, weight: .bold))
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled(true)
                                .disabled(isDeletingLandmark)
                        }
                        .padding(20)
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .padding(.horizontal)
                        
                        Text("To confirm, type exactly: delete landmark")
                            .font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary).padding(.horizontal, 20)
                    }

                    Button(role: .destructive) {
                        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                        Task { await deleteLandmark() }
                    } label: {
                        HStack {
                            Spacer()
                            if isDeletingLandmark { ProgressView().tint(.white) }
                            else { Text("Confirm Delete Landmark").font(.system(size: 17, weight: .bold)) }
                            Spacer()
                        }
                        .foregroundStyle(.white)
                        .padding(.vertical, 16)
                        .background(isDeleteConfirmationValid && !isDeletingLandmark ? Color.red : Color.gray.opacity(0.3))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.horizontal, 20)
                    }
                    .disabled(!isDeleteConfirmationValid || isDeletingLandmark)

                    if let deleteErrorMessage {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
                            Text(deleteErrorMessage).font(.footnote).foregroundColor(.secondary)
                        }.padding(.horizontal, 20)
                    }
                }
                .padding(.top, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Delete Landmark")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isShowingDeleteLandmarkSheet = false }.disabled(isDeletingLandmark)
                }
            }
        }
    }

    private var isDeleteConfirmationValid: Bool {
        deleteConfirmationText.trimmingCharacters(in: .whitespacesAndNewlines) == "delete landmark"
    }

    private func deleteLandmark() async {
        guard isDeleteConfirmationValid else { await MainActor.run { deleteErrorMessage = "Type exactly: delete landmark" }; return }
        await MainActor.run { isDeletingLandmark = true; deleteErrorMessage = nil }
        do {
            _ = try await service.deleteLandmark(landmarkId: landmark.landmarkId, confirmation: deleteConfirmationText)
            await MainActor.run { isDeletingLandmark = false; isShowingDeleteLandmarkSheet = false; onLandmarkDeleted(landmark.landmarkId); dismiss() }
        } catch {
            await MainActor.run { isDeletingLandmark = false; deleteErrorMessage = error.localizedDescription }
        }
    }

    private func uploadRow(title: String, subtitle: String, systemImage: String, color: Color) -> some View {
        HStack(alignment: .center, spacing: 16) {
            Image(systemName: systemImage)
                .font(.system(size: 28))
                .foregroundColor(color)

            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 16, weight: .bold)).foregroundColor(.primary)
                Text(subtitle).font(.system(size: 13, weight: .medium)).foregroundColor(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 14, weight: .bold)).foregroundColor(Color(uiColor: .tertiaryLabel))
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
            Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(.secondary)
            Spacer(minLength: 16)
            Text(value).font(.system(size: 14, weight: .bold, design: .monospaced)).multilineTextAlignment(.trailing).foregroundColor(.primary)
        }
    }
}

// Moved outside the View struct but kept private to the file
private enum MediaSelectionError: LocalizedError {
    case couldNotLoadMedia

    var errorDescription: String? {
        switch self {
        case .couldNotLoadMedia:
            return "Could not load the selected media."
        }
    }
}
