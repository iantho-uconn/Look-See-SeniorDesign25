//
//  Historyview.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/3/26.
//


import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var vm: AuthViewModel
    @State private var isLoading = true

    var body: some View {
        Group {
            if isLoading {
                ProgressView("Loading History...")
            } else if vm.scanHistory.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "clock.badge.exclamationmark")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text("No scans yet.")
                        .font(.headline)
                    Text("Landmarks you scan will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } else {
                List(vm.scanHistory) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.landmarkLabel)
                            .font(.headline)
                        
                        HStack {
                            Image(systemName: "mappin.and.ellipse")
                                .foregroundStyle(.blue)
                            Text(item.locationString)
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                        Text(formatDate(item.scannedAt))
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .navigationTitle("Scan History")
        .task {
            await vm.fetchScanHistory()
            isLoading = false
        }
    }

    private func formatDate(_ isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: isoString) else { return isoString }
        
        let displayFormatter = DateFormatter()
        displayFormatter.dateStyle = .medium
        displayFormatter.timeStyle = .short
        return displayFormatter.string(from: date)
    }
}
