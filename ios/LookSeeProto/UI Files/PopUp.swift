//
//  PopUp.swift
//  LookSeeProto
//
//  Main popup for detected landmarks.
//  Displays the live website URL, promotion details,
//  promotion image, and local landmark image.
//

import SwiftUI

struct PopUp: View {
    @ObservedObject private var infoView = VariableContainer.shared

    @State private var selectedPromotionImage: PromotionImagePreviewItem?

    private let purpleStart = Color(
        red: 0.25,
        green: 0.10,
        blue: 0.90
    )

    private let purpleEnd = Color(
        red: 0.50,
        green: 0.15,
        blue: 0.95
    )

    private let promotionOrange = Color(
        red: 1.00,
        green: 0.58,
        blue: 0.18
    )

    var body: some View {
        VStack(spacing: 0) {
            header

            VStack(alignment: .leading, spacing: 16) {
                landmarkTextSection

                websiteSection

                if shouldShowPromotion {
                    promotionSection
                }
                
                // 🚀 NEW: The Merchant Card Sponsored Footer
                if !infoView.merchantName.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 6) {
                            Image(systemName: "building.2.fill").font(.system(size: 12))
                            Text("Landmark Sponsored By").font(.system(size: 12, weight: .bold)).textCase(.uppercase)
                        }
                        .foregroundStyle(.gray)
                        .padding(.leading, 4)
                        
                        MerchantCard(
                            storeName: infoView.merchantName,
                            logoUrl: infoView.merchantLogoUrl,
                            bio: infoView.merchantBio,
                            phone: infoView.merchantPhone
                        )
                    }
                    .padding(.top, 8)
                }

                gotItButton
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 20)
        }
        .background(.ultraThickMaterial)
        .clipShape(
            RoundedRectangle(
                cornerRadius: 30,
                style: .continuous
            )
        )
        .overlay {
            RoundedRectangle(
                cornerRadius: 30,
                style: .continuous
            )
            .stroke(
                Color.white.opacity(0.20),
                lineWidth: 1
            )
        }
        .shadow(
            color: .black.opacity(0.30),
            radius: 30,
            x: 0,
            y: 15
        )
        .padding(.horizontal, 28)
        .frame(maxHeight: UIScreen.main.bounds.height * 0.85)
        .sheet(item: $selectedPromotionImage) { item in
            promotionImagePreview(url: item.url)
        }
        .onChange(of: infoView.landmarkWebsiteUrl) { _, newValue in
            print("🔗 PopUp observed website URL: \(newValue)")
        }
        .onChange(of: infoView.promoImageUrl) { _, newValue in
            print("🖼️ PopUp observed promotion image URL: \(newValue)")
        }
    }

    // MARK: - Header

    private var header: some View {
        ZStack(alignment: .topTrailing) {
            landmarkHeaderContent

            Button {
                UIImpactFeedbackGenerator(style: .light)
                    .impactOccurred()

                infoView.dismissLandmark()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(.ultraThinMaterial)
                    .clipShape(Circle())
                    .padding(14)
            }
            .buttonStyle(.plain)
        }
        .clipShape(
            RoundedRectangle(
                cornerRadius: 24,
                style: .continuous
            )
        )
        .padding(6)
    }

    @ViewBuilder
    private var landmarkHeaderContent: some View {
        if let imageURL = normalizedURL(infoView.landmarkURL) {
            AsyncImage(url: imageURL) { phase in
                switch phase {
                case .empty:
                    ZStack {
                        compactGradientHeader

                        ProgressView()
                            .tint(.white)
                    }

                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()

                case .failure:
                    compactGradientHeader

                @unknown default:
                    compactGradientHeader
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 180)
            .clipped()
        } else {
            compactGradientHeader
        }
    }

    private var compactGradientHeader: some View {
        ZStack(alignment: .leading) {
            LinearGradient(
                colors: [
                    purpleStart,
                    purpleEnd
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            HStack(spacing: 10) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white.opacity(0.90))

                Text("Landmark Details")
                    .font(
                        .system(
                            size: 14,
                            weight: .bold,
                            design: .rounded
                        )
                    )
                    .foregroundStyle(.white.opacity(0.90))
            }
            .padding(.leading, 18)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 90)
    }

    // MARK: - Landmark Text

    private var landmarkTextSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(displayName)
                .font(
                    .system(
                        size: 26,
                        weight: .bold,
                        design: .rounded
                    )
                )
                .foregroundStyle(.primary)
                .fixedSize(
                    horizontal: false,
                    vertical: true
                )

            ViewThatFits(in: .vertical) {
                Text(displayDescription)
                    .font(
                        .system(
                            size: 15,
                            weight: .regular,
                            design: .rounded
                        )
                    )
                    .foregroundStyle(.secondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                
                ScrollView(.vertical, showsIndicators: true) {
                    Text(displayDescription)
                        .font(
                            .system(
                                size: 15,
                                weight: .regular,
                                design: .rounded
                            )
                        )
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.trailing, 8)
                }
            }
            .frame(maxHeight: 120)
        }
    }

    // MARK: - Website

    @ViewBuilder
    private var websiteSection: some View {
        if let websiteURL = normalizedURL(
            infoView.landmarkWebsiteUrl
        ) {
            Link(destination: websiteURL) {
                HStack(spacing: 12) {
                    Image(systemName: "safari.fill")
                        .font(.system(size: 18, weight: .bold))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Visit Website")
                            .font(
                                .system(
                                    size: 16,
                                    weight: .bold,
                                    design: .rounded
                                )
                            )

                        if let host = websiteURL.host {
                            Text(host)
                                .font(
                                    .system(
                                        size: 12,
                                        weight: .medium,
                                        design: .rounded
                                    )
                                )
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
                    LinearGradient(
                        colors: [
                            purpleStart,
                            purpleEnd
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                }
                .clipShape(
                    RoundedRectangle(
                        cornerRadius: 16,
                        style: .continuous
                    )
                )
                .overlay {
                    RoundedRectangle(
                        cornerRadius: 16,
                        style: .continuous
                    )
                    .stroke(
                        Color.white.opacity(0.18),
                        lineWidth: 1
                    )
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Promotion

    private var promotionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                Image(systemName: "sparkles")
                    .font(.system(size: 14, weight: .bold))

                Text("Active Promotion")
                    .font(
                        .system(
                            size: 12,
                            weight: .bold,
                            design: .rounded
                        )
                    )
                    .textCase(.uppercase)
            }
            .foregroundStyle(promotionOrange)

            Text(infoView.promoName)
                .font(
                    .system(
                        size: 19,
                        weight: .bold,
                        design: .rounded
                    )
                )
                .foregroundStyle(.primary)
                .fixedSize(
                    horizontal: false,
                    vertical: true
                )

            if !cleanedPromoDescription.isEmpty {
                Text(cleanedPromoDescription)
                    .font(
                        .system(
                            size: 14,
                            weight: .medium,
                            design: .rounded
                        )
                    )
                    .foregroundStyle(.secondary)
                    .fixedSize(
                        horizontal: false,
                        vertical: true
                    )
            }

            promotionImageSection
        }
        .padding(14)
        .frame(
            maxWidth: .infinity,
            alignment: .leading
        )
        .background(
            promotionOrange.opacity(0.12)
        )
        .clipShape(
            RoundedRectangle(
                cornerRadius: 16,
                style: .continuous
            )
        )
        .overlay {
            RoundedRectangle(
                cornerRadius: 16,
                style: .continuous
            )
            .stroke(
                promotionOrange.opacity(0.30),
                lineWidth: 1
            )
        }
    }

    @ViewBuilder
    private var promotionImageSection: some View {
        if let imageURL = normalizedURL(
            infoView.promoImageUrl
        ) {
            Button {
                UIImpactFeedbackGenerator(style: .medium)
                    .impactOccurred()

                selectedPromotionImage =
                    PromotionImagePreviewItem(
                        url: imageURL
                    )
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    AsyncImage(url: imageURL) { phase in
                        switch phase {
                        case .empty:
                            ZStack {
                                RoundedRectangle(
                                    cornerRadius: 14,
                                    style: .continuous
                                )
                                .fill(
                                    Color.primary.opacity(0.06)
                                )

                                ProgressView()
                            }

                        case .success(let image):
                            image
                                .resizable()
                                .scaledToFill()

                        case .failure:
                            promotionImageFailureView

                        @unknown default:
                            promotionImageFailureView
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 160)
                    .clipShape(
                        RoundedRectangle(
                            cornerRadius: 14,
                            style: .continuous
                        )
                    )
                    .overlay {
                        RoundedRectangle(
                            cornerRadius: 14,
                            style: .continuous
                        )
                        .stroke(
                            Color.primary.opacity(0.10),
                            lineWidth: 1
                        )
                    }

                    HStack(spacing: 5) {
                        Image(
                            systemName:
                                "arrow.up.left.and.arrow.down.right"
                        )

                        Text("View")
                    }
                    .font(
                        .system(
                            size: 12,
                            weight: .bold,
                            design: .rounded
                        )
                    )
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(
                        Color.black.opacity(0.60),
                        in: Capsule()
                    )
                    .padding(10)
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 2)
        }
    }

    private var promotionImageFailureView: some View {
        ZStack {
            RoundedRectangle(
                cornerRadius: 14,
                style: .continuous
            )
            .fill(Color.primary.opacity(0.06))

            VStack(spacing: 7) {
                Image(systemName: "photo")
                    .font(.system(size: 26, weight: .bold))

                Text("Promotion image unavailable")
                    .font(
                        .system(
                            size: 12,
                            weight: .bold,
                            design: .rounded
                        )
                    )
            }
            .foregroundStyle(.secondary)
        }
    }

    // MARK: - Got It

    private var gotItButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium)
                .impactOccurred()

            infoView.dismissLandmark()
        } label: {
            Text("Close")
                .font(
                    .system(
                        size: 16,
                        weight: .bold,
                        design: .rounded
                    )
                )
                .foregroundStyle(
                    Color(uiColor: .systemBackground)
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color.primary)
                .clipShape(
                    RoundedRectangle(
                        cornerRadius: 16,
                        style: .continuous
                    )
                )
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }

    // MARK: - Promotion Preview

    private func promotionImagePreview(
        url: URL
    ) -> some View {
        NavigationStack {
            ZStack {
                Color.black
                    .ignoresSafeArea()

                AsyncImage(url: url) { phase in
                    switch phase {
                    case .empty:
                        ProgressView()
                            .tint(.white)

                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFit()
                            .padding()

                    case .failure:
                        VStack(spacing: 12) {
                            Image(systemName: "photo")
                                .font(
                                    .system(
                                        size: 42,
                                        weight: .bold
                                    )
                                )

                            Text("Could not load image")
                                .font(.headline)
                        }
                        .foregroundStyle(
                            .white.opacity(0.80)
                        )

                    @unknown default:
                        EmptyView()
                    }
                }
            }
            .navigationTitle("Promotion Image")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(
                    placement: .cancellationAction
                ) {
                    Button("Done") {
                        selectedPromotionImage = nil
                    }
                }
            }
        }
    }

    // MARK: - Display Values

    private var displayName: String {
        let cleaned = infoView.landmarkName
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        return cleaned.isEmpty
            ? "Unknown Landmark"
            : cleaned
    }

    private var displayDescription: String {
        let cleaned = infoView.landmarkDescription
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        return cleaned.isEmpty
            ? "No description is available for this landmark."
            : cleaned
    }

    private var cleanedPromoDescription: String {
        infoView.promoDescription
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )
    }

    private var shouldShowPromotion: Bool {
        let cleanedName = infoView.promoName
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        return !cleanedName.isEmpty
            && cleanedName != "No active promotion"
            && cleanedName != "Checking promotions..."
    }

    // MARK: - URL Handling

    private func normalizedURL(
        _ rawValue: String
    ) -> URL? {
        let cleaned = rawValue
            .trimmingCharacters(
                in: .whitespacesAndNewlines
            )

        guard !cleaned.isEmpty else {
            return nil
        }

        if let directURL = URL(string: cleaned),
           let scheme = directURL.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return directURL
        }

        return URL(string: "https://\(cleaned)")
    }
}

private struct PromotionImagePreviewItem: Identifiable {
    let id = UUID()
    let url: URL
}
