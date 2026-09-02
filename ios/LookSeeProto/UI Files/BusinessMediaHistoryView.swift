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
        .task(id: viewModel.processingPollKey) {
            await viewModel.pollProcessingItems()
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
                    BusinessMediaHistoryRow(
                        item: item,
                        isRetrying: viewModel.isRetrying(item),
                        retryError: viewModel.retryError(for: item)
                    ) {
                        Task {
                            await viewModel.retryProcessing(item)
                        }
                    }
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
    let isRetrying: Bool
    let retryError: String?
    let onRetry: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            thumbnail

            VStack(alignment: .leading, spacing: 7) {
                Text(item.roleAndMediaTitle)
                    .font(.headline)
                    .lineLimit(2)

                statusBadge

                Label(
                    item.uploadedBy.displayText,
                    systemImage: "person.crop.circle"
                )
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(1)

                Label(uploadDateText, systemImage: "calendar")
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

                if item.isProcessingDelayed {
                    Label(
                        "Processing longer than expected",
                        systemImage: "exclamationmark.circle.fill"
                    )
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(.orange)
                }

                if item.canRetryProcessing {
                    Button(action: onRetry) {
                        HStack(spacing: 7) {
                            if isRetrying {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "arrow.clockwise.circle.fill")
                            }
                            Text(isRetrying ? "Requeueing..." : "Retry Processing")
                        }
                        .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .disabled(isRetrying)
                }

                if let retryCount = item.retryCount, retryCount > 0 {
                    Text("Processing retried \(retryCount) time\(retryCount == 1 ? "" : "s")")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                if let retryError {
                    Text(retryError)
                        .font(.caption2)
                        .foregroundColor(.red)
                }
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
        Label(item.displayStatus, systemImage: statusSystemImage)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .foregroundColor(statusColor)
            .background(statusColor.opacity(0.14))
            .clipShape(Capsule())
            .accessibilityLabel(
                "Media status: \(item.displayStatus). Backend status: \(item.backendStatusText)"
            )
    }

    private var statusColor: Color {
        switch item.lifecycleState {
        case .ready: return .green
        case .processing: return .orange
        case .failed: return .red
        case .unknown: return .secondary
        }
    }

    private var statusSystemImage: String {
        if item.isProcessingDelayed {
            return "exclamationmark.circle.fill"
        }

        switch item.lifecycleState {
        case .ready: return "checkmark.circle.fill"
        case .processing: return "clock.arrow.circlepath"
        case .failed: return "exclamationmark.triangle.fill"
        case .unknown: return "questionmark.circle"
        }
    }

    private var uploadDateText: String {
        guard let uploadDate = item.uploadDate else {
            return "Date unavailable"
        }
        return uploadDate.formatted(date: .abbreviated, time: .shortened)
    }
}
