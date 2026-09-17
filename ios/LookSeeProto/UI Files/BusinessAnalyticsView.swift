//
//  BusinessAnalyticsView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 9/9/26.
//


import SwiftUI
import Charts
import Combine
import Foundation

// MARK: - Data Models
struct AnalyticsResponse: Decodable {
    let analytics: [LandmarkAnalyticsData]
}

struct LandmarkAnalyticsData: Decodable, Identifiable {
    var id: String { landmarkId }
    let landmarkId: String
    let label: String
    let totalClicks: Int
    let dailyData: [DailyClickPoint]
}

struct DailyClickPoint: Decodable, Identifiable {
    var id: String { date }
    let date: String
    let clicks: Int
}

// Internal model for clean Time-Series Charts
struct TimeSeriesPoint: Identifiable {
    let id = UUID()
    let date: Date
    let clicks: Int
}

enum AnalyticsChartMode {
    case trend
    case distribution
}

// MARK: - View Model
@MainActor
class BusinessAnalyticsViewModel: ObservableObject {
    @Published var data: [LandmarkAnalyticsData] = []
    @Published var isLoading = true
    @Published var errorMessage: String? = nil

    // Dashboard Aggregations
    var totalViews: Int {
        data.reduce(0) { $0 + $1.totalClicks }
    }
    
    var topLandmark: String {
        guard totalViews > 0 else { return "N/A" }
        return data.max(by: { $0.totalClicks < $1.totalClicks })?.label ?? "N/A"
    }
    
    var activeLandmarksWithViews: [LandmarkAnalyticsData] {
        data.filter { $0.totalClicks > 0 }
            .sorted { $0.totalClicks > $1.totalClicks }
    }
    
    var sortedLandmarks: [LandmarkAnalyticsData] {
        data.sorted {
            if $0.totalClicks == $1.totalClicks {
                return $0.label < $1.label
            }
            return $0.totalClicks > $1.totalClicks
        }
    }
    
    // Converts the string dates ("09/08") into actual Date objects so SwiftUI Charts can draw a proper X-Axis
    var timeSeriesData: [TimeSeriesPoint] {
        guard let template = data.first?.dailyData else { return [] }
        var merged = [String: Int]()
        
        for item in data {
            for point in item.dailyData {
                merged[point.date, default: 0] += point.clicks
            }
        }
        
        let currentYear = Calendar.current.component(.year, from: Date())
        let formatter = DateFormatter()
        formatter.dateFormat = "MM/dd/yyyy"
        
        return template.compactMap { point in
            let dateString = "\(point.date)/\(currentYear)"
            guard let exactDate = formatter.date(from: dateString) else { return nil }
            return TimeSeriesPoint(date: exactDate, clicks: merged[point.date] ?? 0)
        }.sorted { $0.date < $1.date }
    }

    func fetchAnalytics(vm: AuthViewModel) async {
        guard !vm.userId.isEmpty else { return }
        
        guard let url = URL(string: "https://d11vl3v9w133rh.cloudfront.net/analytics") else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        let idToken = await vm.fetchIdToken()
        if !idToken.isEmpty {
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                do {
                    let decoded = try JSONDecoder().decode(AnalyticsResponse.self, from: data)
                    self.data = decoded.analytics
                    self.isLoading = false
                } catch {
                    let rawString = String(data: data, encoding: .utf8) ?? "Unreadable data"
                    self.errorMessage = "AWS sent back: \(rawString)"
                    self.isLoading = false
                }
            } else {
                let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                self.errorMessage = "Failed to load data. Status Code: \(statusCode)"
                self.isLoading = false
            }
        } catch {
            self.errorMessage = error.localizedDescription
            self.isLoading = false
        }
    }
}

// MARK: - Main View
struct BusinessAnalyticsView: View {
    @EnvironmentObject var vm: AuthViewModel
    @StateObject private var viewModel = BusinessAnalyticsViewModel()
    
    @State private var chartMode: AnalyticsChartMode = .trend

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                if viewModel.isLoading {
                    ProgressView()
                        .scaleEffect(1.5)
                        .padding(.top, 60)
                } else if let errorMessage = viewModel.errorMessage {
                    errorStateView(message: errorMessage)
                } else if viewModel.data.isEmpty {
                    emptyStateView
                } else {
                    kpiGrid
                    
                    chartToggle
                    
                    if chartMode == .trend {
                        trendChartSection
                    } else {
                        distributionChartSection
                    }
                    
                    leaderboardSection
                }
            }
            .padding(.vertical, 20)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Analytics")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.fetchAnalytics(vm: vm)
        }
        .refreshable {
            await viewModel.fetchAnalytics(vm: vm)
        }
    }
    
    // MARK: - Dashboard Components
    
    private var kpiGrid: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Total Views")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                    
                    Text("\(viewModel.totalViews)")
                        .font(.system(size: 42, weight: .heavy, design: .rounded))
                        .foregroundStyle(.primary)
                }
                Spacer()
                Image(systemName: "eye.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(primaryColor.opacity(0.2))
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
            
            HStack(spacing: 12) {
                kpiCard(title: "Landmarks", value: "\(viewModel.data.count)", icon: "mappin.and.ellipse", color: .purple)
                kpiCard(title: "Top Performer", value: viewModel.topLandmark, icon: "star.fill", color: .orange)
            }
        }
        .padding(.horizontal, 20)
    }
    
    private func kpiCard(title: String, value: String, icon: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(color)
                Text(title)
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                Spacer()
            }
            
            Text(value)
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
    }
    
    private var chartToggle: some View {
        Picker("Chart Mode", selection: $chartMode) {
            Text("Over Time").tag(AnalyticsChartMode.trend)
            Text("Breakdown").tag(AnalyticsChartMode.distribution)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 20)
    }
    
    private var trendChartSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Engagement Over Time (30 Days)")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .padding(.horizontal, 20)
            
            VStack {
                if viewModel.totalViews == 0 {
                    noDataChartPlaceholder
                } else {
                    Chart(viewModel.timeSeriesData) { item in
                        AreaMark(
                            x: .value("Date", item.date),
                            y: .value("Views", item.clicks)
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: [primaryColor.opacity(0.5), primaryColor.opacity(0.0)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        
                        LineMark(
                            x: .value("Date", item.date),
                            y: .value("Views", item.clicks)
                        )
                        .foregroundStyle(primaryColor)
                        .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                    }
                    .frame(height: 220)
                    .chartXAxis {
                        AxisMarks(preset: .aligned, values: .automatic(desiredCount: 5)) { value in
                            AxisGridLine()
                            AxisValueLabel(format: .dateTime.month().day())
                        }
                    }
                }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.04), radius: 8, x: 0, y: 4)
            .padding(.horizontal, 20)
        }
        .transition(.opacity.combined(with: .scale(scale: 0.98)))
    }
    
    private var distributionChartSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Views by Landmark")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .padding(.horizontal, 20)
            
            VStack {
                if viewModel.activeLandmarksWithViews.isEmpty {
                    noDataChartPlaceholder
                } else {
                    if #available(iOS 17.0, *) {
                        Chart(viewModel.activeLandmarksWithViews) { landmark in
                            SectorMark(
                                angle: .value("Views", landmark.totalClicks),
                                innerRadius: .ratio(0.618),
                                angularInset: 2.0
                            )
                            .cornerRadius(6)
                            .foregroundStyle(by: .value("Landmark", landmark.label))
                            .annotation(position: .overlay) {
                                // Only show percentage on large slices
                                let percentage = Double(landmark.totalClicks) / Double(viewModel.totalViews)
                                if percentage > 0.10 {
                                    Text("\(Int(percentage * 100))%")
                                        .font(.system(size: 12, weight: .bold, design: .rounded))
                                        .foregroundStyle(.white)
                                        .shadow(radius: 2)
                                }
                            }
                        }
                        .frame(height: 220)
                        .chartLegend(position: .bottom, alignment: .center, spacing: 16)
                    } else {
                        // Fallback Bar Chart for iOS 16
                        Chart(viewModel.activeLandmarksWithViews) { landmark in
                            BarMark(
                                x: .value("Views", landmark.totalClicks),
                                y: .value("Landmark", landmark.label)
                            )
                            .foregroundStyle(by: .value("Landmark", landmark.label))
                            .cornerRadius(4)
                        }
                        .frame(height: 220)
                        .chartLegend(.hidden)
                    }
                }
            }
            .padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.04), radius: 8, x: 0, y: 4)
            .padding(.horizontal, 20)
        }
        .transition(.opacity.combined(with: .scale(scale: 0.98)))
    }
    
    private var noDataChartPlaceholder: some View {
        ZStack {
            Color(uiColor: .tertiarySystemGroupedBackground)
            VStack(spacing: 8) {
                Image(systemName: "eye.slash.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(.secondary)
                Text("No views recorded yet.")
                    .font(.subheadline.bold())
                    .foregroundStyle(.secondary)
            }
        }
        .frame(height: 220)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
    
    private var leaderboardSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("All Landmarks")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .padding(.horizontal, 20)
                .padding(.top, 8)
            
            VStack(spacing: 0) {
                ForEach(Array(viewModel.sortedLandmarks.enumerated()), id: \.element.id) { index, landmark in
                    HStack(spacing: 16) {
                        
                        // Rank Number
                        Text("\(index + 1)")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(.tertiary)
                            .frame(width: 24, alignment: .leading)
                        
                        Text(landmark.label.isEmpty ? "Untitled Landmark" : landmark.label)
                            .font(.system(size: 16, weight: .semibold, design: .rounded))
                            .foregroundStyle(.primary)
                        
                        Spacer()
                        
                        Text("\(landmark.totalClicks)")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(landmark.totalClicks > 0 ? primaryColor.opacity(0.15) : Color.gray.opacity(0.15))
                            .foregroundStyle(landmark.totalClicks > 0 ? primaryColor : .secondary)
                            .clipShape(Capsule())
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    
                    if landmark.landmarkId != viewModel.sortedLandmarks.last?.landmarkId {
                        Divider().padding(.leading, 60)
                    }
                }
            }
            .background(Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
            .padding(.horizontal, 20)
        }
    }
    
    // MARK: - Empty / Error States
    private func errorStateView(message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 32))
                .foregroundStyle(.orange)
            Text(message)
                .font(.headline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 40)
        .padding(.horizontal, 32)
    }
    
    private var emptyStateView: some View {
        VStack(spacing: 12) {
            Image(systemName: "chart.bar.xaxis")
                .font(.system(size: 42))
                .foregroundStyle(.secondary.opacity(0.5))
            Text("No landmarks found.")
                .font(.headline)
                .foregroundStyle(.secondary)
            Text("Add landmarks to your business account to start tracking engagement.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 60)
        .padding(.horizontal, 32)
    }
}
