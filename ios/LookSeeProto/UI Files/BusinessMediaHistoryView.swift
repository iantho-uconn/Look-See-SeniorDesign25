//
//  BusinessMediaHistoryView.swift
//  LookSeeProto
//
//  Displays upload history for one business-owned landmark.
//

import SwiftUI

struct BusinessMediaHistoryView: View {
    @StateObject private var viewModel: BusinessMediaHistoryViewModel

    init(
        landmarkId: String,
        landmarkLabel: String
    ) {
        _viewModel = StateObject(
            wrappedValue: BusinessMediaHistoryViewModel(
                landmarkId: landmarkId,
                landmarkLabel: landmarkLabel
            )
        )
    }

    var body: some View {
        Group {
            if viewModel.isLoadingInitial && viewModel.items.isEmpty {
                initialLoadingView
            } else if viewModel.items.isEmpty {
                emptyOrErrorView
            } else {
                historyList
            }
        }
        .navigationTitle("Media History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.loadInitial()
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Task {
                        await viewModel.refresh()
                    }
                } label: {
                    if viewModel.isRefreshing {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(
                    viewModel.isRefreshing ||
                    viewModel.isLoadingInitial ||
                    viewModel.isLoadingMore
                )
                .accessibilityLabel("Refresh media history")
            }
        }
    }

    private var historyList: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 5) {
                    Text(viewModel.landmarkLabel)
                        .font(.headline)

                    Text("\(viewModel.items.count) upload\(viewModel.items.count == 1 ? "" : "s") loaded")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 2)
            }

            Section(header: Text("Uploads")) {
                ForEach(viewModel.items) { item in
                    BusinessMediaHistoryRow(item: item)
                }

                if viewModel.hasMoreItems {
                    Button {
                        Task {
                            await viewModel.loadMore()
                        }
                    } label: {
                        HStack {
                            Spacer()

                            if viewModel.isLoadingMore {
                                ProgressView()
                                Text("Loading more...")
                            } else {
                                Label(
                                    "Load More",
                                    systemImage: "arrow.down.circle"
                                )
                            }

                            Spacer()
                        }
                    }
                    .disabled(viewModel.isLoadingMore)
                }
            }

            if let errorMessage = viewModel.errorMessage {
                Section {
                    errorMessageView(errorMessage)
                }
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            await viewModel.refresh()
        }
    }

    private var initialLoadingView: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("Loading media history...")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyOrErrorView: some View {
        VStack(spacing: 16) {
            Image(
                systemName: viewModel.errorMessage == nil
                    ? "photo.on.rectangle.angled"
                    : "exclamationmark.triangle"
            )
            .font(.system(size: 42))
            .foregroundColor(.secondary)

            Text(
                viewModel.errorMessage == nil
                    ? "No Upload History"
                    : "Couldn’t Load History"
            )
            .font(.title3.weight(.semibold))

            Text(
                viewModel.errorMessage
                    ?? "New positive and negative uploads for this landmark will appear here."
            )
            .font(.subheadline)
            .foregroundColor(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 28)

            if viewModel.errorMessage != nil {
                Button("Try Again") {
                    Task {
                        await viewModel.retry()
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func errorMessageView(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(
                "Some history could not be loaded",
                systemImage: "exclamationmark.triangle.fill"
            )
            .foregroundColor(.orange)

            Text(message)
                .font(.footnote)
                .foregroundColor(.secondary)

            Button("Retry") {
                Task {
                    await viewModel.retry()
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct BusinessMediaHistoryRow: View {
    let item: BusinessMediaHistoryItem

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            thumbnail

            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.roleAndMediaTitle)
                        .font(.headline)

                    Spacer(minLength: 8)

                    statusBadge
                }

                Label(
                    item.uploadedBy.displayText,
                    systemImage: "person.crop.circle"
                )
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(1)

                Label(
                    item.uploadDate.formatted(
                        date: .abbreviated,
                        time: .shortened
                    ),
                    systemImage: "calendar"
                )
                .font(.subheadline)
                .foregroundColor(.secondary)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Submission ID")
                        .font(.caption2)
                        .foregroundColor(.secondary)

                    Text(item.submissionId)
                        .font(.caption.monospaced())
                        .foregroundColor(.primary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                }

                Text(item.displayFilename)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(.vertical, 7)
    }

    private var thumbnail: some View {
        Group {
            if let thumbnailUrl = item.thumbnailUrl {
                AsyncImage(url: thumbnailUrl) { phase in
                    switch phase {
                    case .empty:
                        thumbnailPlaceholder(showProgress: true)
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        thumbnailPlaceholder(showProgress: false)
                    @unknown default:
                        thumbnailPlaceholder(showProgress: false)
                    }
                }
            } else {
                thumbnailPlaceholder(showProgress: false)
            }
        }
        .frame(width: 88, height: 72)
        .background(Color.secondary.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(alignment: .bottomTrailing) {
            if item.isVideo {
                Image(systemName: "video.fill")
                    .font(.caption2)
                    .padding(5)
                    .foregroundColor(.white)
                    .background(Color.black.opacity(0.65))
                    .clipShape(Circle())
                    .padding(5)
            }
        }
    }

    private func thumbnailPlaceholder(showProgress: Bool) -> some View {
        ZStack {
            Color.secondary.opacity(0.10)

            if showProgress {
                ProgressView()
            } else {
                Image(systemName: item.mediaSystemImage)
                    .font(.title2)
                    .foregroundColor(.secondary)
            }
        }
    }

    private var statusBadge: some View {
        Text(item.normalizedStatus)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .foregroundColor(statusColor)
            .background(statusColor.opacity(0.14))
            .clipShape(Capsule())
    }

    private var statusColor: Color {
        switch item.normalizedStatus.lowercased() {
        case "ready", "complete", "completed":
            return .green
        case "processing", "upload pending", "initiated":
            return .orange
        case "failed", "error":
            return .red
        default:
            return .secondary
        }
    }
}
