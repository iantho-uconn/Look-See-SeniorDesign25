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
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
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
