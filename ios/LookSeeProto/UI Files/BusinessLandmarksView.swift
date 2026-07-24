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
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                
                // MARK: - Pending Uploads
                if !offlineManager.archivedItems.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Pending Uploads")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .padding(.horizontal, 20)
                        
                        VStack(spacing: 0) {
                            syncBannerRow
                            Divider()
                            
                            ForEach(offlineManager.archivedItems) { item in
                                pendingRow(for: item)
                                if item.id != offlineManager.archivedItems.last?.id {
                                    Divider().padding(.leading, 64)
                                }
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                        .padding(.horizontal)
                    }
                } else if !viewModel.landmarks.isEmpty {
                    emptyQueueCard
                }

                // MARK: - Active Landmarks
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Active Landmarks")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                        if !viewModel.landmarks.isEmpty {
                            Text("(\(viewModel.landmarks.count))")
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 20)
                    
                    if viewModel.isLoading && viewModel.landmarks.isEmpty {
                        loadingView
                    } else if viewModel.landmarks.isEmpty && offlineManager.archivedItems.isEmpty {
                        emptyQueueCard
                    } else if viewModel.landmarks.isEmpty {
                        Text("No active business landmarks.")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 20)
                    } else {
                        VStack(spacing: 12) {
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
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal)
                    }
                }
                Spacer(minLength: 40)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .animation(.default, value: offlineManager.archivedItems.isEmpty)
        .refreshable { await viewModel.refresh() }
        .navigationTitle("My Landmarks")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if viewModel.landmarks.isEmpty {
                await viewModel.loadLandmarks()
            }
        }
        .task {
            await printCognitoTokens()
      }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    Task { await viewModel.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 16, weight: .bold))
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
        
        HStack(spacing: 16) {
            Image(systemName: isUploading ? "arrow.up.circle.fill" : (isOffline ? "icloud.slash.fill" : "pause.circle.fill"))
                .font(.system(size: 24))
                .foregroundColor(isUploading ? primaryColor : .gray)
            
            VStack(alignment: .leading, spacing: 2) {
                Text(isUploading ? "Syncing to Cloud..." : (isOffline ? "Waiting for Connection" : "Queue Processing..."))
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("\(offlineManager.archivedItems.count) items waiting to upload")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            if isUploading { ProgressView().tint(primaryColor) }
        }
        .padding(20)
        .background(isUploading ? primaryColor.opacity(0.05) : Color.clear)
    }

    @ViewBuilder
    private func pendingRow(for item: ArchivedMedia) -> some View {
        let isUploading = uploadManager.currentlyUploadingId == item.id
        
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if !isUploading { draftToEdit = item }
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Color(uiColor: .tertiarySystemFill)
                    Image(systemName: item.isVideo ? "video.fill" : "photo.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.primary)
                }
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundColor(.primary)

                    if isUploading {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Uploading...")
                                .font(.system(size: 12, weight: .bold, design: .rounded))
                                .foregroundColor(primaryColor)
                            ProgressView(value: uploadManager.currentUploadProgress)
                                .progressViewStyle(.linear)
                                .tint(primaryColor)
                        }
                    } else {
                        HStack(spacing: 4) {
                            Image(systemName: "clock.fill").font(.system(size: 10))
                            Text("Queued").font(.system(size: 12, weight: .bold, design: .rounded))
                        }
                        .foregroundColor(.orange)
                    }
                }
                
                Spacer()
                
                if !isUploading {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        offlineManager.deleteArchive(media: item)
                    } label: {
                        Image(systemName: "trash.fill")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.red)
                            .frame(width: 36, height: 36)
                            .background(Color.red.opacity(0.1))
                            .clipShape(Circle())
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
    }

    private var emptyQueueCard: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Color.green.opacity(0.1)).frame(width: 70, height: 70)
                Image(systemName: "checkmark.icloud.fill").font(.system(size: 32)).foregroundColor(.green)
            }
            VStack(spacing: 4) {
                Text("All Caught Up!")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(30)
        .frame(maxWidth: .infinity)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal)
    }

    private var loadingView: some View {
        VStack(spacing: 14) {
            ProgressView().tint(primaryColor)
            Text("Loading your landmarks...")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    private struct BusinessLandmarkRow: View {
        let landmark: BusinessLandmark

        var body: some View {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.primary)

                    Spacer()

                    // Sleek Status Pill
                    Text(landmark.displayStatus)
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .textCase(.uppercase)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(landmark.isActive == false ? Color.gray.opacity(0.15) : Color.green.opacity(0.15))
                        .foregroundColor(landmark.isActive == false ? .secondary : .green)
                        .clipShape(Capsule())
                }

                Text(landmark.displayDescription)
                    .font(.system(size: 14, weight: .regular))
                    .foregroundColor(.secondary)
                    .lineLimit(2)

                HStack(spacing: 12) {
                    if landmark.promotionEnabled == true {
                        HStack(spacing: 4) {
                            Image(systemName: "tag.fill")
                            Text("Promotions On")
                        }
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.orange.opacity(0.15))
                        .foregroundStyle(.orange)
                        .clipShape(Capsule())
                    }

                    if let latitude = landmark.latitude, let longitude = landmark.longitude {
                        HStack(spacing: 4) {
                            Image(systemName: "location.fill")
                            Text(String(format: "%.4f, %.4f", latitude, longitude))
                        }
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(.tertiary)
                    }
                }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
        }
    }
}
