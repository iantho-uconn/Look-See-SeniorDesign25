//
//  BusinessLandmarksView.swift
//  LookSeeProto
//
//  Business user's landmark management entry point.
//


import SwiftUI

struct BusinessLandmarksView: View {
    @StateObject private var viewModel = BusinessLandmarksViewModel()
    @ObservedObject private var offlineManager = OfflineMediaManager.shared
    @ObservedObject private var uploadManager = AutoUploadManager.shared
    @ObservedObject private var networkMonitor = NetworkMonitor.shared
    
    @State private var draftToEdit: ArchivedMedia?

    var body: some View {
        List {
            Section {
                if !offlineManager.archivedItems.isEmpty {
                    syncBannerRow
                    
                    ForEach(offlineManager.archivedItems) { item in
                        Button {
                            if uploadManager.currentlyUploadingId != item.id {
                                draftToEdit = item
                            }
                        } label: {
                            pendingRow(for: item)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                offlineManager.deleteArchive(media: item)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                } else if !viewModel.landmarks.isEmpty {
                    emptyView
                }
            } header: {
                Text("Pending Uploads")
                    .font(.title3.weight(.bold))
                    .foregroundColor(.primary)
                    .textCase(nil)
                    .padding(.leading, -16)
            }

            Section {
                if viewModel.isLoading && viewModel.landmarks.isEmpty {
                    loadingView
                } else if viewModel.landmarks.isEmpty && offlineManager.archivedItems.isEmpty {
                    emptyView
                } else if viewModel.landmarks.isEmpty {
                    Text("No active business landmarks.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                } else {
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
                }
            } header: {
                HStack {
                    Text("Active Landmarks")
                        if !viewModel.landmarks.isEmpty {
                        Text("(\(viewModel.landmarks.count))")
                    }
                }
                .font(.title3.weight(.bold))
                .foregroundColor(.primary)
                .textCase(nil)
                .padding(.leading, -16)
            }
        }
        .listStyle(.insetGrouped)
        .animation(.default, value: offlineManager.archivedItems.isEmpty)
        .refreshable {
            await viewModel.refresh()
        }
        .navigationTitle("My Landmarks")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if viewModel.landmarks.isEmpty {
                await viewModel.loadLandmarks()
            }
        }
 /*       .task {
            await printCognitoTokens()
      }*/  
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await viewModel.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(viewModel.isLoading)
            }
        }
        .fullScreenCover(item: $draftToEdit) { draft in
            LandmarkRecord(archivedMedia: draft)
        }
    }

    @ViewBuilder
    private var syncBannerRow: some View {
        let isUploading = uploadManager.currentlyUploadingId != nil
        let isOffline = !networkMonitor.isConnected
        
        HStack(spacing: 12) {
            Image(systemName: isUploading ? "arrow.up.circle.fill" : (isOffline ? "icloud.slash.fill" : "pause.circle.fill"))
                .font(.title2)
                .foregroundColor(isUploading ? .blue : .gray)
            
            VStack(alignment: .leading, spacing: 2) {
                Text(isUploading ? "Syncing to Cloud..." : (isOffline ? "Waiting for Connection" : "Queue Processing..."))
                    .font(.subheadline.weight(.semibold))
                Text("\(offlineManager.archivedItems.count) items waiting to upload")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            if isUploading {
                ProgressView()
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private func pendingRow(for item: ArchivedMedia) -> some View {
        let isUploading = uploadManager.currentlyUploadingId == item.id
        
        HStack(spacing: 16) {
            ZStack {
                Color.gray.opacity(0.2)
                Image(systemName: item.isVideo ? "video.fill" : "photo.fill")
                    .foregroundColor(.secondary)
            }
            .frame(width: 48, height: 48)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 6) {
                Text(item.title)
                    .font(.headline)
                    .foregroundColor(.primary)

                if isUploading {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Uploading...")
                            .font(.caption)
                            .foregroundColor(Color.blue)
                        ProgressView(value: uploadManager.currentUploadProgress)
                            .progressViewStyle(.linear)
                            .tint(Color.blue)
                    }
                } else {
                    HStack(spacing: 4) {
                        Image(systemName: "clock.fill")
                            .font(.system(size: 10))
                        Text("Queued")
                            .font(.caption)
                    }
                    .foregroundColor(.orange)
                }
            }
            
            Spacer()
            
            if !isUploading {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.gray.opacity(0.5))
            }
        }
        .padding(.vertical, 4)
    }

    private var emptyView: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Color.gray.opacity(0.1)).frame(width: 80, height: 80)
                Image(systemName: "checkmark.icloud.fill").font(.system(size: 40)).foregroundColor(.green)
            }
            Text("All Caught Up!")
                .font(.title2.bold())
            Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 220, alignment: .center)
        .padding(.vertical, 40)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .transition(.opacity)
    }

    private var loadingView: some View {
        VStack(spacing: 14) {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .center)
            
            Text("Loading your landmarks...")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding(.vertical, 20)
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

                    if let latitude = landmark.latitude, let longitude = landmark.longitude {
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
}
