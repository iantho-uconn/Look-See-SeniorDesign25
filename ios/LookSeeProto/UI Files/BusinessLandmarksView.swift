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
    @State private var searchText = ""
    @State private var promotionTitlesByLandmarkId: [String: [String]] = [:]
    @State private var isIndexingPromotionTitles = false

    // Selection is stored by landmark ID rather than by the filtered list.
    // That keeps selections intact while search results change.
    @State private var isSelectionMode = false
    @State private var selectedLandmarkIds: Set<String> = []
    @State private var bulkPromotionSelection: BulkLandmarkSelection?
    @State private var bulkDeleteSelection: BulkLandmarkSelection?

    private let promotionService = BusinessPromotionService()
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
               
                // MARK: - Active Landmarks
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Active Landmarks")
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)

                        if !viewModel.landmarks.isEmpty {
                            Text(landmarkCountText)
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        if isIndexingPromotionTitles && !cleanedSearchText.isEmpty {
                            ProgressView()
                                .controlSize(.small)
                                .tint(primaryColor)
                        }
                    }
                    .padding(.horizontal, 20)

                    if isIndexingPromotionTitles && !cleanedSearchText.isEmpty {
                        Text("Checking promotion titles...")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 20)
                    }

                    if isSelectionMode {
                        selectionSummaryCard
                    }

                    if viewModel.isLoading && viewModel.landmarks.isEmpty {
                        loadingView
                    } else if viewModel.landmarks.isEmpty {
                        Text("No active business landmarks.")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 20)
                    } else if displayedLandmarks.isEmpty {
                        noSearchResultsView
                    } else {
                        VStack(spacing: 12) {
                            ForEach(displayedLandmarks) { landmark in
                                if isSelectionMode {
                                    let isSelected = selectedLandmarkIds.contains(landmark.landmarkId)
                                    let accessibilityText = isSelected ? "Deselect \(landmark.label)" : "Select \(landmark.label)"
                                    
                                    Button {
                                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                        toggleSelection(for: landmark)
                                    } label: {
                                        BusinessLandmarkRow(
                                            landmark: landmark,
                                            matchedPromotionTitle: matchedPromotionTitle(for: landmark),
                                            isSelectionMode: true,
                                            isSelected: isSelected
                                        )
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(accessibilityText)
                                } else {
                                    NavigationLink {
                                        BusinessLandmarkDetailView(
                                            landmark: landmark,
                                            onLandmarkUpdated: { updatedLandmark in
                                                viewModel.replaceLandmark(updatedLandmark)
                                            },
                                            onLandmarkDeleted: { landmarkId in
                                                viewModel.removeLandmark(landmarkId: landmarkId)
                                                promotionTitlesByLandmarkId.removeValue(forKey: landmarkId)
                                                selectedLandmarkIds.remove(landmarkId)
                                            },
                                            onPromotionTitlesChanged: { landmarkId, titles in
                                                promotionTitlesByLandmarkId[landmarkId] = titles
                                            }
                                        )
                                    } label: {
                                        BusinessLandmarkRow(
                                            landmark: landmark,
                                            matchedPromotionTitle: matchedPromotionTitle(for: landmark),
                                            isSelectionMode: false,
                                            isSelected: false
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }

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
                } else {
                    emptyQueueCard
                }

                Spacer(minLength: 40)
            }
            .padding(.top, 16)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .animation(.default, value: offlineManager.archivedItems.isEmpty)
        .refreshable {
            await refreshLandmarksAndSearchIndex()
        }
        .navigationTitle("My Landmarks")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(
            text: $searchText,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: String(localized: "Search labels or promotion titles")
        )
        .task {
            if viewModel.landmarks.isEmpty {
                await viewModel.loadLandmarks()
            }
            await loadPromotionSearchIndex()
        }
        .task {
            await printCognitoTokens()
        }
        .onChange(of: viewModel.landmarks.map(\.landmarkId)) { _, validIds in
            // Keep selection valid after refreshes or single-landmark deletion,
            // without clearing IDs merely because search results changed.
            selectedLandmarkIds.formIntersection(Set(validIds))
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if isSelectionMode {
                    selectionActionsMenu

                    Button("Done") {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        endSelectionMode()
                    }
                    .fontWeight(.bold)
                } else {
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        Task {
                            await refreshLandmarksAndSearchIndex()
                        }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 16, weight: .bold))
                    }
                    .disabled(viewModel.isLoading)

                    Button("Select") {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        isSelectionMode = true
                    }
                    .fontWeight(.bold)
                    .disabled(viewModel.landmarks.isEmpty)
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isSelectionMode {
                selectionBottomBar
            }
        }
        .fullScreenCover(item: $draftToEdit) { draft in
            LandmarkRecord(archivedMedia: draft)
        }
        .sheet(item: $bulkPromotionSelection) { selection in
            BusinessBulkPromotionEditor(
                landmarks: selection.landmarks
            ) { result in
                handleBulkPromotionResult(result)
            }
        }
        .sheet(item: $bulkDeleteSelection) { selection in
            BusinessBulkDeleteView(
                landmarks: selection.landmarks
            ) { result in
                handleBulkDeleteResult(result)
            }
        }
    }

    // MARK: - Cognito Token Helper
    private func printCognitoTokens() async {
        // Placeholder implementation to satisfy scope requirements
    }

    // MARK: - Selection

    private var selectedLandmarks: [BusinessLandmark] {
        viewModel.landmarks.filter {
            selectedLandmarkIds.contains($0.landmarkId)
        }
    }

    private var visibleLandmarkIds: Set<String> {
        Set(displayedLandmarks.map(\.landmarkId))
    }

    private var visibleSelectedCount: Int {
        selectedLandmarkIds.intersection(visibleLandmarkIds).count
    }

    private var hiddenSelectedCount: Int {
        selectedLandmarkIds.subtracting(visibleLandmarkIds).count
    }

    private var selectionCountText: String {
        let count = selectedLandmarks.count
        return "\(count) landmark\(count == 1 ? "" : "s") selected"
    }

    private var selectionSummaryCard: some View {
        HStack(spacing: 12) {
            Image(systemName: selectedLandmarkIds.isEmpty
                  ? "checkmark.circle"
                  : "checkmark.circle.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(primaryColor)

            VStack(alignment: .leading, spacing: 3) {
                Text(selectionCountText)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(.primary)

                if hiddenSelectedCount > 0 {
                    Text(
                        "\(hiddenSelectedCount) selected landmark\(hiddenSelectedCount == 1 ? "" : "s") hidden by the current search"
                    )
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)
                } else if !cleanedSearchText.isEmpty {
                    Text("\(visibleSelectedCount) selected in these search results")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                } else {
                    Text("Search for more landmarks without losing this selection.")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding(14)
        .background(primaryColor.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(primaryColor.opacity(0.20), lineWidth: 1)
        }
        .padding(.horizontal)
    }

    private var selectionActionsMenu: some View {
        Menu {
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                selectVisibleLandmarks()
            } label: {
                Label(
                    "Select Visible (\(displayedLandmarks.count))",
                    systemImage: "checkmark.circle"
                )
            }
            .disabled(displayedLandmarks.isEmpty)

            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                deselectVisibleLandmarks()
            } label: {
                Label(
                    "Deselect Visible (\(visibleSelectedCount))",
                    systemImage: "circle"
                )
            }
            .disabled(visibleSelectedCount == 0)

            Divider()

            Button(role: .destructive) {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                selectedLandmarkIds.removeAll()
            } label: {
                Label("Clear All Selection", systemImage: "xmark.circle")
            }
            .disabled(selectedLandmarkIds.isEmpty)
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 17, weight: .bold))
        }
        .accessibilityLabel("Selection actions")
    }

    private var selectionBottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(selectionCountText)
                        .font(.system(size: 15, weight: .bold, design: .rounded))

                    if hiddenSelectedCount > 0 {
                        Text("\(hiddenSelectedCount) hidden by search")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Selection stays active while searching")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Button("Clear") {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    selectedLandmarkIds.removeAll()
                }
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .foregroundStyle(.red)
                .disabled(selectedLandmarkIds.isEmpty)
            }

            HStack(spacing: 12) {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    beginBulkPromotion()
                } label: {
                    Label("Add Promotion", systemImage: "tag.fill")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(primaryColor)
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 14,
                                style: .continuous
                            )
                        )
                }
                .buttonStyle(.plain)
                .disabled(selectedLandmarkIds.isEmpty)
                .opacity(selectedLandmarkIds.isEmpty ? 0.45 : 1)

                Button(role: .destructive) {
                    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                    beginBulkDelete()
                } label: {
                    Label("Delete", systemImage: "trash.fill")
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.red.opacity(0.12))
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 14,
                                style: .continuous
                            )
                        )
                }
                .buttonStyle(.plain)
                .disabled(selectedLandmarkIds.isEmpty)
                .opacity(selectedLandmarkIds.isEmpty ? 0.45 : 1)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 10)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Divider()
        }
    }

    private func toggleSelection(for landmark: BusinessLandmark) {
        if selectedLandmarkIds.contains(landmark.landmarkId) {
            selectedLandmarkIds.remove(landmark.landmarkId)
        } else {
            selectedLandmarkIds.insert(landmark.landmarkId)
        }
    }

    private func selectVisibleLandmarks() {
        selectedLandmarkIds.formUnion(visibleLandmarkIds)
    }

    private func deselectVisibleLandmarks() {
        selectedLandmarkIds.subtract(visibleLandmarkIds)
    }

    private func endSelectionMode() {
        isSelectionMode = false
        selectedLandmarkIds.removeAll()
    }

    private func beginBulkPromotion() {
        let snapshot = selectedLandmarks

        guard !snapshot.isEmpty else {
            return
        }

        bulkPromotionSelection = BulkLandmarkSelection(
            landmarks: snapshot
        )
    }

    private func beginBulkDelete() {
        let snapshot = selectedLandmarks

        guard !snapshot.isEmpty else {
            return
        }

        bulkDeleteSelection = BulkLandmarkSelection(
            landmarks: snapshot
        )
    }

    @MainActor
    private func handleBulkPromotionResult(
        _ result: BusinessBulkPromotionResult
    ) {
        viewModel.replaceLandmarks(result.updatedLandmarks)

        for landmarkId in result.successfulLandmarkIds {
            var titles = promotionTitlesByLandmarkId[landmarkId] ?? []

            if !titles.contains(where: {
                $0.caseInsensitiveCompare(result.promotionName) == .orderedSame
            }) {
                titles.append(result.promotionName)
            }

            promotionTitlesByLandmarkId[landmarkId] = titles
        }

        let failedIds = Set(
            result.failedLandmarks.map(\.landmarkId)
        )

        selectedLandmarkIds = failedIds

        if failedIds.isEmpty {
            isSelectionMode = false
        }
    }

    @MainActor
    private func handleBulkDeleteResult(
        _ result: BusinessBulkDeleteResult
    ) {
        viewModel.removeLandmarks(
            landmarkIds: result.successfulLandmarkIds
        )

        for landmarkId in result.successfulLandmarkIds {
            promotionTitlesByLandmarkId.removeValue(
                forKey: landmarkId
            )
        }

        let failedIds = Set(
            result.failedLandmarks.map(\.landmarkId)
        )

        selectedLandmarkIds = failedIds

        if failedIds.isEmpty {
            isSelectionMode = false
        }
    }

    private var cleanedSearchText: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var landmarkCountText: String {
        guard !cleanedSearchText.isEmpty else {
            return "(\(viewModel.landmarks.count))"
        }
        return "(\(displayedLandmarks.count) of \(viewModel.landmarks.count))"
    }

    private var displayedLandmarks: [BusinessLandmark] {
        let query = cleanedSearchText

        guard !query.isEmpty else {
            return viewModel.landmarks
        }

        let matches: [(landmark: BusinessLandmark, priority: Int)] = viewModel.landmarks.compactMap { landmark in
            if landmark.label.localizedCaseInsensitiveContains(query) {
                return (landmark, 0)
            }

            if searchablePromotionTitles(for: landmark).contains(where: {
                $0.localizedCaseInsensitiveContains(query)
            }) {
                return (landmark, 1)
            }
            return nil
        }

        return matches
            .sorted { lhs, rhs in
                if lhs.priority != rhs.priority {
                    return lhs.priority < rhs.priority
                }
                return lhs.landmark.label.localizedCaseInsensitiveCompare(rhs.landmark.label) == .orderedAscending
            }
            .map { $0.landmark }
    }

    private var noSearchResultsView: some View {
        VStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.secondary)

            Text("No landmarks found")
                .font(.system(size: 17, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)

            Text("Try a landmark label or promotion title.")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal)
    }

    private func searchablePromotionTitles(for landmark: BusinessLandmark) -> [String] {
        var titles = promotionTitlesByLandmarkId[landmark.landmarkId] ?? []

        if let legacyPromotion = landmark.promotion?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !legacyPromotion.isEmpty,
           !titles.contains(where: { $0.caseInsensitiveCompare(legacyPromotion) == .orderedSame }) {
            titles.append(legacyPromotion)
        }
        return titles
    }

    private func matchedPromotionTitle(for landmark: BusinessLandmark) -> String? {
        let query = cleanedSearchText

        guard !query.isEmpty else { return nil }
        guard !landmark.label.localizedCaseInsensitiveContains(query) else { return nil }

        return searchablePromotionTitles(for: landmark).first {
            $0.localizedCaseInsensitiveContains(query)
        }
    }

    @MainActor
    private func refreshLandmarksAndSearchIndex() async {
        await viewModel.refresh()
        await loadPromotionSearchIndex(forceReload: true)
    }

    @MainActor
    private func loadPromotionSearchIndex(forceReload: Bool = false) async {
        let currentLandmarks = viewModel.landmarks
        let validLandmarkIds = Set(currentLandmarks.map(\.landmarkId))

        promotionTitlesByLandmarkId = promotionTitlesByLandmarkId.filter {
            validLandmarkIds.contains($0.key)
        }

        guard !currentLandmarks.isEmpty else {
            isIndexingPromotionTitles = false
            return
        }

        let landmarksToLoad = forceReload
            ? currentLandmarks
            : currentLandmarks.filter { promotionTitlesByLandmarkId[$0.landmarkId] == nil }

        guard !landmarksToLoad.isEmpty else {
            isIndexingPromotionTitles = false
            return
        }

        isIndexingPromotionTitles = true
        defer { isIndexingPromotionTitles = false }

        for landmark in landmarksToLoad {
            guard !Task.isCancelled else { return }

            do {
                let response = try await promotionService.fetchPromotions(landmarkId: landmark.landmarkId)
                let titles = response.items
                    .map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }

                promotionTitlesByLandmarkId[landmark.landmarkId] = titles
            } catch {
                if promotionTitlesByLandmarkId[landmark.landmarkId] == nil {
                    let legacyPromotion = landmark.promotion?.trimmingCharacters(in: .whitespacesAndNewlines)
                    promotionTitlesByLandmarkId[landmark.landmarkId] = legacyPromotion.map { $0.isEmpty ? [] : [$0] } ?? []
                }
            }
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
                if isUploading {
                    Text("Syncing to Cloud...")
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                } else if isOffline {
                    Text("Waiting for Connection")
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                } else {
                    Text("Queue Processing...")
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                }
               
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
        let matchedPromotionTitle: String?
        let isSelectionMode: Bool
        let isSelected: Bool

        private let selectionColor = Color(
            red: 0.22,
            green: 0.49,
            blue: 1.00
        )

        var body: some View {
            HStack(alignment: .top, spacing: 14) {
                if isSelectionMode {
                    Image(
                        systemName: isSelected
                            ? "checkmark.circle.fill"
                            : "circle"
                    )
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(
                        isSelected
                            ? selectionColor
                            : Color(uiColor: .tertiaryLabel)
                    )
                    .padding(.top, 1)
                    .transition(.scale.combined(with: .opacity))
                }

                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .top) {
                        Text(
                            landmark.label.isEmpty
                                ? "Untitled Landmark"
                                : landmark.label
                        )
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.primary)

                        Spacer()

                        Text(landmark.displayStatus)
                            .font(.system(size: 11, weight: .bold, design: .rounded))
                            .textCase(.uppercase)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(
                                landmark.isActive == false
                                    ? Color.gray.opacity(0.15)
                                    : Color.green.opacity(0.15)
                            )
                            .foregroundColor(
                                landmark.isActive == false
                                    ? .secondary
                                    : .green
                            )
                            .clipShape(Capsule())
                    }

                    Text(landmark.displayDescription)
                        .font(.system(size: 14, weight: .regular))
                        .foregroundColor(.secondary)
                        .lineLimit(2)

                    if let matchedPromotionTitle {
                        HStack(spacing: 6) {
                            Image(systemName: "tag.fill")
                            Text("Matched promotion: \(matchedPromotionTitle)")
                                .lineLimit(1)
                        }
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(.orange)
                    }

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

                        if let latitude = landmark.latitude,
                           let longitude = landmark.longitude {
                            HStack(spacing: 4) {
                                Image(systemName: "location.fill")
                                Text(
                                    String(
                                        format: "%.4f, %.4f",
                                        latitude,
                                        longitude
                                    )
                                )
                            }
                            .font(
                                .system(
                                    size: 12,
                                    weight: .bold,
                                    design: .monospaced
                                )
                            )
                            .foregroundStyle(.tertiary)
                        }
                    }
                }
            }
            .padding(20)
            .background {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(
                        isSelected
                            ? selectionColor.opacity(0.10)
                            : Color(uiColor: .secondarySystemGroupedBackground)
                    )
            }
            .overlay {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(
                        isSelected
                            ? selectionColor.opacity(0.60)
                            : Color.clear,
                        lineWidth: 1.5
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
            .animation(.easeOut(duration: 0.18), value: isSelected)
        }
    }
}

private struct BulkLandmarkSelection: Identifiable {
    let id = UUID()
    let landmarks: [BusinessLandmark]
}
