//
//  ArchiveView.swift
//  LookSeeProto
//

import SwiftUI
import AVKit

struct ArchiveView: View {
    @EnvironmentObject var vm: AuthViewModel
    @ObservedObject private var offlineManager = OfflineMediaManager.shared
    @ObservedObject private var autoUploader = AutoUploadManager.shared
    
    @State private var showInfoSheet = false
    @State private var selectedMedia: ArchivedMedia?
    @State private var editingMedia: ArchivedMedia?
    
    private let backgroundColor = Color(red: 0.08, green: 0.08, blue: 0.12)
    private let cardColor = Color(red: 0.12, green: 0.12, blue: 0.18)
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    var body: some View {
        NavigationStack {
            ZStack {
                backgroundColor.ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 0) {
                        statusHeader
                            .padding(.top, 16)
                        
                        if offlineManager.archivedItems.isEmpty {
                            emptyStateView.padding(.top, 80)
                        } else {
                            queueList.padding(.top, 16)
                        }
                    }
                }
                .scrollBounceBehavior(.always)
            }
            .navigationTitle("Upload Queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(backgroundColor, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: {
                        if autoUploader.isUploading { autoUploader.stopProcessing() }
                        else { Task { await autoUploader.startProcessing(authViewModel: vm) } }
                    }) {
                        Image(systemName: autoUploader.isUploading ? "pause.circle.fill" : "play.circle.fill")
                            .foregroundStyle(autoUploader.isUploading ? .orange : .green)
                            .font(.title3)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showInfoSheet = true }) {
                        Image(systemName: "questionmark.circle").foregroundStyle(.white)
                    }
                }
            }
        }
        .sheet(isPresented: $showInfoSheet) { QueueInfoSheet() }
        .sheet(item: $selectedMedia) { media in
            QueueDetailSheet(media: media, onEdit: {
                selectedMedia = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    if autoUploader.isUploading { autoUploader.stopProcessing() }
                    editingMedia = media
                }
            })
        }
        .fullScreenCover(item: $editingMedia) { media in
            LandmarkRecord(archivedMedia: media)
        }
        .onAppear {
            if !autoUploader.isUploading && !offlineManager.archivedItems.isEmpty {
                Task { await autoUploader.startProcessing(authViewModel: vm) }
            }
        }
    }
    
    private var statusHeader: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle().fill(autoUploader.isUploading ? Color.blue.opacity(0.2) : Color.gray.opacity(0.2)).frame(width: 44, height: 44)
                Image(systemName: autoUploader.isUploading ? "arrow.up.circle.fill" : "icloud.slash.fill")
                    .foregroundStyle(autoUploader.isUploading ? .blue : .gray).font(.system(size: 22))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(autoUploader.isUploading ? "Syncing to Cloud..." : "Queue Paused").font(.headline).foregroundStyle(.white)
                Text("\(offlineManager.archivedItems.count) items waiting to upload").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            if autoUploader.isUploading { ProgressView().tint(.white).padding(.trailing, 8) }
        }
        .padding(.vertical, 16).padding(.horizontal, 20).background(cardColor)
        .overlay(Rectangle().frame(height: 1).foregroundColor(Color.white.opacity(0.05)), alignment: .bottom)
    }
    
    private var queueList: some View {
        LazyVStack(spacing: 16) {
            ForEach(offlineManager.archivedItems) { media in
                queueItemCard(for: media)
            }
        }.padding(.horizontal).padding(.bottom, 80)
    }
    
    private func queueItemCard(for media: ArchivedMedia) -> some View {
        let isCurrentlyUploading = (autoUploader.currentlyUploadingId == media.id)
        return Button(action: {
            selectedMedia = media
        }) {
            HStack(spacing: 16) {
                ZStack {
                    if media.isVideo { Color.black; Image(systemName: "video.fill").foregroundStyle(.white) }
                    else {
                        if let image = UIImage(contentsOfFile: offlineManager.getFileURL(for: media).path) { Image(uiImage: image).resizable().scaledToFill() }
                        else { Color.gray; Image(systemName: "photo.fill") }
                    }
                }.frame(width: 70, height: 70).clipShape(RoundedRectangle(cornerRadius: 12))
                
                VStack(alignment: .leading, spacing: 6) {
                    Text(media.savedLabel ?? "Untitled Landmark").font(.headline).foregroundStyle(.white).lineLimit(1)
                    if isCurrentlyUploading {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Uploading...").font(.caption).bold().foregroundStyle(.blue)
                            ProgressView(value: autoUploader.currentUploadProgress, total: 1.0).tint(.blue)
                        }
                    } else {
                        HStack(spacing: 4) { Image(systemName: "clock.fill").foregroundStyle(.orange); Text("Queued").foregroundStyle(.orange) }.font(.caption.bold())
                    }
                }
                Spacer()
                if !isCurrentlyUploading {
                    Button(role: .destructive) { offlineManager.deleteArchive(media: media) }
                    label: { Image(systemName: "trash.circle.fill").font(.title2).foregroundStyle(Color.red.opacity(0.8)) }
                }
            }.padding(12).background(cardColor).clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(isCurrentlyUploading ? Color.blue.opacity(0.5) : Color.white.opacity(0.1), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
    
    private var emptyStateView: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle().fill(cardColor).frame(width: 100, height: 100)
                Image(systemName: "checkmark.icloud.fill").font(.system(size: 44)).foregroundStyle(.green)
            }
            Text("All Caught Up!").font(.title2.bold()).foregroundStyle(.white)
            Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 40)
        }
    }
}

struct QueueInfoSheet: View {
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.11, green: 0.11, blue: 0.16).ignoresSafeArea()
                VStack(spacing: 24) {
                    ZStack {
                        Circle().fill(Color.blue.opacity(0.15)).frame(width: 80, height: 80)
                        Image(systemName: "arrow.up.right.and.arrow.down.left.rectangle.fill").font(.system(size: 34)).foregroundStyle(.blue)
                    }.padding(.top, 30)
                    Text("How the Queue Works").font(.title2.bold()).foregroundStyle(.white)
                    VStack(alignment: .leading, spacing: 20) {
                        infoRow(icon: "wifi.slash", title: "Offline Ready", desc: "If you lose connection while recording, your landmarks are securely saved here automatically.")
                        infoRow(icon: "arrow.up.circle.fill", title: "Background Sync", desc: "The app actively watches your connection and uploads queued media in the background as soon as service returns.")
                        infoRow(icon: "battery.100.bolt", title: "Safe Storage", desc: "Media stays on your device until it is verified by the LookSee cloud, preventing data loss.")
                    }.padding(.horizontal, 20)
                    Spacer()
                    Button { dismiss() } label: {
                        Text("Got It").font(.headline).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 16).background(Color.blue).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 24).padding(.bottom, 20)
                    }
                }
            }
            .navigationTitle("About Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Done") { dismiss() }.foregroundStyle(.blue) } }
        }.presentationDetents([.medium, .large])
    }
    private func infoRow(icon: String, title: String, desc: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: icon).font(.title2).foregroundStyle(.blue).frame(width: 30)
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline).foregroundStyle(.white); Text(desc).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

struct QueueDetailSheet: View {
    let media: ArchivedMedia
    let onEdit: () -> Void
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.11, green: 0.11, blue: 0.16).ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Label").font(.caption).foregroundStyle(.secondary)
                            Text(media.savedLabel ?? "No Label").font(.title3.bold()).foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Description").font(.caption).foregroundStyle(.secondary)
                            Text(media.savedDescription ?? "No Description").font(.body).foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Location Coordinates").font(.caption).foregroundStyle(.secondary)
                            Text("\(media.latitude), \(media.longitude)").font(.body).foregroundStyle(.white)
                        }
                        
                        Button {
                            onEdit()
                        } label: {
                            Text("Edit Landmark")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color.blue)
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                        }
                        .padding(.top, 16)
                        
                    }
                    .padding(24)
                }
            }
            .navigationTitle("Submission Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }.foregroundStyle(.blue)
                }
            }
            .environment(\.colorScheme, .dark)
        }
        .presentationDetents([.medium, .large])
    }
}
