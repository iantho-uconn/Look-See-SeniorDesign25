//
//  ForgotPasswordView.swift
//  LookSeeProto
//
//  Signed-out password recovery backed by Amplify/Cognito.
//

import SwiftUI
import Amplify

struct ForgotPasswordView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var username: String
    @State private var confirmationCode = ""
    @State private var newPassword = ""
    @State private var confirmedPassword = ""

    @State private var awaitingCode = false
    @State private var resetCompleted = false
    @State private var isWorking = false
    @State private var statusMessage: String?
    @State private var isError = false

    init(initialUsername: String = "") {
        _username = State(initialValue: initialUsername)
    }

    var body: some View {
        NavigationStack {
            Form {
                if resetCompleted {
                    Section {
                        Label(
                            "Your password has been reset successfully.",
                            systemImage: "checkmark.circle.fill"
                        )
                        .foregroundStyle(.green)

                        Button("Return to Sign In") {
                            dismiss()
                        }
                    } footer: {
                        Text("Use your new password the next time you sign in.")
                    }
                } else {
                    Section {
                        TextField("Email address", text: $username)
                            .keyboardType(.emailAddress)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            .disabled(awaitingCode)

                        if awaitingCode {
                            TextField("Verification code", text: $confirmationCode)
                                .keyboardType(.numberPad)
                                .textContentType(.oneTimeCode)

                            SecureField("New password", text: $newPassword)
                                .textContentType(.newPassword)

                            SecureField("Confirm new password", text: $confirmedPassword)
                                .textContentType(.newPassword)
                        }
                    } header: {
                        Text(awaitingCode ? "Reset Password" : "Find Your Account")
                    } footer: {
                        Text(
                            awaitingCode
                                ? "Enter the verification code Cognito sent to your email."
                                : "We will send a password-reset code to the verified email on your account."
                        )
                    }

                    Section {
                        Button(awaitingCode ? "Reset Password" : "Send Reset Code") {
                            Task {
                                if awaitingCode {
                                    await confirmReset()
                                } else {
                                    await requestReset()
                                }
                            }
                        }
                        .disabled(!canSubmit || isWorking)

                        if awaitingCode {
                            Button("Send Another Code") {
                                Task {
                                    await requestReset()
                                }
                            }
                            .disabled(isWorking)
                        }
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

                if let statusMessage, !resetCompleted {
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
            .navigationTitle("Forgot Password")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isWorking)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(isWorking)
                }
            }
        }
    }

    private var cleanedUsername: String {
        username
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSubmit: Bool {
        guard !cleanedUsername.isEmpty else {
            return false
        }

        if awaitingCode {
            return !confirmationCode
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
                && !newPassword.isEmpty
                && !confirmedPassword.isEmpty
        }

        return true
    }

    @MainActor
    private func requestReset() async {
        guard !cleanedUsername.isEmpty else {
            return
        }

        isWorking = true
        isError = false
        statusMessage = nil

        defer {
            isWorking = false
        }

        do {
            let result = try await Amplify.Auth.resetPassword(
                for: cleanedUsername
            )

            switch result.nextStep {
            case .confirmResetPasswordWithCode:
                awaitingCode = true
                statusMessage = "A password-reset code was sent to your verified email."

            case .done:
                resetCompleted = true
            }
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    @MainActor
    private func confirmReset() async {
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
            try await Amplify.Auth.confirmResetPassword(
                for: cleanedUsername,
                with: newPassword,
                confirmationCode: confirmationCode
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            )

            resetCompleted = true
        } catch {
            isError = true
            statusMessage = friendlyMessage(for: error)
        }
    }

    private func friendlyMessage(for error: Error) -> String {
        let description = error.localizedDescription
        let fullDescription = String(describing: error)

        if fullDescription.contains("CodeMismatchException") {
            return "That verification code is incorrect. Please try again."
        }

        if fullDescription.contains("ExpiredCodeException") {
            return "That verification code has expired. Send a new code and try again."
        }

        if fullDescription.contains("LimitExceededException")
            || fullDescription.contains("TooManyRequestsException") {
            return "Too many attempts. Please wait a moment and try again."
        }

        if fullDescription.contains("InvalidPasswordException") {
            return "The new password does not meet the Cognito password requirements."
        }

        return description
    }
}
