//
//  ReportIssueView.swift
//  LookSeeProto
//

import SwiftUI
import PhotosUI
import Sentry // 🚀 Sentry SDK integrated

// MARK: - Report Models

enum ReportCategory: String, CaseIterable, Identifiable {
    case uiBug = "ui_bug"
    case detectionBug = "detection_bug"
    case uploadBug = "upload_bug"
    case other = "other"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .uiBug: return String(localized: "UI Bug")
        case .detectionBug: return String(localized: "Detection Bug")
        case .uploadBug: return String(localized: "Upload Bug")
        case .other: return String(localized: "Other")
        }
    }

    var icon: String {
        switch self {
        case .uiBug: return "rectangle.on.rectangle.slash"
        case .detectionBug: return "viewfinder.circle"
        case .uploadBug: return "arrow.up.circle"
        case .other: return "questionmark.circle"
        }
    }
}

enum ReportSeverity: String, CaseIterable, Identifiable {
    case low, medium, high, critical

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .low: return String(localized: "Low")
        case .medium: return String(localized: "Medium")
        case .high: return String(localized: "High")
        case .critical: return String(localized: "Critical")
        }
    }

    var color: Color {
        switch self {
        case .low: return .green
        case .medium: return .yellow
        case .high: return .orange
        case .critical: return .red
        }
    }
}

struct BugReport {
    var category: ReportCategory
    var severity: ReportSeverity
    var title: String
    var description: String
    var screenshot: UIImage?
}

// MARK: - Main View

struct ReportIssueView: View {
    @EnvironmentObject var vm: AuthViewModel
    @Environment(\.dismiss) var dismiss

    let initialScreenshot: UIImage?

    @State private var category: ReportCategory = .uiBug
    @State private var severity: ReportSeverity = .medium
    @State private var title = ""
    @State private var description = ""
    @State private var screenshot: UIImage?

    @State private var photoPickerItem: PhotosPickerItem?
    @State private var showSentConfirmation = false
    @State private var isResolvingIdentity = false

    @FocusState private var focusedField: Field?
    private enum Field { case title, description }

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    private var isValid: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    init(initialScreenshot: UIImage? = nil) {
        self.initialScreenshot = initialScreenshot
        _screenshot = State(initialValue: initialScreenshot)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(uiColor: .systemGroupedBackground).ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        categorySection
                        severitySection
                        detailsSection
                        screenshotSection
                        submitButton
                        Spacer(minLength: 24)
                    }
                    .padding(.top, 16)
                }
                .scrollDismissesKeyboard(.immediately)
            }
            .navigationTitle("Report a Bug")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        dismiss()
                    }
                }
            }
            .alert("Report Sent", isPresented: $showSentConfirmation) {
                Button("Done") { dismiss() }
            } message: {
                Text("Thanks for helping improve LookSee! Your report has been sent directly to our developer dashboard.")
            }
        }
    }

    // MARK: - Sections

    private var categorySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("What kind of issue?")

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(ReportCategory.allCases) { option in
                    categoryChip(option)
                }
            }
            .padding(.horizontal)
        }
    }

    private func categoryChip(_ option: ReportCategory) -> some View {
        let isSelected = category == option
        return Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            category = option
        } label: {
            VStack(spacing: 8) {
                Image(systemName: option.icon)
                    .font(.system(size: 20, weight: .semibold))
                Text(option.displayName)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(isSelected ? .white : .primary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(isSelected ? primaryColor : Color(uiColor: .secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private var severitySection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Severity")

            HStack(spacing: 8) {
                ForEach(ReportSeverity.allCases) { option in
                    severityChip(option)
                }
            }
            .padding(.horizontal)
        }
    }

    private func severityChip(_ option: ReportSeverity) -> some View {
        let isSelected = severity == option
        return Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            severity = option
        } label: {
            Text(option.displayName)
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(isSelected ? .white : option.color)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(isSelected ? option.color : option.color.opacity(0.12))
                .clipShape(Capsule())
        }
    }

    private var detailsSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                sectionHeader("Title")
                TextField("Short summary of the issue", text: $title)
                    .focused($focusedField, equals: .title)
                    .font(.system(size: 16, weight: .medium))
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(.horizontal)
            }

            VStack(alignment: .leading, spacing: 8) {
                sectionHeader("Description")
                TextField(
                    "What happened? What did you expect instead?",
                    text: $description,
                    axis: .vertical
                )
                .focused($focusedField, equals: .description)
                .lineLimit(4...10)
                .font(.system(size: 16, weight: .medium))
                .padding(16)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(.horizontal)
            }
        }
    }

    private var screenshotSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("Screenshot")

            if let screenshot {
                ZStack(alignment: .topTrailing) {
                    Image(uiImage: screenshot)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 260)
                        .frame(maxWidth: .infinity)
                        .background(Color(uiColor: .tertiarySystemFill))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        self.screenshot = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(.black.opacity(0.6))
                            .clipShape(Circle())
                    }
                    .padding(10)
                }
                .padding(.horizontal)
            }

            PhotosPicker(selection: $photoPickerItem, matching: .images) {
                HStack(spacing: 8) {
                    Image(systemName: "photo.on.rectangle")
                    Text(screenshot == nil ? "Attach a Screenshot" : "Replace Screenshot")
                }
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(primaryColor)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(primaryColor.opacity(0.10))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(.horizontal)
            .onChange(of: photoPickerItem) { _, newItem in
                Task {
                    guard let newItem,
                          let data = try? await newItem.loadTransferable(type: Data.self),
                          let image = UIImage(data: data) else { return }
                    await MainActor.run { screenshot = image }
                }
            }
        }
    }

    private var submitButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            focusedField = nil
            guard !isResolvingIdentity else { return }
            isResolvingIdentity = true

            // 🚀 THE FIX: Explicit 'block:' syntax used here as well!
            SentrySDK.capture(message: "[\(category.displayName)] \(title)", block: { scope in
                scope.setExtra(value: description, key: "User Description")
                scope.setTag(value: severity.displayName, key: "Severity")
                scope.setTag(value: category.displayName, key: "Category")
                scope.setExtra(value: vm.userEmail, key: "User Email")

                if let screenshot = screenshot, let data = screenshot.jpegData(compressionQuality: 0.8) {
                    let attachment = Attachment(data: data, filename: "screenshot.jpg", contentType: "image/jpeg")
                    scope.addAttachment(attachment)
                }
            })

            isResolvingIdentity = false
            showSentConfirmation = true

        } label: {
            HStack(spacing: 10) {
                if isResolvingIdentity {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: "paperplane.fill")
                }
                Text("Submit Report")
            }
            .font(.system(size: 17, weight: .bold, design: .rounded))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 18)
        }
        .background(isValid ? primaryColor : Color.gray.opacity(0.3))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .disabled(!isValid || isResolvingIdentity)
        .padding(.horizontal)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .bold, design: .rounded))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.horizontal, 20)
    }
}

// MARK: - Report Button + Screenshot Capture
// This handles the screenshot capture and opens the Report Issue sheet.

struct ReportIssueButton: View {
    @State private var showReportSheet = false
    @State private var capturedScreenshot: UIImage?

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            capturedScreenshot = Self.captureCurrentScreen()
            showReportSheet = true
        } label: {
            HStack(spacing: 16) {
                Image(systemName: "ladybug.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(Color.red)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("Report a Bug")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .padding(16)
        }
        Divider().padding(.leading, 68)
        .sheet(isPresented: $showReportSheet) {
            ReportIssueView(initialScreenshot: capturedScreenshot)
        }
    }

    private static func captureCurrentScreen() -> UIImage? {
        guard
            let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
            let window = scene.windows.first(where: { $0.isKeyWindow })
        else { return nil }

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        return renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
    }
}
