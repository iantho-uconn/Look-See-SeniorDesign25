//
//  ReportIssueView.swift
//  LookSeeProto
//
//  "Report a Bug" sheet — captures a screenshot of whatever screen was
//  visible when the report button was tapped, lets the user pick a
//  category/severity and describe the issue, then hands it to the
//  system Mail composer addressed to the team inbox.
//

import SwiftUI
import PhotosUI
import MessageUI

struct ReportIssueView: View {
    @EnvironmentObject var vm: AuthViewModel
    @Environment(\.dismiss) var dismiss

    /// Pass in a screenshot captured at the moment the report button was
    /// tapped (see ReportIssueButton below) so the user doesn't have to
    /// reproduce the bug a second time just to attach an image.
    let initialScreenshot: UIImage?

    @State private var category: ReportCategory = .uiBug
    @State private var severity: ReportSeverity = .medium
    @State private var title = ""
    @State private var description = ""
    @State private var screenshot: UIImage?

    @State private var photoPickerItem: PhotosPickerItem?
    @State private var showMailComposer = false
    @State private var showNoMailAlert = false
    @State private var showSentConfirmation = false
    @State private var isResolvingIdentity = false
    @State private var verifiedReplyToEmail: String?

    @FocusState private var focusedField: Field?
    private enum Field { case title, description }

    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)

    private var isValid: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var currentReport: BugReport {
        BugReport(
            category: category,
            severity: severity,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
            screenshot: screenshot
        )
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
            .sheet(isPresented: $showMailComposer) {
                MailComposerView(
                    subject: MailReportService.subject(for: currentReport),
                    body: MailReportService.body(for: currentReport, userEmail: verifiedReplyToEmail ?? vm.userEmail),
                    recipients: MailReportService.recipients,
                    replyToAddress: verifiedReplyToEmail,
                    attachmentData: MailReportService.attachmentData(for: currentReport),
                    attachmentFilename: "screenshot.jpg",
                    attachmentMimeType: "image/jpeg",
                    onFinish: { result in
                        showMailComposer = false
                        if result == .sent {
                            showSentConfirmation = true
                        }
                    }
                )
            }
            .alert("Report Sent", isPresented: $showSentConfirmation) {
                Button("Done") { dismiss() }
            } message: {
                Text("Thanks for helping improve LookSee!")
            }
            .alert("Mail Not Set Up", isPresented: $showNoMailAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Add a Mail account in Settings to send reports from the app, or email us directly at \(MailReportService.recipients.first ?? "").")
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

            Task {
                // Fetch first, THEN decide what to show — otherwise the
                // sheet opens before this finishes and replyToAddress
                // never gets set in time.
                let verifiedEmail = try? await AuthService.shared.fetchVerifiedEmail()

                await MainActor.run {
                    verifiedReplyToEmail = verifiedEmail ?? vm.userEmail
                    isResolvingIdentity = false

                    if MailReportService.canSendMail() {
                        showMailComposer = true
                    } else {
                        showNoMailAlert = true
                    }
                }
            }
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

// MARK: - Mail Composer Wrapper

/// UIKit bridge for MFMailComposeViewController — SwiftUI has no native
/// mail composer, so this wraps the system one.
struct MailComposerView: UIViewControllerRepresentable {
    let subject: String
    let body: String
    let recipients: [String]
    /// Best-effort only — see notes below. iOS gives third-party apps no
    /// way to force the "From" address of a Mail-composed message.
    var replyToAddress: String? = nil
    let attachmentData: Data?
    let attachmentFilename: String
    let attachmentMimeType: String
    let onFinish: (MFMailComposeResult) -> Void

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let composer = MFMailComposeViewController()
        composer.mailComposeDelegate = context.coordinator
        composer.setSubject(subject)
        composer.setMessageBody(body, isHTML: false)
        composer.setToRecipients(recipients)

        if let replyToAddress, !replyToAddress.isEmpty {
            // MFMailComposeViewController has no public API for setting a
            // Reply-To header — the only place the reporter's real email
            // can reliably surface is in the body text itself (already
            // included via MailReportService.body's "Reported by:" line).
            // This call is a legacy, deprecated-since-iOS-15 hint that iOS
            // may or may not honor — kept only because it's harmless, not
            // because it's reliable.
            composer.setPreferredSendingEmailAddress(replyToAddress)
        }

        if let attachmentData {
            composer.addAttachmentData(attachmentData, mimeType: attachmentMimeType, fileName: attachmentFilename)
        }
        return composer
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) { }

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onFinish: (MFMailComposeResult) -> Void

        init(onFinish: @escaping (MFMailComposeResult) -> Void) {
            self.onFinish = onFinish
        }

        func mailComposeController(
            _ controller: MFMailComposeViewController,
            didFinishWith result: MFMailComposeResult,
            error: Error?
        ) {
            controller.dismiss(animated: true) {
                self.onFinish(result)
            }
        }
    }
}

// MARK: - Report Button + Screenshot Capture

/// Drop this anywhere (e.g. your side menu or a toolbar) to launch the
/// report sheet. Captures a screenshot of the current window at the
/// moment of tapping, so the user's current screen is pre-attached.
struct ReportIssueButton: View {
    @State private var showReportSheet = false
    @State private var capturedScreenshot: UIImage?

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            capturedScreenshot = Self.captureCurrentScreen()
            showReportSheet = true
        } label: {
            Label("Report a Bug", systemImage: "exclamationmark.bubble.fill")
        }
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
