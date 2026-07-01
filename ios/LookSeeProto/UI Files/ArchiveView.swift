//
//  ArchiveView..swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/18/26.
//


import SwiftUI

struct ArchiveView: View {
    @StateObject private var offlineManager = OfflineMediaManager.shared
    @State private var selectedMedia: ArchivedMedia?
    
    // NEW: Expanded 6-way sorting menu
    enum SortOption { case dateNewest, dateOldest, nameAZ, nameZA, videoFirst, photoFirst }
    @State private var sortOption: SortOption = .dateNewest
    @State private var showFavoritesOnly = false
    
    @State private var mediaToRename: ArchivedMedia?
    @State private var newTitle: String = ""
    @State private var showRenameAlert = false
    
    private let primaryColor = Color(red: 0.11, green: 0.22, blue: 0.55)
    
    var filteredAndSortedItems: [ArchivedMedia] {
        var items = offlineManager.archivedItems
        if showFavoritesOnly { items = items.filter { $0.isFavorite == true } }
        
        switch sortOption {
        case .dateNewest: items.sort { $0.dateSaved > $1.dateSaved }
        case .dateOldest: items.sort { $0.dateSaved < $1.dateSaved }
        case .nameAZ: items.sort { $0.title < $1.title }
        case .nameZA: items.sort { $0.title > $1.title }
        case .videoFirst: items.sort { ($0.isVideo ? 0 : 1) < ($1.isVideo ? 0 : 1) }
        case .photoFirst: items.sort { ($0.isVideo ? 1 : 0) < ($1.isVideo ? 1 : 0) }
        }
        return items
    }
    
    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
            
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Offline Archive")
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                    
                    Spacer()
                    
                    Menu {
                        Toggle(isOn: $showFavoritesOnly) { Label("Favorites Only", systemImage: "heart.fill") }
                        Divider()
                        Picker("Sort By", selection: $sortOption) {
                            Text("Date (Newest First)").tag(SortOption.dateNewest)
                            Text("Date (Oldest First)").tag(SortOption.dateOldest)
                            Text("Name (A-Z)").tag(SortOption.nameAZ)
                            Text("Name (Z-A)").tag(SortOption.nameZA)
                            Text("Media (Videos First)").tag(SortOption.videoFirst)
                            Text("Media (Photos First)").tag(SortOption.photoFirst)
                        }
                    } label: { Image(systemName: "line.3.horizontal.decrease.circle").font(.title2).foregroundStyle(.white) }
                }
                .padding(.top, 80).padding(.horizontal, 24).padding(.bottom, 15)
                
                if filteredAndSortedItems.isEmpty {
                    Spacer()
                    Text("No media found.").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                    Spacer()
                } else {
                    ScrollView {
                        VStack(spacing: 20) {
                            ForEach(filteredAndSortedItems) { media in archiveCard(for: media) }
                        }.padding(.horizontal, 20).padding(.bottom, 100)
                    }
                }
            }
        }
        .sheet(item: $selectedMedia) { media in
            // NEW: Smart routing! Opens Tier 2 if the archive was made in Tier 2!
            if media.isTier2 == true {
                Tier2LandmarkRecord(archivedMedia: media)
            } else {
                LandmarkRecord(archivedMedia: media)
            }
        }
        .alert("Rename File", isPresented: $showRenameAlert) {
            TextField("Name", text: $newTitle)
            Button("Save") { if let media = mediaToRename { offlineManager.renameArchive(media: media, newTitle: newTitle) } }
            Button("Cancel", role: .cancel) {}
        }
    }
    
    private func archiveCard(for media: ArchivedMedia) -> some View {
        ZStack(alignment: .bottomLeading) {
            let imgPath = offlineManager.getThumbnailURL(for: media).path
            if let uiImage = UIImage(contentsOfFile: imgPath) {
                Image(uiImage: uiImage).resizable().scaledToFill().frame(height: 200).frame(maxWidth: .infinity).clipped()
            } else {
                Rectangle().fill(Color(red: 0.11, green: 0.11, blue: 0.16)).frame(height: 200)
            }
            
            LinearGradient(colors: [Color.black.opacity(0.8), Color.clear, Color.black.opacity(0.7)], startPoint: .bottom, endPoint: .top)
            
            VStack {
                HStack(alignment: .top) {
                    HStack(spacing: 6) {
                        Image(systemName: media.isVideo ? "video.fill" : "camera.fill")
                        Text(media.isVideo ? "VIDEO" : "PHOTO").font(.system(size: 10, weight: .bold))
                    }.padding(.horizontal, 10).padding(.vertical, 6).background(Color.black.opacity(0.6)).clipShape(Capsule()).foregroundStyle(.white)
                    
                    Spacer()
                    
                    HStack(spacing: 16) {
                        Button { offlineManager.toggleFavorite(media: media) } label: { Image(systemName: media.isFavorite == true ? "heart.fill" : "heart").font(.title2).foregroundStyle(media.isFavorite == true ? .red : .white).shadow(radius: 2) }
                        Menu {
                            Button { mediaToRename = media; newTitle = media.title; showRenameAlert = true } label: { Label("Rename", systemImage: "pencil") }
                            Button(role: .destructive) { offlineManager.deleteArchive(media: media) } label: { Label("Delete", systemImage: "trash") }
                        } label: { Image(systemName: "ellipsis.circle.fill").font(.title2).foregroundStyle(.white).symbolRenderingMode(.hierarchical).shadow(radius: 2) }
                    }
                }
                Spacer()
            }.padding(12)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(media.title).font(.title3.bold()).foregroundStyle(.white).lineLimit(1)
                Text("Saved on \(media.dateSaved.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(Color.white.opacity(0.7))
            }.padding(16)
        }
        .contentShape(RoundedRectangle(cornerRadius: 20)) // FIX: Stops ghost taps from shadows!
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .shadow(color: Color.black.opacity(0.3), radius: 8, x: 0, y: 4)
        .onTapGesture { selectedMedia = media }
    }
}

#Preview {
    ArchiveView()
}
