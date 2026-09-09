//
//  Historyview.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/3/26.
//

import SwiftUI

struct HistoryGroup: Identifiable {
    let id = UUID()
    let title: String
    let items: [ScanHistoryItem]
}

struct HistoryView: View {
    @EnvironmentObject var vm: AuthViewModel
    @State private var isLoading = true
    @State private var selectedItem: ScanHistoryItem? = nil

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    // Groups items chronologically while preserving DynamoDB's newest-first order
    private var historyGroups: [HistoryGroup] {
        var groups: [HistoryGroup] = []
        var currentTitle = ""
        var currentItems: [ScanHistoryItem] = []
        
        for item in vm.scanHistory {
            let title = groupTitle(for: item.scannedAt)
            if title != currentTitle {
                if !currentItems.isEmpty {
                    groups.append(HistoryGroup(title: currentTitle, items: currentItems))
                }
                currentTitle = title
                currentItems = [item]
            } else {
                currentItems.append(item)
            }
        }
        if !currentItems.isEmpty {
            groups.append(HistoryGroup(title: currentTitle, items: currentItems))
        }
        return groups
    }

    var body: some View {
        VStack(spacing: 0) {
            if isLoading {
                Spacer()
                ProgressView("Loading History...")
                Spacer()
            } else if vm.scanHistory.isEmpty {
                Spacer()
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
                Spacer()
            } else {
                List {
                    ForEach(historyGroups) { group in
                        Section(header: Text(group.title)) {
                            ForEach(group.items) { item in
                                Button {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    selectedItem = item
                                } label: {
                                    HStack(spacing: 14) {
                                        // Thumbnail
                                        if let imgUrl = item.imageUrl, let url = URL(string: imgUrl), !imgUrl.isEmpty {
                                            AsyncImage(url: url) { phase in
                                                if let image = phase.image {
                                                    image.resizable().scaledToFill()
                                                } else {
                                                    Color.gray.opacity(0.3)
                                                }
                                            }
                                            .frame(width: 50, height: 50)
                                            .clipShape(RoundedRectangle(cornerRadius: 8))
                                        } else {
                                            ZStack {
                                                Circle().fill(primaryColor.opacity(0.2))
                                                Text(String(item.landmarkLabel.prefix(1)).uppercased())
                                                    .font(.system(size: 20, weight: .bold, design: .rounded))
                                                    .foregroundStyle(primaryColor)
                                            }
                                            .frame(width: 50, height: 50)
                                        }
                                        
                                        // Text Info
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(item.landmarkLabel)
                                                .font(.headline)
                                                .foregroundStyle(.primary)
                                            
                                            HStack(spacing: 4) {
                                                Image(systemName: "mappin.and.ellipse")
                                                    .foregroundStyle(primaryColor)
                                                Text(item.locationString)
                                            }
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)

                                            Text(formatTimeOnly(item.scannedAt))
                                                .font(.caption)
                                                .foregroundStyle(.tertiary)
                                        }
                                        Spacer()
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        Task { await vm.deleteScanHistory(scannedAt: item.scannedAt) }
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
        }
        .navigationTitle("Scan History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await vm.fetchScanHistory()
            isLoading = false
        }
        .sheet(item: $selectedItem) { item in
            HistoryDetailSheet(item: item)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private func groupTitle(for isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: isoString) else { return "Unknown" }
        
        if Calendar.current.isDateInToday(date) { return "Today" }
        if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
        
        let displayFormatter = DateFormatter()
        displayFormatter.dateStyle = .medium
        return displayFormatter.string(from: date)
    }

    private func formatTimeOnly(_ isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: isoString) else { return isoString }
        
        let displayFormatter = DateFormatter()
        displayFormatter.timeStyle = .short
        return displayFormatter.string(from: date)
    }
}

// MARK: - Dedicated Popup Sheet for History Tab
struct HistoryDetailSheet: View {
    let item: ScanHistoryItem
    @Environment(\.dismiss) var dismiss
    
    // UI State for live data
    @State private var liveDescription: String = "Loading details..."
    @State private var websiteUrl: String = ""
    @State private var promoName: String = ""
    @State private var promoDescription: String = ""
    @State private var promoImageUrl: String = ""
    
    @State private var mName = ""
    @State private var mBio = ""
    @State private var mPhone = ""
    @State private var mWeb = ""
    @State private var mAddr = ""
    @State private var mLogo = ""
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    var body: some View {
        ZStack(alignment: .top) {
            Color(red: 0.12, green: 0.12, blue: 0.14).ignoresSafeArea()
            
            VStack(spacing: 0) {
                // --- HEADER SECTION ---
                VStack(alignment: .leading, spacing: 20) {
                    HStack(alignment: .center, spacing: 16) {
                        if let imgUrl = item.imageUrl, let url = URL(string: imgUrl), !imgUrl.isEmpty {
                            AsyncImage(url: url) { phase in
                                if let image = phase.image {
                                    image.resizable().scaledToFill()
                                } else {
                                    Color.gray.opacity(0.3)
                                }
                            }
                            .frame(width: 60, height: 60)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else {
                            ZStack {
                                Circle().fill(primaryColor.opacity(0.2))
                                Text(String(item.landmarkLabel.prefix(1)).uppercased())
                                    .font(.system(size: 24, weight: .bold, design: .rounded))
                                    .foregroundStyle(primaryColor)
                            }
                            .frame(width: 60, height: 60)
                        }
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.landmarkLabel)
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                                .lineLimit(2)
                                .minimumScaleFactor(0.8)
                            
                            Text("\(formatFullDate(item.scannedAt)) • \(item.locationString)")
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.6))
                        }
                        Spacer()
                    }
                    
                    Divider().background(Color.white.opacity(0.1))
                }
                .padding(.horizontal, 24)
                .padding(.top, 24)
                .padding(.bottom, 16)
                
                // --- SCROLLABLE CONTENT SECTION ---
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 24) {
                        
                        // 🚀 FIXED: Forces text to wrap vertically instead of expanding horizontally
                        Text(liveDescription)
                            .font(.system(size: 15))
                            .foregroundStyle(.white.opacity(0.9))
                            .lineSpacing(4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                        
                        // 🚀 Website Button
                        if !websiteUrl.isEmpty {
                            Button {
                                if let url = URL(string: websiteUrl) {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "safari.fill")
                                        .font(.system(size: 20))
                                    
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Visit Website")
                                            .font(.system(size: 16, weight: .bold))
                                        Text(URL(string: websiteUrl)?.host ?? websiteUrl)
                                            .font(.system(size: 12))
                                            .foregroundStyle(.white.opacity(0.7))
                                            .lineLimit(1)
                                            .truncationMode(.tail)
                                    }
                                    
                                    Spacer()
                                    Image(systemName: "arrow.up.forward.square")
                                        .font(.system(size: 18))
                                }
                                .foregroundStyle(.white)
                                .padding(16)
                                .background(Color(red: 0.35, green: 0.15, blue: 0.85)) // Matches your purple button
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                        }
                        
                        // 🚀 Promotion Card
                        if !promoName.isEmpty && promoName != "No active promotion" {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(promoName)
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundStyle(.white)
                                    .fixedSize(horizontal: false, vertical: true)
                                
                                Text(promoDescription)
                                    .font(.system(size: 14))
                                    .foregroundStyle(.white.opacity(0.8))
                                    .fixedSize(horizontal: false, vertical: true)
                                
                                if !promoImageUrl.isEmpty, let url = URL(string: promoImageUrl) {
                                    AsyncImage(url: url) { phase in
                                        if let image = phase.image {
                                            image.resizable().scaledToFill()
                                        } else {
                                            Color.white.opacity(0.1)
                                        }
                                    }
                                    // 🚀 FIXED: Strictly confines the image bounds and clips overflow
                                    .frame(maxWidth: .infinity, minHeight: 140, maxHeight: 140)
                                    .clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                    .padding(.top, 4)
                                }
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(red: 0.25, green: 0.18, blue: 0.12)) // Matches your dark brown promo box
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        
                        // 🚀 Merchant Card
                        if !mName.isEmpty {
                            MerchantCard(
                                storeName: mName,
                                logoUrl: mLogo,
                                bio: mBio,
                                phone: mPhone,
                                website: mWeb,
                                address: mAddr
                            )
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 40)
                }
            }
        }
        .task {
            await fetchPopupData()
        }
    }
    
    private func fetchPopupData() async {
        // 1. Pull the Merchant Card info from the local cache
        let cacheKey = "cached_merchant_\(item.landmarkId)"
        if let cachedData = UserDefaults.standard.dictionary(forKey: cacheKey) {
            mName = cachedData["merchantName"] as? String ?? ""
            mBio = cachedData["merchantBio"] as? String ?? ""
            mPhone = cachedData["merchantPhone"] as? String ?? ""
            mWeb = cachedData["merchantWebsite"] as? String ?? ""
            mAddr = cachedData["merchantAddress"] as? String ?? ""
            mLogo = cachedData["merchantLogoUrl"] as? String ?? ""
        }
        
        // 2. Fetch the live description, website, and promotions
        do {
            let liveInfo = try await LiveLandmarkInfoService().fetchLiveInfo(landmarkId: item.landmarkId, timeoutSeconds: 2.5)
            await MainActor.run {
                let fetchedDesc = liveInfo.shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
                self.liveDescription = fetchedDesc.isEmpty ? "No description is available for this landmark." : fetchedDesc
                
                self.websiteUrl = liveInfo.websiteUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                
                if liveInfo.isActive == true, let promotion = liveInfo.activePromotion {
                    self.promoName = promotion.name.trimmingCharacters(in: .whitespacesAndNewlines)
                    self.promoDescription = promotion.description.trimmingCharacters(in: .whitespacesAndNewlines)
                    self.promoImageUrl = promotion.imageUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                }
                
                // Fallback if cache missed but API has it
                if self.mName.isEmpty, let fetchedName = liveInfo.merchantName, !fetchedName.isEmpty {
                    self.mName = fetchedName
                    self.mBio = liveInfo.merchantBio ?? ""
                    self.mPhone = liveInfo.merchantPhone ?? ""
                    self.mWeb = liveInfo.merchantWebsite ?? ""
                    self.mAddr = liveInfo.merchantAddress ?? ""
                    self.mLogo = liveInfo.merchantLogoUrl ?? ""
                }
            }
        } catch {
            await MainActor.run {
                self.liveDescription = "No description is available for this landmark."
            }
        }
    }
    
    private func formatFullDate(_ isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: isoString) else { return isoString }
        
        let displayFormatter = DateFormatter()
        displayFormatter.dateStyle = .medium
        displayFormatter.timeStyle = .short
        return displayFormatter.string(from: date)
    }
}
