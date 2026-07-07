//
//  BusinessLandmarkDetailView.swift
//  LookSeeProto
//
//  Read-only/detail-edit page for one business-owned landmark.
//

import SwiftUI

struct BusinessLandmarkDetailView: View {
    let landmark: BusinessLandmark
    let onLandmarkUpdated: (BusinessLandmark) -> Void

    @State private var displayedShortDescription: String
    @State private var draftShortDescription: String
    @State private var isEditingDescription = false
    @State private var isSavingDescription = false
    @State private var saveErrorMessage: String?

    private let service = BusinessLandmarkService()

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

            Section(header: Text("Management")) {
                Label(
                    landmark.displayStatus,
                    systemImage: landmark.isActive == false ? "pause.circle" : "checkmark.circle"
                )

                Label(
                    landmark.displayPromotionStatus,
                    systemImage: landmark.promotionEnabled == true ? "tag.fill" : "tag"
                )
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
                footer: Text("Remote positive and negative media uploads will be connected in the next phase.")
            ) {
                Button {
                    print("Positive media upload tapped for \(landmark.landmarkId)")
                } label: {
                    Label("Upload Positive Media", systemImage: "plus.circle")
                }
                .disabled(true)

                Button {
                    print("Negative example upload tapped for \(landmark.landmarkId)")
                } label: {
                    Label("Upload Negative Examples", systemImage: "minus.circle")
                }
                .disabled(true)
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
                    .disabled(isSavingDescription || draftShortDescription.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
