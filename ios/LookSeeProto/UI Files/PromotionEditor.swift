//
//  PromotionEditor.swift
//  LookSeeProto
//

import SwiftUI
import PhotosUI
import Amplify

// MARK: - PromotionEditor

struct PromotionEditor: View {
    var existingPromotion: PromotionPayload? = nil

    @StateObject private var promotionService = PromotionService()
    @StateObject private var landmarkService = LandmarkService()

    // Current user — resolved from Amplify on appear
    @State private var userEmail = ""

    // Form fields
    @State private var selectedLandmark: BusinessLocation? = nil
    @State private var showLandmarkPicker = false
    @State private var promoName = ""
    @State private var promoDescription = ""
    @State private var startDate = Date()
    @State private var endDate = Date()
    @State private var enabled = true
    @State var selectedItems: [PhotosPickerItem] = []
    @State private var media: [Image] = []

    // Validation
    @State private var showValidationAlert = false
    @State private var validationMessage = ""

    // Delete confirmation
    @State private var promotionToDelete: PromotionPayload? = nil
    @State private var showDeleteAlert = false

    var isEditing: Bool { existingPromotion != nil }

    var body: some View {
        VStack {
            Form {

                // MARK: - Active Promotions
                if !promotionService.promotions.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(promotionService.promotions) { promo in
                                    PromoChip(
                                        name: promo.name,
                                        business: promo.landmarkLabel,
                                        onDelete: {
                                            promotionToDelete = promo
                                            showDeleteAlert = true
                                        }
                                    )
                                }
                            }
                            .padding(.vertical, 6)
                            .padding(.horizontal, 2)
                        }
                    } header: { Text("Active Promotions") }
                    footer: { Text("Swipe to browse. Tap × to remove a promotion.") }
                    .alert("Remove Promotion?", isPresented: $showDeleteAlert, presenting: promotionToDelete) { promo in
                        Button("Cancel", role: .cancel) {}
                        Button("Remove", role: .destructive) {
                            Task {
                                await promotionService.deletePromotion(
                                    promotionId: promo.id,
                                    userEmail: userEmail
                                )
                            }
                        }
                    } message: { promo in
                        Text("\(promo.name) will be permanently removed.")
                    }
                }

                // MARK: - Promotion Details
                Section {
                    // Location picker — shows user's landmarks from LookSeeLandmarks
                    Button {
                        showLandmarkPicker = true
                    } label: {
                        HStack {
                            Text("Location")
                                .foregroundStyle(.primary)
                            Spacer()
                            if let landmark = selectedLandmark {
                                Text(landmark.label)
                                    .foregroundStyle(.secondary)
                            } else if landmarkService.isLoading {
                                ProgressView()
                            } else {
                                Text("Select a location")
                                    .foregroundStyle(.secondary)
                            }
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .confirmationDialog(
                        "Select Location",
                        isPresented: $showLandmarkPicker,
                        titleVisibility: .visible
                    ) {
                        ForEach(landmarkService.landmarks) { landmark in
                            Button(landmark.label) {
                                selectedLandmark = landmark
                            }
                        }
                        Button("Cancel", role: .cancel) {}
                    }

                    TextField(text: $promoName, prompt: Text("Promotion name")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)

                    TextField(text: $promoDescription, prompt: Text("Promotion description"), axis: .vertical) {}
                        .controlSize(.large)
                        .lineLimit(5, reservesSpace: true)

                    Toggle("Enabled", isOn: $enabled)

                } header: { Text("Promotion Details") }
                footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let error = landmarkService.errorMessage {
                            Text(error)
                                .foregroundColor(.red)
                                .font(.caption)
                        }
                        if showValidationAlert {
                            Text(validationMessage)
                                .foregroundColor(.red)
                                .font(.caption)
                        }
                        if let error = promotionService.errorMessage {
                            Text(error)
                                .foregroundColor(.red)
                                .font(.caption)
                        }
                    }
                }

                // MARK: - Promotion Dates
                Section {
                    DatePicker("Start Date", selection: $startDate, displayedComponents: [.date])
                    DatePicker("End Date", selection: $endDate, in: startDate..., displayedComponents: [.date])
                } header: { Text("Promotion Dates") }

                
                // MARK: - Media
                Section {
                    PhotosPicker(selection: $selectedItems) {
                        Text("Add media")
                    }
                    .onChange(of: selectedItems) { _, newValue in
                        media.removeAll()
                        newValue.forEach { selectedItem in
                            Task {
                                if let imageData = try? await selectedItem.loadTransferable(type: Data.self),
                                   let uiImage = UIImage(data: imageData) {
                                    media.append(Image(uiImage: uiImage))
                                }
                            }
                        }
                    }
                } header: { Text("Promotion Media") }

                MediaList(selectedItems: $selectedItems, media: $media)
            }

            // MARK: - Submit / Save
            Button(isEditing ? "Save Changes" : "Submit", role: .cancel) {
                guard validate() else { return }
                guard let landmark = selectedLandmark else { return }

                Task {
                    if let existing = existingPromotion {
                        await promotionService.updatePromotion(
                            promotionId: existing.id,
                            userEmail: userEmail,
                            landmarkId: landmark.id,
                            landmarkLabel: landmark.label,
                            name: promoName,
                            description: promoDescription,
                            startDate: startDate,
                            endDate: endDate,
                            enabled: enabled
                        )
                    } else {
                        await promotionService.createPromotion(
                            userEmail: userEmail,
                            landmarkId: landmark.id,
                            landmarkLabel: landmark.label,
                            name: promoName,
                            description: promoDescription,
                            startDate: startDate,
                            endDate: endDate,
                            enabled: enabled
                        )
                    }
                    if promotionService.errorMessage == nil {
                        resetForm()
                    }
                }
            }
            .buttonStyle(.bordered)
        }
        .onAppear {
            Task {
                await resolveUserEmail()
                if !userEmail.isEmpty {
                    async let landmarks: () = landmarkService.fetchLandmarks(userEmail: userEmail)
                    async let promotions: () = promotionService.fetchPromotions(userEmail: userEmail)
                    await landmarks
                    await promotions
                }
                prefillIfEditing()
            }
        }
    }

    // MARK: - Resolve email from Amplify

    private func resolveUserEmail() async {
        do {
            let attributes = try await Amplify.Auth.fetchUserAttributes()
            if let emailAttr = attributes.first(where: { $0.key == .email }) {
                userEmail = emailAttr.value
            }
        } catch {
            print("❌ Failed to fetch user email: \(error)")
        }
    }

    // MARK: - Reset Form

    private func resetForm() {
        selectedLandmark = nil
        promoName = ""
        promoDescription = ""
        startDate = Date()
        endDate = Date()
        enabled = true
        selectedItems = []
        media = []
        showValidationAlert = false
        validationMessage = ""
    }

    // MARK: - Prefill for Edit Mode

    private func prefillIfEditing() {
        guard let promo = existingPromotion else { return }
        // Resolve the matching Landmark object from the already-fetched list
        selectedLandmark = landmarkService.landmarks.first { $0.id == promo.landmarkId }
        promoName = promo.name
        promoDescription = promo.description
        enabled = promo.enabled

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        startDate = formatter.date(from: promo.startDate) ?? Date()
        endDate = formatter.date(from: promo.endDate) ?? Date()
    }

    // MARK: - Validation

    private func validate() -> Bool {
        var errors: [String] = []

        if selectedLandmark == nil {
            errors.append("Please select a location.")
        }
        if promoName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append("Promotion name is required.")
        }
        if promoDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append("Promotion description is required.")
        }
        if endDate < startDate {
            errors.append("End date cannot be before start date.")
        }

        if errors.isEmpty {
            showValidationAlert = false
            validationMessage = ""
            return true
        } else {
            validationMessage = errors.joined(separator: "\n")
            showValidationAlert = true
            return false
        }
    }
}

// MARK: - PromoChip (unchanged)

struct PromoChip: View {
    let name: String
    let business: String
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundStyle(.primary)
                Text(business)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Button {
                onDelete()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 16))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(20)
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color(uiColor: .separator), lineWidth: 0.5)
        )
    }
}

// MARK: - MediaList (unchanged)

struct MediaList: View {
    @Binding var selectedItems: [PhotosPickerItem]
    @Binding var media: [Image]
    var body: some View {
        if selectedItems.isEmpty {
            Text("No media selected")
        } else {
            ScrollView(.horizontal) {
                LazyHStack {
                    ForEach(0..<media.count, id: \.self) { item in
                        media[item]
                            .resizable()
                            .scaledToFit()
                            .frame(width: 300, height: 300)
                    }
                }
            }
        }
    }
}

#Preview {
    PromotionEditor()
}
