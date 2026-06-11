import SwiftUI

struct Library: View {
    @StateObject private var libraryService = LibraryService.shared
    @StateObject private var modelService = ModelService.shared
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            Group {
                if libraryService.isLoading {
                    VStack(spacing: 16) {
                        ProgressView()
                        Text("Loading landmarks…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                } else if let error = libraryService.errorMessage {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 32))
                            .foregroundStyle(.orange)
                        Text("Could not load landmarks")
                            .font(.headline)
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                        Button("Retry") {
                            Task { await loadForCurrentModel() }
                        }
                        .buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                } else if libraryService.items.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "archivebox")
                            .font(.system(size: 32))
                            .foregroundStyle(.secondary)
                        Text("No landmarks found")
                            .font(.headline)
                        Text("No landmarks are associated with your current model.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                } else {
                    List(filtered) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.label)
                                .font(.headline)
                            Text(item.shortDescription)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Text("Cluster \(item.clusterId)")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                    }
                    .searchable(text: $searchText)
                }
            }
            .navigationTitle("Library")
            .task {
                await loadForCurrentModel()
            }
        }
    }

    // MARK: - Filter
    var filtered: [LibraryLandmark] {
        if searchText.isEmpty {
            return libraryService.items
        }
        return libraryService.items.filter {
            $0.label.localizedCaseInsensitiveContains(searchText) ||
            $0.shortDescription.localizedCaseInsensitiveContains(searchText)
        }
    }

    // MARK: - Load for current model
    private func loadForCurrentModel() async {
        if case .loaded(let infos) = modelService.state,
           let first = infos.first,
           let clusterIdInt = Int(first.name.components(separatedBy: "-").last ?? "") {
            await libraryService.fetchLandmarks(clusterId: clusterIdInt)
        } else {
            libraryService.errorMessage = "No model loaded. Load a model first in Settings."
        }
    }
}

#Preview {
    Library()
}
