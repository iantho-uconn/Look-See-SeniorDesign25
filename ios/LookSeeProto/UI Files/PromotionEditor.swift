//
//  PromotionEditor.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/25/26.
//
import SwiftUI
import PhotosUI

// MARK: - Promotion Model
struct Promotion: Identifiable {
    let id: UUID
    var business: String
    var name: String
    var description: String
    var startDate: Date
    var endDate: Date
    var mediaItems: [PhotosPickerItem]
    var mediaImages: [Image]

    init(
        id: UUID = UUID(),
        business: String = "",
        name: String = "",
        description: String = "",
        startDate: Date = Date(),
        endDate: Date = Date(),
        mediaItems: [PhotosPickerItem] = [],
        mediaImages: [Image] = []
    ) {
        self.id = id
        self.business = business
        self.name = name
        self.description = description
        self.startDate = startDate
        self.endDate = endDate
        self.mediaItems = mediaItems
        self.mediaImages = mediaImages
    }
}

// MARK: - PromotionEditor
struct PromotionEditor: View {
    var existingPromotion: Promotion? = nil

    @State private var savedPromotions: [Promotion] = [
        // TODO: replace with API fetch of existing promotions
        Promotion(business: "Dick's Automotive", name: "Summer Sale", description: "20% off all services.", startDate: Date(), endDate: Calendar.current.date(byAdding: .month, value: 1, to: Date()) ?? Date()),
        Promotion(business: "Jerry's Bait Shop", name: "Weekend Special", description: "Buy 2 get 1 free on lures.", startDate: Date(), endDate: Calendar.current.date(byAdding: .weekOfYear, value: 1, to: Date()) ?? Date())
    ]
    @State private var promotionToDelete: Promotion? = nil
    @State private var showDeleteAlert = false

    @State private var businesses = ["Dick's Automotive", "Jerry's Bait Shop", "Hardware Store"]
    @State private var selectedBusiness = String()
    @State private var promoName = String()
    @State private var promoDescription = String()
    @State private var startDate = Date()
    @State private var endDate = Date()
    @State var selectedItems: [PhotosPickerItem] = []
    @State private var media: [Image] = []
    @State private var submit = false

    @State private var showValidationAlert = false
    @State private var validationMessage = ""

    var isEditing: Bool { existingPromotion != nil }

    var body: some View {
        VStack {
            Form {

                // MARK: - Saved Promotions
                if !savedPromotions.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(savedPromotions) { promo in
                                    PromoChip(
                                        name: promo.name,
                                        business: promo.business,
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
                            savedPromotions.removeAll { $0.id == promo.id }
                            // TODO: DELETE /promotions/{promo.id}
                        }
                    } message: { promo in
                        Text("\(promo.name) will be permanently removed.")
                    }
                }

                // MARK: - Promotion Details
                Section {
                    Picker("Location", selection: $selectedBusiness){
                        ForEach(businesses, id: \.self){ business in Text(business) }
                    }
                    TextField(text: $promoName, prompt: Text("Promotion name")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    TextField(text: $promoDescription, prompt: Text("Promotion description"), axis: .vertical) {}
                        .controlSize(.large)
                        .lineLimit(5, reservesSpace: true)
                } header: { Text("Promotion Details") }
                footer: {
                    if showValidationAlert {
                        Text(validationMessage)
                            .foregroundColor(.red)
                            .font(.caption)
                    }
                }

                Section {
                    DatePicker(
                        "Start Date",
                        selection: $startDate,
                        displayedComponents: [.date]
                    )
                    DatePicker(
                        "End Date",
                        selection: $endDate,
                        in: startDate...,
                        displayedComponents: [.date]
                    )
                } header: { Text("Promotion Dates") }

                Section {
                    PhotosPicker(selection: $selectedItems) {
                        Text("Add media")
                    }
                    .onChange(of: selectedItems) { _, newValue in
                        media.removeAll()
                        newValue.forEach({ selectedItem in
                            Task {
                                if let imageData = try? await selectedItem.loadTransferable(type: Data.self),
                                   let uiImage = UIImage(data: imageData) {
                                    media.append(Image(uiImage: uiImage))
                                } else {
                                    print("Image Error")
                                }
                            }
                        })
                    }
                } header: { Text("Promotion Media") }

                MediaList(selectedItems: $selectedItems, media: $media)
            }

            Button(isEditing ? "Save Changes" : "Submit", role: .cancel) {
                if validate() {
                    let newPromo = Promotion(
                        business: selectedBusiness,
                        name: promoName,
                        description: promoDescription,
                        startDate: startDate,
                        endDate: endDate,
                        mediaItems: selectedItems,
                        mediaImages: media
                    )
                    savedPromotions.append(newPromo)
                    submit = true
                    // TODO: POST /promotions or PATCH /promotions/{existingPromotion.id}
                    resetForm()
                }
            }
            .buttonStyle(.bordered)
        }
        .onAppear {
            prefillIfEditing()
        }
    }

    // MARK: - Reset Form
    private func resetForm() {
        selectedBusiness = ""
        promoName = ""
        promoDescription = ""
        startDate = Date()
        endDate = Date()
        selectedItems = []
        media = []
        showValidationAlert = false
        validationMessage = ""
    }

    // MARK: - Prefill for Edit Mode
    private func prefillIfEditing() {
        guard let promo = existingPromotion else { return }
        selectedBusiness = promo.business
        promoName = promo.name
        promoDescription = promo.description
        startDate = promo.startDate
        endDate = promo.endDate
        selectedItems = promo.mediaItems
        media = promo.mediaImages
    }

    // MARK: - Validation
    private func validate() -> Bool {
        var errors: [String] = []

        if selectedBusiness.isEmpty {
            errors.append("Business location is required.")
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

// MARK: - Promo Chip
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

// MARK: - MediaList
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

#Preview("Edit Mode") {
    PromotionEditor(existingPromotion: Promotion(
        business: "Dick's Automotive",
        name: "Summer Sale",
        description: "20% off all services this summer.",
        startDate: Date(),
        endDate: Calendar.current.date(byAdding: .month, value: 1, to: Date()) ?? Date()
    ))
}
