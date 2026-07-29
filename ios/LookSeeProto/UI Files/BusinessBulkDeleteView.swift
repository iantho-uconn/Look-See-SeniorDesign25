//
//  BusinessBulkDeleteView.swift
//  LookSeeProto
//
//  Confirms and deletes multiple business-owned landmarks by calling the
//  existing single-landmark delete endpoint once per selected landmark.
//

import SwiftUI

struct BusinessBulkDeleteResult {
    let successfulLandmarkIds: Set<String>
    let failedLandmarks: [BusinessBulkLandmarkFailure]

    var successfulCount: Int {
        successfulLandmarkIds.count
    }
}

struct BusinessBulkDeleteView: View {
    let landmarks: [BusinessLandmark]
    let onCompleted: (BusinessBulkDeleteResult) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var confirmationText = ""
    @State private var isDeleting = false
    @State private var progressText: String?
    @State private var completedResult: BusinessBulkDeleteResult?

    private let service = BusinessLandmarkService()

    var body: some View {
        NavigationStack {
            Form {
                Section(
                    header: Text("Selected Landmarks"),
                    footer: Text(
                        "Deleting a landmark begins backend cleanup for its promotions, dataset files, and cluster mappings. This cannot be undone."
                    )
                ) {
                    HStack {
                        Label(
                            "\(landmarks.count) landmark\(landmarks.count == 1 ? "" : "s")",
                            systemImage: "trash.fill"
                        )
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.red)

                        Spacer()
                    }

                    ForEach(landmarks) { landmark in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(displayLabel(for: landmark))
                                .font(.system(size: 15, weight: .semibold))

                            Text(landmark.landmarkId)
                                .font(.system(size: 11, weight: .medium, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }

                if completedResult == nil {
                    Section(
                        header: Text("Confirmation"),
                        footer: Text("Type exactly: \(requiredConfirmationText)")
                    ) {
                        TextField(
                            requiredConfirmationText,
                            text: $confirmationText
                        )
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .disabled(isDeleting)
                    }

                    Section {
                        Button(role: .destructive) {
                            UIImpactFeedbackGenerator(style: .heavy)
                                .impactOccurred()
                            deleteLandmarks()
                        } label: {
                            HStack {
                                Spacer()

                                if isDeleting {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    Label(
                                        "Delete \(landmarks.count) Landmark\(landmarks.count == 1 ? "" : "s")",
                                        systemImage: "trash.fill"
                                    )
                                    .font(.system(size: 16, weight: .bold))
                                }

                                Spacer()
                            }
                            .foregroundStyle(.white)
                            .padding(.vertical, 8)
                            .background(
                                isConfirmationValid && !isDeleting
                                    ? Color.red
                                    : Color.gray.opacity(0.35)
                            )
                            .clipShape(
                                RoundedRectangle(
                                    cornerRadius: 12,
                                    style: .continuous
                                )
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(!isConfirmationValid || isDeleting)
                    }
                }

                if isDeleting {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()

                            VStack(alignment: .leading, spacing: 3) {
                                Text("Deleting landmarks...")
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
            }
            .navigationTitle("Delete Landmarks")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isDeleting)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(completedResult == nil ? "Cancel" : "Close") {
                        dismiss()
                    }
                    .disabled(isDeleting)
                }

                if completedResult != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            dismiss()
                        }
                        .fontWeight(.bold)
                    }
                }
            }
        }
    }

    private var requiredConfirmationText: String {
        "delete \(landmarks.count) landmark\(landmarks.count == 1 ? "" : "s")"
    }

    private var isConfirmationValid: Bool {
        confirmationText.trimmingCharacters(
            in: .whitespacesAndNewlines
        ) == requiredConfirmationText
    }

    @ViewBuilder
    private func resultSection(
        _ result: BusinessBulkDeleteResult
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
                        "Deleted \(result.successfulCount) of \(landmarks.count) landmarks"
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

    private func deleteLandmarks() {
        guard !isDeleting else {
            return
        }

        guard isConfirmationValid else {
            return
        }

        isDeleting = true
        completedResult = nil

        Task {
            var successfulLandmarkIds: Set<String> = []
            var failedLandmarks: [BusinessBulkLandmarkFailure] = []

            for (index, landmark) in landmarks.enumerated() {
                guard !Task.isCancelled else {
                    break
                }

                await MainActor.run {
                    progressText = "Landmark \(index + 1) of \(landmarks.count): \(displayLabel(for: landmark))"
                }

                do {
                    _ = try await service.deleteLandmark(
                        landmarkId: landmark.landmarkId,
                        confirmation: "delete landmark"
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

            let result = BusinessBulkDeleteResult(
                successfulLandmarkIds: successfulLandmarkIds,
                failedLandmarks: failedLandmarks
            )

            await MainActor.run {
                isDeleting = false
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
}
