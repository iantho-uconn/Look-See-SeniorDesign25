//
//  BusinessLandmarkDetailView.swift
//  LookSeeProto
//
//  Read-only detail page for one business-owned landmark.
//

import SwiftUI

struct BusinessLandmarkDetailView: View {
    let landmark: BusinessLandmark

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                        .font(.title2.weight(.bold))

                    Text(landmark.displayDescription)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section("Management") {
                Label(
                    landmark.displayStatus,
                    systemImage: landmark.isActive == false ? "pause.circle" : "checkmark.circle"
                )

                Label(
                    landmark.displayPromotionStatus,
                    systemImage: landmark.promotionEnabled == true ? "tag.fill" : "tag"
                )
            }

            Section("Location") {
                if let latitude = landmark.latitude {
                    LabeledContent("Latitude", value: String(format: "%.6f", latitude))
                } else {
                    LabeledContent("Latitude", value: "Not available")
                }

                if let longitude = landmark.longitude {
                    LabeledContent("Longitude", value: String(format: "%.6f", longitude))
                } else {
                    LabeledContent("Longitude", value: "Not available")
                }
            }

            if let promotion = landmark.promotion,
               !promotion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Section("Promotion") {
                    Text(promotion)
                        .foregroundStyle(.primary)
                }
            }

            Section("Media Uploads") {
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
            } footer: {
                Text("Remote positive and negative media uploads will be connected in the next phase.")
            }

            Section("Identifiers") {
                LabeledContent("Landmark ID", value: landmark.landmarkId)

                if let ownerUserId = landmark.ownerUserId,
                   !ownerUserId.isEmpty {
                    LabeledContent("Owner User ID", value: ownerUserId)
                }

                if let userEmail = landmark.userEmail,
                   !userEmail.isEmpty {
                    LabeledContent("Owner Email", value: userEmail)
                }

                if let updatedAt = landmark.updatedAt,
                   !updatedAt.isEmpty {
                    LabeledContent("Updated At", value: updatedAt)
                }
            }
        }
        .navigationTitle("Landmark Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}
