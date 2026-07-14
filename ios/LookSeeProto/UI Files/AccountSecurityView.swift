//
//  AccountSecurityView.swift
//  LookSeeProto
//
//  Account contact and password management backed by Amplify/Cognito.
//

import SwiftUI
import Amplify

struct AccountSecurityView: View {
    @EnvironmentObject private var vm: AuthViewModel

    @State private var currentEmail = ""
    @State private var currentPhone = ""
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("Contact Information") {
                LabeledContent("Email") {
                    Text(currentEmail.isEmpty ? "Not set" : currentEmail)
                        .foregroundStyle(.secondary)
                }

                LabeledContent("Phone") {
                    Text(currentPhone.isEmpty ? "Not set" : currentPhone)
                        .foregroundStyle(.secondary)
                }

                NavigationLink {
                    UpdateContactAttributeView(
                        kind: .email,
                        currentValue: currentEmail
                    ) {
                        await reloadAttributes()
                        await vm.fetchUserEmail()
                    }
                } label: {
                    Label("Change Email", systemImage: "envelope")
                }

                NavigationLink {
                    UpdateContactAttributeView(
                        kind: .phone,
                        currentValue: currentPhone
                    ) {
                        await reloadAttributes()
                    }
                } label: {
                    Label(
                        currentPhone.isEmpty ? "Add Phone Number" : "Change Phone Number",
                        systemImage: "phone"
                    )
                }
            } footer: {
                Text("New email addresses and phone numbers must be verified before the change is complete.")
            }

            Section("Password") {
                NavigationLink {
                    ChangePasswordView()
                } label: {
                    Label("Change Password", systemImage: "key")
                }
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
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Account & Security")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await reloadAttributes()
        }
    }

    @MainActor
    private func reloadAttributes() async {
        isLoading = true
        errorMessage = nil

        defer {
            isLoading = false
        }

        do {
            let attributes = try await Amplify.Auth.fetchUserAttributes()

            currentEmail = attributes.first(where: { $0.key == .email })?.value ?? ""
            currentPhone = attributes.first(where: { $0.key == .phoneNumber })?.value ?? ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private enum ContactAttributeKind {
    case email
    case phone

    var title: String {
        switch self {
        case .email:
            return "Change Email"
        case .phone:
            return "Phone Number"
        }
    }

    var fieldTitle: String {
        switch self {
        case .email:
            return "New email address"
        case .phone:
            return "Phone number (+1…)"
        }
    }

    var key: AuthUserAttributeKey {
        switch self {
        case .email:
            return .email
        case .phone:
            return .phoneNumber
        }
    }

    var keyboardType: UIKeyboardType {
        switch self {
        case .email:
            return .emailAddress
        case .phone:
            return .phonePad
        }
    }

    func normalized(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)

        switch self {
        case .email:
            return trimmed.lowercased()
        case .phone:
            return trimmed
                .replacingOccurrences(of: " ", with: "")
                .replacingOccurrences(of: "-", with: "")
                .replacingOccurrences(of: "(", with: "")
                .replacingOccurrences(of: ")", with: "")
        }
    }

    func validationMessage(for value: String) -> String? {
        let normalizedValue = normalized(value)

        switch self {
        case .email:
            guard normalizedValue.contains("@"),
                  normalizedValue.contains(".") else {
                return "Enter a valid email address."
            }

        case .phone:
            let pattern = #"^\+[1-9][0-9]{7,14}$"#
            guard normalizedValue.range(
                of: pattern,
                options: .regularExpression
            ) != nil else {
                return "Use E.164 format, such as +18605551234."
            }
        }

        return nil
    }
}

private struct UpdateContactAttributeView: View {
    let kind: ContactAttributeKind
    let currentValue: String
    let onCompleted: () async -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var newValue = ""
    @State private var confirmationCode = ""
    @State private var awaitingConfirmation = false
    @State private var isWorking = false
    @State private var statusMessage: String?
    @State private var isError = false

    var body: some View {
        Form {
            if !currentValue.isEmpty {
                Section("Current") {
                    Text(currentValue)
                        .foregroundStyle(.secondary)
                }
            }

            Section(awaitingConfirmation ? "Verify Change" : "New Value") {
                if awaitingConfirmation {
                    TextField("Verification code", text: $confirmationCode)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)

                    Button("Verify Code") {
                        Task {
                            await confirmChange()
                        }
                    }
                    .disabled(
                        isWorking ||
                        confirmationCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )

                    Button("Resend Code") {
                        Task {
                            await resendCode()
                        }
                    }
                    .disabled(isWorking)
                } else {
                    TextField(kind.fieldTitle, text: $newValue)
                        .keyboardType(kind.keyboardType)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)

                    Button("Send Verification Code") {
                        Task {
                            await beginChange()
                        }
                    }
                    .disabled(
                        isWorking ||
                        newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )
                }
            } footer: {
                if kind == .phone {
                    Text("Phone numbers must use international E.164 format, for example +18605551234.")
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
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    @MainActor
    private func beginChange() async {
        let value = kind.normalized(newValue)

        if let validationMessage = kind.validationMessage(for: value) {
            isError = true
            statusMessage = validationMessage
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
                userAttribute: AuthUserAttribute(kind.key, value: value)
            )

            switch result.nextStep {
            case .confirmAttributeWithCode:
                awaitingConfirmation = true
                statusMessage = "A verification code was sent to the new \(kind == .email ? "email address" : "phone number")."

            case .done:
                statusMessage = "Your \(kind == .email ? "email address" : "phone number") was updated."
                await onCompleted()
                dismiss()
            }
        } catch {
            isError = true
            statusMessage = error.localizedDescription
        }
    }

    @MainActor
    private func confirmChange() async {
        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            try await Amplify.Auth.confirm(
                userAttribute: kind.key,
                confirmationCode: confirmationCode.trimmingCharacters(in: .whitespacesAndNewlines)
            )

            statusMessage = "Verification complete."
            await onCompleted()
            dismiss()
        } catch {
            isError = true
            statusMessage = error.localizedDescription
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
                forUserAttributeKey: kind.key
            )
            statusMessage = "A new verification code was sent."
        } catch {
            isError = true
            statusMessage = error.localizedDescription
        }
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

    var body: some View {
        Form {
            Section("Password") {
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
                    isWorking ||
                    currentPassword.isEmpty ||
                    newPassword.isEmpty ||
                    confirmedPassword.isEmpty
                )
            } footer: {
                Text("The new password must satisfy the password policy configured in your Cognito user pool.")
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
        .navigationTitle("Change Password")
        .navigationBarTitleDisplayMode(.inline)
    }

    @MainActor
    private func updatePassword() async {
        guard newPassword == confirmedPassword else {
            isError = true
            statusMessage = "The new passwords do not match."
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

            statusMessage = "Password updated successfully."
            dismiss()
        } catch {
            isError = true
            statusMessage = error.localizedDescription
        }
    }
}
