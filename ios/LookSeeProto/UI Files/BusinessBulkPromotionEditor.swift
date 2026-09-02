//
//  BusinessBulkPromotionEditor.swift
//  LookSeeProto
//
//  Creates the same promotion for multiple business-owned landmarks.
//  The existing single-landmark API is called once per selected landmark.
//

import SwiftUI

struct BusinessBulkLandmarkFailure: Identifiable, Hashable {
    let landmarkId: String
    let landmarkLabel: String
    let message: String

    var id: String {
        landmarkId
    }
}

struct BusinessBulkPromotionResult {
    let promotionName: String
    let successfulLandmarkIds: Set<String>
    let failedLandmarks: [BusinessBulkLandmarkFailure]
    let updatedLandmarks: [BusinessLandmark]

    var successfulCount: Int {
        successfulLandmarkIds.count
    }
}

struct BusinessBulkPromotionEditor: View {
    let landmarks: [BusinessLandmark]
    let onCompleted: (BusinessBulkPromotionResult) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var description = ""
    @State private var imageUrl = ""
    @State private var startDate = Date()
    @State private var endDate = Calendar.current.date(
        byAdding: .day,
        value: 30,
        to: Date()
    ) ?? Date()
    @State private var enabled = true
    @State private var enablePromotionsOnLandmarks = true

    @State private var isSaving = false
    @State private var progressText: String?
    @State private var errorMessage: String?
    @State private var completedResult: BusinessBulkPromotionResult?

    private let promotionService = BusinessPromotionService()
    private let landmarkService = BusinessLandmarkService()

    var body: some View {
        NavigationStack {
            Form {
                selectedLandmarksSection

                Section(
                    header: Text("Promotion Details"),
                    footer: Text(
                        "A separate promotion record will be created for every selected landmark."
                    )
                ) {
                    TextField("Promotion name", text: $name)
                        .autocorrectionDisabled(true)
                        .disabled(isSaving || completedResult != nil)

                    TextField(
                        "Promotion description",
                        text: $description,
                        axis: .vertical
                    )
                    .lineLimit(4, reservesSpace: true)
                    .disabled(isSaving || completedResult != nil)

                    TextField("Image URL (optional)", text: $imageUrl)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .disabled(isSaving || completedResult != nil)

                    Toggle("Promotion enabled", isOn: $enabled)
                        .disabled(isSaving || completedResult != nil)
                }

                Section(
                    header: Text("Landmark Settings"),
                    footer: Text(
                        "A promotion only appears in the scan popup when the promotion record and the landmark's Promotions Enabled setting are both on."
                    )
                ) {
                    Toggle(
                        "Turn on promotions for selected landmarks",
                        isOn: $enablePromotionsOnLandmarks
                    )
                    .disabled(isSaving || completedResult != nil)
                }

                Section(header: Text("Dates")) {
                    DatePicker(
                        "Start Date",
                        selection: $startDate,
                        displayedComponents: [.date]
                    )
                    .disabled(isSaving || completedResult != nil)

                    DatePicker(
                        "End Date",
                        selection: $endDate,
                        in: startDate...,
                        displayedComponents: [.date]
                    )
                    .disabled(isSaving || completedResult != nil)
                }

                if isSaving {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()

                            VStack(alignment: .leading, spacing: 3) {
                                Text("Applying promotion...")
                                    .font(.system(size: 15, weight: .bold))

                                if let progressText {
                                    Text(progressText)
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if let completedResult {
                    resultSection(completedResult)
                }

                if let errorMessage {
                    Section {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)

                            Text(errorMessage)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Add Promotion")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isSaving)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(completedResult == nil ? "Cancel" : "Close") {
                        dismiss()
                    }
                    .disabled(isSaving)
                }

                ToolbarItem(placement: .confirmationAction) {
                    if completedResult == nil {
                        Button {
                            savePromotion()
                        } label: {
                            if isSaving {
                                ProgressView()
                            } else {
                                Text("Apply")
                                    .fontWeight(.bold)
                            }
                        }
                        .disabled(
                            isSaving
                                || landmarks.isEmpty
                                || name.trimmingCharacters(
                                    in: .whitespacesAndNewlines
                                ).isEmpty
                        )
                    } else {
                        Button("Done") {
                            dismiss()
                        }
                        .fontWeight(.bold)
                    }
                }
            }
        }
    }

    private var selectedLandmarksSection: some View {
        Section(
            header: Text("Selected Landmarks"),
            footer: Text(
                "Selection is captured when this sheet opens, so changing the search behind it cannot change this operation."
            )
        ) {
            HStack {
                Label(
                    "\(landmarks.count) landmark\(landmarks.count == 1 ? "" : "s")",
                    systemImage: "checkmark.circle.fill"
                )
                .font(.system(size: 15, weight: .bold))

                Spacer()
            }

            ForEach(landmarks) { landmark in
                VStack(alignment: .leading, spacing: 3) {
                    Text(
                        landmark.label.isEmpty
                            ? "Untitled Landmark"
                            : landmark.label
                    )
                    .font(.system(size: 15, weight: .semibold))

                    Text(landmark.landmarkId)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
            }
        }
    }

    @ViewBuilder
    private func resultSection(
        _ result: BusinessBulkPromotionResult
    ) -> some View {
        Section(header: Text("Result")) {
            HStack(spacing: 10) {
                Image(
                    systemName: result.failedLandmarks.isEmpty
                        ? "checkmark.circle.fill"
                        : "exclamationmark.triangle.fill"
                )
                .foregroundStyle(
                    result.failedLandmarks.isEmpty
                        ? Color.green
                        : Color.orange
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text(
                        "Applied to \(result.successfulCount) of \(landmarks.count) landmarks"
                    )
                    .font(.system(size: 15, weight: .bold))

                    if !result.failedLandmarks.isEmpty {
                        Text(
                            "Failed landmarks remain selected so you can retry them."
                        )
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                    }
                }
            }

            if !result.failedLandmarks.isEmpty {
                ForEach(result.failedLandmarks) { failure in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(
                            failure.landmarkLabel.isEmpty
                                ? failure.landmarkId
                                : failure.landmarkLabel
                        )
                        .font(.system(size: 14, weight: .bold))

                        Text(failure.message)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private func savePromotion() {
        guard !isSaving else {
            return
        }

        let cleanedName = name.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let cleanedDescription = description.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let cleanedImageUrl = imageUrl.trimmingCharacters(
            in: .whitespacesAndNewlines
        )

        guard !cleanedName.isEmpty else {
            errorMessage = "Promotion name is required."
            return
        }

        guard endDate >= startDate else {
            errorMessage = "End date cannot be before the start date."
            return
        }

        isSaving = true
        errorMessage = nil
        completedResult = nil

        Task {
            var successfulLandmarkIds: Set<String> = []
            var failedLandmarks: [BusinessBulkLandmarkFailure] = []
            var updatedLandmarksById: [String: BusinessLandmark] = [:]

            for (index, landmark) in landmarks.enumerated() {
                guard !Task.isCancelled else {
                    break
                }

                await MainActor.run {
                    progressText = "Landmark \(index + 1) of \(landmarks.count): \(displayLabel(for: landmark))"
                }

                do {
                    if enablePromotionsOnLandmarks {
                        let updatedLandmark = try await landmarkService
                            .updateLandmarkSettings(
                                landmarkId: landmark.landmarkId,
                                promotionEnabled: true
                            )

                        updatedLandmarksById[landmark.landmarkId] = updatedLandmark
                    }

                    _ = try await promotionService.createPromotion(
                        landmarkId: landmark.landmarkId,
                        name: cleanedName,
                        description: cleanedDescription,
                        imageUrl: cleanedImageUrl,
                        startDate: Self.string(from: startDate),
                        endDate: Self.string(from: endDate),
                        enabled: enabled
                    )

                    successfulLandmarkIds.insert(landmark.landmarkId)
                } catch {
                    failedLandmarks.append(
                        BusinessBulkLandmarkFailure(
                            landmarkId: landmark.landmarkId,
                            landmarkLabel: displayLabel(for: landmark),
                            message: error.localizedDescription
                        )
                    )
                }
            }

            let result = BusinessBulkPromotionResult(
                promotionName: cleanedName,
                successfulLandmarkIds: successfulLandmarkIds,
                failedLandmarks: failedLandmarks,
                updatedLandmarks: Array(updatedLandmarksById.values)
            )

            await MainActor.run {
                isSaving = false
                progressText = nil
                completedResult = result
                onCompleted(result)
            }
        }
    }

    private func displayLabel(for landmark: BusinessLandmark) -> String {
        landmark.label.isEmpty
            ? "Untitled Landmark"
            : landmark.label
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
}
