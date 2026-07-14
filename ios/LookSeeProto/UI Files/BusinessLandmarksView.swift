//
//  BusinessLandmarksView.swift
//  LookSeeProto
//
//  Business user's landmark management entry point.
//

import SwiftUI

struct BusinessLandmarksView: View {
    @StateObject private var viewModel = BusinessLandmarksViewModel()

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.landmarks.isEmpty {
                loadingView
            } else if let errorMessage = viewModel.errorMessage, viewModel.landmarks.isEmpty {
                errorView(message: errorMessage)
            } else if viewModel.landmarks.isEmpty {
                emptyView
            } else {
                landmarkList
            }
        }
        .navigationTitle("My Landmarks")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if viewModel.landmarks.isEmpty {
                await viewModel.loadLandmarks()
            }
        }
        .refreshable {
            await viewModel.refresh()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task {
                        await viewModel.refresh()
                    }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(viewModel.isLoading)
            }
        }
    }

    private var landmarkList: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Section {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)

                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Section {
                ForEach(viewModel.landmarks) { landmark in
                    NavigationLink {
                        BusinessLandmarkDetailView(
                            landmark: landmark,
                            onLandmarkUpdated: { updatedLandmark in
                                viewModel.replaceLandmark(updatedLandmark)
                            },
                            onLandmarkDeleted: { landmarkId in
                                viewModel.removeLandmark(landmarkId: landmarkId)
                            }
                        )
                    } label: {
                        BusinessLandmarkRow(landmark: landmark)
                    }
                }
            } header: {
                Text("\(viewModel.landmarks.count) Landmarks")
            } footer: {
                Text("These are the landmarks currently assigned to your business account.")
            }
        }
    }

    private var loadingView: some View {
        VStack(spacing: 14) {
            ProgressView()

            Text("Loading your landmarks...")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }

    private var emptyView: some View {
        ContentUnavailableView(
            "No Landmarks Yet",
            systemImage: "mappin.slash",
            description: Text("Landmarks assigned to your business account will appear here.")
        )
    }

    private func errorView(message: String) -> some View {
        ContentUnavailableView {
            Label("Could Not Load Landmarks", systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button("Try Again") {
                Task {
                    await viewModel.refresh()
                }
            }
        }
    }
}

private struct BusinessLandmarkRow: View {
    let landmark: BusinessLandmark

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                    .font(.headline)
                    .foregroundColor(.primary)

                Spacer()

                Text(landmark.displayStatus)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(landmark.isActive == false ? .secondary : .green)
            }

            Text(landmark.displayDescription)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(2)

            HStack(spacing: 8) {
                Label(
                    landmark.displayPromotionStatus,
                    systemImage: landmark.promotionEnabled == true ? "tag.fill" : "tag"
                )
                .font(.caption)
                .foregroundColor(.secondary)

                if let latitude = landmark.latitude,
                   let longitude = landmark.longitude {
                    Text("·")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Text(String(format: "%.5f, %.5f", latitude, longitude))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
