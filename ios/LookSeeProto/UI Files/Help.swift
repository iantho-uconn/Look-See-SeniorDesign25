//
//  Help.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct FAQItem: Identifiable {
    let id = UUID()
    let question: String
    let answer: String
}

struct Help: View {
    @Environment(\.dismiss) var dismiss

    let faqs = [
        FAQItem(question: "How do I record a landmark?", answer: "Tap the record button on the main screen. Hold your device steady and follow the on-screen instructions."),
        FAQItem(question: "How do I delete my data?", answer: "You can delete individual landmarks from your Manage My Landmarks Screen or contact support to request a full account deletion."),
        FAQItem(question: "Is my location data shared?", answer: "Your location is only used to place landmarks on the map and is never shared with third parties without your consent.")
    ]

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // MARK: - Common Questions
                VStack(alignment: .leading, spacing: 10) {
                    sectionHeader("Common Questions")

                    VStack(spacing: 0) {
                        ForEach(faqs.indices, id: \.self) { index in
                            FAQCard(faq: faqs[index], primaryColor: primaryColor)
                            
                            if index < faqs.count - 1 {
                                Divider()
                                    .padding(.leading, 56)
                            }
                        }
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                }

                // MARK: - Still need help?
                VStack(alignment: .leading, spacing: 10) {
                    sectionHeader("Still need help?")

                    VStack(spacing: 0) {
                        SupportContactCard(
                            title: "Email Support",
                            systemImage: "envelope.fill",
                            primaryColor: primaryColor
                        ) {
                            if let url = URL(string: "mailto:looksee.support@informationoutpost.com") {
                                UIApplication.shared.open(url)
                            }
                        }

                        Divider()
                            .padding(.leading, 56)

                        SupportContactCard(
                            title: "Visit Website",
                            systemImage: "globe",
                            primaryColor: primaryColor
                        ) {
                            if let url = URL(string: "https://www.informationoutpost.com") {
                                UIApplication.shared.open(url)
                            }
                        }
                    }
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: .black.opacity(0.03), radius: 8, x: 0, y: 2)
                }

                // MARK: - Footer
                VStack(spacing: 4) {
                    Text("Version \(appVersion) (Build \(appBuild))")
                    Text("© 2026 Information Outpost")
                }
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 24)
            }
            .padding(.top, 16)
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Help & Support")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .bold, design: .rounded))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.horizontal, 4)
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.4"
    }

    private var appBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "256"
    }
}

// MARK: - Subcomponents

struct FAQCard: View {
    let faq: FAQItem
    let primaryColor: Color
    @State private var isExpanded = false

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                isExpanded.toggle()
            }
        } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 16) {
                    Image(systemName: "questionmark.circle")
                        .font(.system(size: 24))
                        .foregroundStyle(primaryColor)

                    Text(faq.question)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Color(uiColor: .tertiaryLabel))
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }

                if isExpanded {
                    Text(faq.answer)
                        .font(.system(size: 14, weight: .regular))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 40)
                        .multilineTextAlignment(.leading)
                }
            }
            .padding(16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct SupportContactCard: View {
    let title: String
    let systemImage: String
    let primaryColor: Color
    let action: () -> Void

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(primaryColor.opacity(0.15))
                        .frame(width: 36, height: 36)

                    Image(systemName: systemImage)
                        .font(.system(size: 18))
                        .foregroundStyle(primaryColor)
                }

                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.primary)

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .padding(16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        Help()
    }
}
