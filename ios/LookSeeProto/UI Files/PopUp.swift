//
//  PopUp.swift
//  LookSeeProto
//

import SwiftUI
import MapKit
import Sentry

struct PopUp: View {
    @ObservedObject private var infoView = VariableContainer.shared

    @State private var selectedPromotionImage: PromotionImagePreviewItem?
    @State private var showReportSheet = false

    private let purpleStart = Color(red: 0.25, green: 0.10, blue: 0.90)
    private let purpleEnd = Color(red: 0.50, green: 0.15, blue: 0.95)
    private let promotionOrange = Color(red: 1.00, green: 0.58, blue: 0.18)
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00) // The blue from Image 3

    var body: some View {
        GeometryReader { proxy in
            let popupWidth = min(max(proxy.size.width - 56, 1), 620)
            let maximumPopupHeight = min(max(proxy.size.height - 32, 1), 780)

            ViewThatFits(in: .vertical) {
                intrinsicPopup(width: popupWidth)
                scrollingPopup(width: popupWidth, height: maximumPopupHeight)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
        .environment(\.colorScheme, .dark)
        .sheet(item: $selectedPromotionImage) { item in
            promotionImagePreview(url: item.url)
        }
        .sheet(isPresented: $showReportSheet) {
            MapReportSheet(
                landmarkId: infoView.landmarkId,
                landmarkLabel: infoView.landmarkName,
                reportedOwnerId: infoView.reportedOwnerId ?? "unknown"
            )
            .presentationDetents([.fraction(0.70), .large])
            .presentationDragIndicator(.visible)
        }
    }

    // MARK: - Adaptive Popup Layouts

    private func intrinsicPopup(width: CGFloat) -> some View {
        popupShell(width: width) {
            VStack(spacing: 0) {
                popupContent.fixedSize(horizontal: false, vertical: true)
                popupDivider
                closeButtonArea
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func scrollingPopup(width: CGFloat, height: CGFloat) -> some View {
        popupShell(width: width) {
            VStack(spacing: 0) {
                ScrollView(.vertical, showsIndicators: true) {
                    popupContent
                }
                .scrollDismissesKeyboard(.immediately)
                popupDivider
                closeButtonArea
            }
            .frame(height: height)
        }
    }

    private func popupShell<Content: View>(width: CGFloat, @ViewBuilder content: () -> Content) -> some View {
        content()
            .frame(width: width)
            .background(.ultraThickMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .stroke(Color.white.opacity(0.20), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.30), radius: 30, x: 0, y: 15)
    }

    private var popupContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            landmarkTextSection
            websiteSection

            if shouldShowPromotion {
                promotionSection
            }

            MerchantCardView()
            
            // 🚀 NEW: Appends the Map Actions (Image 3) right below the Merchant Card (Image 2)
            if infoView.isMapPin {
                mapActionButtons
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 26)
        .padding(.bottom, 22)
    }

    private var popupDivider: some View {
        Divider().opacity(0.18)
    }

    private var closeButtonArea: some View {
        closeButton
            .padding(.horizontal, 20)
            .padding(.top, 14)
            .padding(.bottom, 18)
    }

    // MARK: - Map Action Buttons
    
    @ViewBuilder
    private var mapActionButtons: some View {
        HStack(spacing: 12) {
            // Directions Button
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                openAppleMaps()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "location.fill")
                    Text("Directions").fontWeight(.bold)
                }
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(Color(uiColor: .tertiarySystemFill))
                .foregroundStyle(primaryColor) // Matches the blue from Image 3
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            // Report Button
            Button {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                showReportSheet = true
            } label: {
                Image(systemName: "flag.fill")
                    .font(.system(size: 20))
                    .frame(width: 54, height: 54)
                    .background(Color.red.opacity(0.15))
                    .foregroundStyle(.red) // Matches the red flag from Image 3
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .padding(.top, 4)
    }
    
    private func openAppleMaps() {
        guard let lat = infoView.mapLatitude, let lon = infoView.mapLongitude else { return }
        let mapItem = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)))
        mapItem.name = infoView.landmarkName
        mapItem.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
    }

    // MARK: - Landmark Text

    private var landmarkTextSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(displayName)
                .font(.system(size: 34, weight: .heavy, design: .rounded))
                .foregroundStyle(.primary)
                .lineLimit(3)
                .minimumScaleFactor(0.72)
                .fixedSize(horizontal: false, vertical: true)

            Text(displayDescription)
                .font(.system(size: 16, weight: .regular, design: .rounded))
                .foregroundStyle(.secondary)
                .lineSpacing(5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
    }

    // MARK: - Website

    @ViewBuilder
    private var websiteSection: some View {
        if let websiteURL = normalizedURL(infoView.landmarkWebsiteUrl) {
            Link(destination: websiteURL) {
                HStack(spacing: 12) {
                    Image(systemName: "safari.fill")
                        .font(.system(size: 18, weight: .bold))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Visit Website")
                            .font(.system(size: 16, weight: .bold, design: .rounded))

                        if let host = websiteURL.host {
                            Text(host)
                                .font(.system(size: 12, weight: .medium, design: .rounded))
                                .opacity(0.75)
                                .lineLimit(1)
                        }
                    }

                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background {
                    LinearGradient(colors: [purpleStart, purpleEnd], startPoint: .leading, endPoint: .trailing)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color.white.opacity(0.18), lineWidth: 1)
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Promotion

    private var promotionSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(infoView.promoName)
                .font(.system(size: 21, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            if !cleanedPromoDescription.isEmpty {
                Text(cleanedPromoDescription)
                    .font(.system(size: 14, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            promotionImageSection
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(promotionOrange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(promotionOrange.opacity(0.30), lineWidth: 1)
        }
    }

    @ViewBuilder
    private var promotionImageSection: some View {
        if let imageURL = normalizedURL(infoView.promoImageUrl) {
            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                selectedPromotionImage = PromotionImagePreviewItem(url: imageURL)
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    AsyncImage(url: imageURL) { phase in
                        switch phase {
                        case .empty:
                            ZStack {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(Color.primary.opacity(0.06))
                                ProgressView()
                            }
                        case .success(let image):
                            image
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .clipped()
                        case .failure:
                            promotionImageFailureView
                        @unknown default:
                            promotionImageFailureView
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 160, maxHeight: 160)
                    .background(Color.black.opacity(0.22))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Color.primary.opacity(0.10), lineWidth: 1)
                    }

                    HStack(spacing: 5) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                        Text("View")
                    }
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(Color.black.opacity(0.60), in: Capsule())
                    .padding(10)
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 2)
        }
    }

    private var promotionImageFailureView: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.primary.opacity(0.06))
            VStack(spacing: 7) {
                Image(systemName: "photo").font(.system(size: 26, weight: .bold))
                Text("Promotion image unavailable")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
            }
            .foregroundStyle(.secondary)
        }
    }

    // MARK: - Close Button

    private var closeButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            infoView.dismissLandmark()
        } label: {
            Text("Close")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .foregroundStyle(Color(uiColor: .systemBackground))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Promotion Preview

    private func promotionImagePreview(url: URL) -> some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .empty:
                        ProgressView().tint(.white)
                    case .success(let image):
                        image.resizable().scaledToFit().padding()
                    case .failure:
                        VStack(spacing: 12) {
                            Image(systemName: "photo").font(.system(size: 42, weight: .bold))
                            Text("Could not load image").font(.headline)
                        }
                        .foregroundStyle(.white.opacity(0.80))
                    @unknown default:
                        EmptyView()
                    }
                }
            }
            .navigationTitle("Promotion Image")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { selectedPromotionImage = nil }
                }
            }
        }
    }

    // MARK: - Display Values

    private var displayName: String {
        let cleaned = infoView.landmarkName.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "Unknown Landmark" : cleaned
    }

    private var displayDescription: String {
        let cleaned = infoView.landmarkDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "No description is available for this landmark." : cleaned
    }

    private var cleanedPromoDescription: String {
        infoView.promoDescription.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var shouldShowPromotion: Bool {
        let cleanedName = infoView.promoName.trimmingCharacters(in: .whitespacesAndNewlines)
        return !cleanedName.isEmpty && cleanedName != "No active promotion" && cleanedName != "Checking promotions..."
    }

    // MARK: - URL Handling

    private func normalizedURL(_ rawValue: String) -> URL? {
        let cleaned = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        if let directURL = URL(string: cleaned), let scheme = directURL.scheme?.lowercased(), scheme == "http" || scheme == "https" {
            return directURL
        }
        return URL(string: "https://\(cleaned)")
    }
}

private struct PromotionImagePreviewItem: Identifiable {
    let id = UUID()
    let url: URL
}

// MARK: - Shared Report Sheet
struct MapReportSheet: View {
    let landmarkId: String
    let landmarkLabel: String
    let reportedOwnerId: String
    
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    
    @State private var selectedReason: ReportReason? = nil
    @State private var customExplanation = ""
    @State private var isSubmitting = false
    @State private var reportSuccess = false
    
    private let maxWords = 40
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    enum ReportReason: String, CaseIterable, Identifiable {
        case fakeObject = "Not a real object or business"
        case inappropriate = "Inappropriate content"
        case ownership = "I am the real owner of this business"
        case inaccurateLocation = "Location is highly inaccurate"
        case other = "Other / Custom Issue"

        var id: String { rawValue }

        var icon: String {
            switch self {
            case .fakeObject: return "trash.slash.fill"
            case .inappropriate: return "exclamationmark.shield.fill"
            case .ownership: return "person.badge.key.fill"
            case .inaccurateLocation: return "map.fill"
            case .other: return "text.bubble.fill"
            }
        }

        var tintColor: Color {
            switch self {
            case .fakeObject: return .red
            case .inappropriate: return .orange
            case .ownership: return .purple
            case .inaccurateLocation: return .blue
            case .other: return .secondary
            }
        }
    }

    private var wordCount: Int {
        let components = customExplanation.split { $0.isWhitespace || $0.isNewline }
        return components.count
    }

    private var isFormValid: Bool {
        guard let reason = selectedReason else { return false }
        if reason == .other {
            return !customExplanation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && wordCount <= maxWords
        }
        return true
    }

    var body: some View {
        ZStack {
            Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            
            if reportSuccess {
                VStack(spacing: 16) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(.green)
                    Text("Report Submitted")
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                    Text("Thank you for keeping LookSee clean. Our team will review this landmark shortly.")
                        .font(.system(size: 16))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .transition(.scale.combined(with: .opacity))
            } else {
                ZStack(alignment: .bottom) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Report Landmark")
                                    .font(.system(size: 26, weight: .bold, design: .rounded))
                                    .foregroundStyle(.primary)
                                
                                Text("Why are you reporting '\(landmarkLabel)'?")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 4)
                            .padding(.top, 24)

                            VStack(spacing: 12) {
                                ForEach(ReportReason.allCases) { reason in
                                    reasonCard(for: reason)
                                }
                            }

                            if selectedReason == .other {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("Please describe the issue")
                                        .font(.system(size: 13, weight: .bold, design: .rounded))
                                        .foregroundStyle(.secondary)
                                        .textCase(.uppercase)

                                    ZStack(alignment: .bottomTrailing) {
                                        TextEditor(text: $customExplanation)
                                            .font(.system(size: 15))
                                            .scrollContentBackground(.hidden)
                                            .frame(height: 110)
                                            .padding(12)
                                            .background(Color(uiColor: .tertiarySystemGroupedBackground))
                                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                            .overlay(
                                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                                    .stroke(wordCount > maxWords ? Color.red.opacity(0.8) : Color.clear, lineWidth: 1.5)
                                            )
                                            .onChange(of: customExplanation) { _, newValue in
                                                let words = newValue.split { $0.isWhitespace || $0.isNewline }
                                                if words.count > maxWords {
                                                    customExplanation = words.prefix(maxWords).joined(separator: " ")
                                                }
                                            }

                                        Text("\(wordCount)/\(maxWords)")
                                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                                            .foregroundStyle(wordCount > maxWords ? .red : .secondary)
                                            .padding(10)
                                    }
                                }
                                .transition(.opacity.combined(with: .move(edge: .top)))
                            }

                            Spacer(minLength: 100)
                        }
                        .padding(24)
                    }

                    if selectedReason != nil {
                        VStack(spacing: 0) {
                            Divider()
                            Button {
                                Task { await submitReport() }
                            } label: {
                                HStack(spacing: 8) {
                                    if isSubmitting {
                                        ProgressView().tint(.white)
                                    } else {
                                        Text("Submit Report")
                                            .font(.system(size: 17, weight: .bold, design: .rounded))
                                        Image(systemName: "paperplane.fill")
                                            .font(.system(size: 14, weight: .bold))
                                    }
                                }
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(isFormValid ? primaryColor : Color.gray.opacity(0.4))
                                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .disabled(!isFormValid || isSubmitting)
                            .padding(.horizontal, 24)
                            .padding(.top, 12)
                            .padding(.bottom, 20)
                        }
                        .background(.ultraThinMaterial)
                        .transition(.move(edge: .bottom))
                    }
                }
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: selectedReason)
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: reportSuccess)
    }

    @ViewBuilder
    private func reasonCard(for reason: ReportReason) -> some View {
        let isSelected = selectedReason == reason

        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            selectedReason = reason
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(reason.tintColor.opacity(0.15))
                        .frame(width: 42, height: 42)
                    
                    Image(systemName: reason.icon)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(reason.tintColor)
                }

                Text(reason.rawValue)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)

                Spacer()

                ZStack {
                    Circle()
                        .stroke(isSelected ? primaryColor : Color(uiColor: .tertiaryLabel), lineWidth: 2)
                        .frame(width: 22, height: 22)
                    
                    if isSelected {
                        Circle()
                            .fill(primaryColor)
                            .frame(width: 12, height: 12)
                    }
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(isSelected ? primaryColor : Color.clear, lineWidth: 2)
            )
            .shadow(color: .black.opacity(isSelected ? 0.05 : 0.02), radius: isSelected ? 8 : 4, x: 0, y: 2)
        }
        .buttonStyle(.plain)
    }

    private func submitReport() async {
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        isSubmitting = true
        
        let finalReason = selectedReason == .other ? "Other: \(customExplanation)" : (selectedReason?.rawValue ?? "Unknown")

        SentrySDK.capture(message: "[Reported Landmark] \(landmarkLabel)", block: { scope in
            scope.setTag(value: "Content Report", key: "Category")
            scope.setTag(value: finalReason, key: "Report Reason")
            scope.setExtra(value: landmarkId, key: "Reported Landmark ID")
            scope.setExtra(value: reportedOwnerId, key: "Reported Owner ID")
            scope.setExtra(value: vm.userEmail, key: "Reporter Email")
        })
        
        try? await Task.sleep(nanoseconds: 800_000_000)
        
        isSubmitting = false
        withAnimation { reportSuccess = true }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            dismiss()
        }
    }
}
