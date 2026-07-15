//
//  AccountSecurityView.swift
//  LookSeeProto
//
//  Signed-in email and password management backed by Amplify/Cognito.
//

import SwiftUI
import Amplify

struct AccountSecurityView: View {
    @EnvironmentObject private var vm: AuthViewModel

    @State private var currentEmail = ""
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                LabeledContent("Current email") {
                    Text(currentEmail.isEmpty ? "Not set" : currentEmail)
                        .foregroundStyle(.secondary)
                }

                NavigationLink {
                    UpdateEmailView(
                        currentEmail: currentEmail,
                        onCompleted: refreshAccount
                    )
                } label: {
                    Label("Change Email", systemImage: "envelope")
                }
            } header: {
                Text("Email")
            } footer: {
                Text("Cognito will send a verification code to the new email address before completing the change.")
            }

            Section {
                NavigationLink {
                    ChangePasswordView()
                } label: {
                    Label("Change Password", systemImage: "key")
                }
            } header: {
                Text("Password")
            } footer: {
                Text("Changing your password requires your current password.")
            }

            if isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView("Loading account…")
                        Spacer()
                    }
                }
            }

            if let errorMessage {
                Section {
                    Label(
                        errorMessage,
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Account & Security")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await refreshAccount()
        }
    }

    @MainActor
    private func refreshAccount() async {
        isLoading = true
        errorMessage = nil

        defer {
            isLoading = false
        }

        do {
            _ = try? await Amplify.Auth.fetchAuthSession(
                options: .forceRefresh()
            )

            let attributes = try await Amplify.Auth.fetchUserAttributes()

            currentEmail = attributes
                .first(where: { $0.key == .email })?
                .value ?? ""

            await vm.fetchUserEmail()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct UpdateEmailView: View {
    let currentEmail: String
    let onCompleted: () async -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var newEmail = ""
    @State private var confirmationCode = ""
    @State private var awaitingConfirmation = false
    @State private var isWorking = false
    @State private var statusMessage: String?
    @State private var isError = false

    var body: some View {
        Form {
            if !currentEmail.isEmpty {
                Section {
                    Text(currentEmail)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Current Email")
                }
            }

            Section {
                if awaitingConfirmation {
                    TextField("Verification code", text: $confirmationCode)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)

                    Button("Verify Email") {
                        Task {
                            await confirmEmailChange()
                        }
                    }
                    .disabled(
                        isWorking
                            || confirmationCode
                                .trimmingCharacters(in: .whitespacesAndNewlines)
                                .isEmpty
                    )

                    Button("Resend Code") {
                        Task {
                            await resendCode()
                        }
                    }
                    .disabled(isWorking)
                } else {
                    TextField("New email address", text: $newEmail)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)

                    Button("Send Verification Code") {
                        Task {
                            await beginEmailChange()
                        }
                    }
                    .disabled(
                        isWorking
                            || newEmail
                                .trimmingCharacters(in: .whitespacesAndNewlines)
                                .isEmpty
                    )
                }
            } header: {
                Text(awaitingConfirmation ? "Verify New Email" : "New Email")
            } footer: {
                if awaitingConfirmation {
                    Text("Enter the code sent to the new email address.")
                } else {
                    Text("Your current account remains signed in while the new address is verified.")
                }
            }

            if isWorking {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            }

            if let statusMessage {
                Section {
                    Label(
                        statusMessage,
                        systemImage: isError
                            ? "exclamationmark.triangle.fill"
                            : "checkmark.circle.fill"
                    )
                    .foregroundStyle(isError ? .orange : .green)
                }
            }
        }
        .navigationTitle("Change Email")
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(isWorking)
    }

    private var cleanedEmail: String {
        newEmail
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    @MainActor
    private func beginEmailChange() async {
        guard isValidEmail(cleanedEmail) else {
            isError = true
            statusMessage = "Enter a valid email address."
            return
        }

        guard cleanedEmail != currentEmail.lowercased() else {
            isError = true
            statusMessage = "That is already the email address on this account."
            return
        }

        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            let result = try await Amplify.Auth.update(
                userAttribute: AuthUserAttribute(
                    .email,
                    value: cleanedEmail
                )
            )

            switch result.nextStep {
            case .confirmAttributeWithCode:
                awaitingConfirmation = true
                statusMessage = "A verification code was sent to the new email address."

            case .done:
                await onCompleted()
                dismiss()
            }
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    @MainActor
    private func confirmEmailChange() async {
        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            try await Amplify.Auth.confirm(
                userAttribute: .email,
                confirmationCode: confirmationCode
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            )

            await onCompleted()
            dismiss()
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    @MainActor
    private func resendCode() async {
        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            _ = try await Amplify.Auth.sendVerificationCode(
                forUserAttributeKey: .email
            )

            statusMessage = "A new verification code was sent."
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    private func isValidEmail(_ value: String) -> Bool {
        let pattern = #"^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$"#

        return value.range(
            of: pattern,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
    }

    private func friendlyMessage(for error: Error) -> String {
        let description = error.localizedDescription
        let fullDescription = String(describing: error)

        if fullDescription.contains("AliasExistsException") {
            return "That email address is already connected to another account."
        }

        if fullDescription.contains("CodeMismatchException") {
            return "That verification code is incorrect."
        }

        if fullDescription.contains("ExpiredCodeException") {
            return "That verification code has expired. Send a new code and try again."
        }

        if fullDescription.contains("LimitExceededException")
            || fullDescription.contains("TooManyRequestsException") {
            return "Too many attempts. Please wait a moment and try again."
        }

        return description
    }
}

private struct ChangePasswordView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmedPassword = ""
    @State private var isWorking = false
    @State private var statusMessage: String?
    @State private var isError = false
    @State private var passwordChanged = false

    var body: some View {
        Form {
            if passwordChanged {
                Section {
                    Label(
                        "Password updated successfully.",
                        systemImage: "checkmark.circle.fill"
                    )
                    .foregroundStyle(.green)

                    Button("Done") {
                        dismiss()
                    }
                }
            } else {
                Section {
                    SecureField("Current password", text: $currentPassword)
                        .textContentType(.password)

                    SecureField("New password", text: $newPassword)
                        .textContentType(.newPassword)

                    SecureField("Confirm new password", text: $confirmedPassword)
                        .textContentType(.newPassword)

                    Button("Update Password") {
                        Task {
                            await updatePassword()
                        }
                    }
                    .disabled(
                        isWorking
                            || currentPassword.isEmpty
                            || newPassword.isEmpty
                            || confirmedPassword.isEmpty
                    )
                } header: {
                    Text("Password")
                } footer: {
                    Text("The new password must satisfy the password policy configured in your Cognito user pool.")
                }
            }

            if isWorking {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            }

            if let statusMessage, !passwordChanged {
                Section {
                    Label(
                        statusMessage,
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Change Password")
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(isWorking)
    }

    @MainActor
    private func updatePassword() async {
        guard newPassword == confirmedPassword else {
            isError = true
            statusMessage = "The new passwords do not match."
            return
        }

        guard currentPassword != newPassword else {
            isError = true
            statusMessage = "Your new password must be different from your current password."
            return
        }

        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            try await Amplify.Auth.update(
                oldPassword: currentPassword,
                to: newPassword
            )

            currentPassword = ""
            newPassword = ""
            confirmedPassword = ""
            passwordChanged = true
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    private func friendlyMessage(for error: Error) -> String {
        let description = error.localizedDescription
        let fullDescription = String(describing: error)

        if fullDescription.contains("NotAuthorizedException") {
            return "The current password is incorrect."
        }

        if fullDescription.contains("InvalidPasswordException") {
            return "The new password does not meet the Cognito password requirements."
        }

        if fullDescription.contains("LimitExceededException")
            || fullDescription.contains("TooManyRequestsException") {
            return "Too many attempts. Please wait a moment and try again."
        }

        return description
    }
}
