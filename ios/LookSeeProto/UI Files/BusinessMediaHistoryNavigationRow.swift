//
//  BusinessMediaHistoryNavigationRow.swift
//  LookSeeProto
//

import SwiftUI

struct BusinessMediaHistoryNavigationRow: View {
    let landmarkId: String
    let landmarkLabel: String

    var body: some View {
        NavigationLink {
            BusinessMediaHistoryView(
                landmarkId: landmarkId,
                landmarkLabel: landmarkLabel
            )
        } label: {
            HStack {
                Label(
                    "Media Upload History",
                    systemImage: "clock.arrow.circlepath"
                )

                Spacer()

                Text("View")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
    }
}
