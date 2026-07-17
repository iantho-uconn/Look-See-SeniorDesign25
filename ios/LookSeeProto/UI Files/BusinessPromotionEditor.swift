//
//  BusinessPromotionEditor.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 7/13/26.
//  Add/edit sheet for business landmark promotions.
//

import SwiftUI

enum BusinessPromotionEditorContext: Identifiable {
    case create
    case edit(BusinessPromotion)

    var id: String {
        switch self {
        case .create:
            return "create"
        case .edit(let promotion):
            return promotion.id
        }
    }

    var existingPromotion: BusinessPromotion? {
        switch self {
        case .create:
            return nil
        case .edit(let promotion):
            return promotion
        }
    }

    var navigationTitle: String {
        switch self {
        case .create:
            return "Add Promotion"
        case .edit:
            return "Edit Promotion"
        }
    }

    var saveButtonTitle: String {
        switch self {
        case .create:
            return "Create"
        case .edit:
            return "Save"
        }
    }
}

struct BusinessPromotionEditor: View {
    let landmark: BusinessLandmark
    let context: BusinessPromotionEditorContext
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var description: String
    @State private var imageUrl: String
    @State private var startDate: Date
    @State private var endDate: Date
    @State private var enabled: Bool
    @State private var isSaving = false
    @State private var errorMessage: String?

    private let service = BusinessPromotionService()

    init(
        landmark: BusinessLandmark,
        context: BusinessPromotionEditorContext,
        onSaved: @escaping () -> Void
    ) {
        self.landmark = landmark
        self.context = context
        self.onSaved = onSaved

        let existing = context.existingPromotion
        let defaultStartDate = Date()
        let defaultEndDate = Calendar.current.date(byAdding: .day, value: 30, to: defaultStartDate) ?? defaultStartDate

        _name = State(initialValue: existing?.name ?? "")
        _description = State(initialValue: existing?.description ?? "")
        _imageUrl = State(initialValue: existing?.imageUrl ?? "")
        _startDate = State(initialValue: Self.date(from: existing?.startDate) ?? defaultStartDate)
        _endDate = State(initialValue: Self.date(from: existing?.endDate) ?? defaultEndDate)
        _enabled = State(initialValue: existing?.enabled ?? true)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Landmark")) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                            .font(.headline)

                        Text(landmark.landmarkId)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                }

                Section(
                    header: Text("Promotion Details"),
                    footer: Text("This promotion can be shown for this landmark when both the promotion and the landmark's Promotions Enabled setting are on.")
                ) {
                    TextField("Promotion name", text: $name)
                        .autocorrectionDisabled(true)

                    TextField("Promotion description", text: $description, axis: .vertical)
                        .lineLimit(4, reservesSpace: true)

                    TextField("Image URL (optional)", text: $imageUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)

                    Toggle("Enabled", isOn: $enabled)
                }

                Section(header: Text("Dates")) {
                    DatePicker("Start Date", selection: $startDate, displayedComponents: [.date])
                    DatePicker("End Date", selection: $endDate, in: startDate..., displayedComponents: [.date])
                }

                if let errorMessage {
                    Section {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)

                            Text(errorMessage)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(context.navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(isSaving)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        savePromotion()
                    } label: {
                        if isSaving {
                            ProgressView()
                        } else {
                            Text(context.saveButtonTitle)
                        }
                    }
                    .disabled(isSaving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func savePromotion() {
        guard !isSaving else { return }

        let cleanedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedImageUrl = imageUrl.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanedName.isEmpty else {
            errorMessage = "Promotion name is required."
            return
        }

        isSaving = true
        errorMessage = nil

        Task {
            do {
                switch context {
                case .create:
                    _ = try await service.createPromotion(
                        landmarkId: landmark.landmarkId,
                        name: cleanedName,
                        description: cleanedDescription,
                        imageUrl: cleanedImageUrl,
                        startDate: Self.string(from: startDate),
                        endDate: Self.string(from: endDate),
                        enabled: enabled
                    )

                case .edit(let promotion):
                    _ = try await service.updatePromotion(
                        landmarkId: landmark.landmarkId,
                        promotionId: promotion.id,
                        name: cleanedName,
                        description: cleanedDescription,
                        imageUrl: cleanedImageUrl,
                        startDate: Self.string(from: startDate),
                        endDate: Self.string(from: endDate),
                        enabled: enabled
                    )
                }

                await MainActor.run {
                    isSaving = false
                    onSaved()
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isSaving = false
                }
            }
        }
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    private static func string(from date: Date) -> String {
        dateFormatter.string(from: date)
    }

    private static func date(from value: String?) -> Date? {
        guard let value,
              !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        return dateFormatter.date(from: value)
    }
}
