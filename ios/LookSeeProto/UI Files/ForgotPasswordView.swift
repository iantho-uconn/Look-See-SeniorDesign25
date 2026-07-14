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

    @State private var username = ""
    @State private var confirmationCode = ""
    @State private var newPassword = ""
    @State private var confirmedPassword = ""

    @State private var awaitingCode = false
    @State private var isWorking = false
    @State private var statusMessage: String?
    @State private var isError = false

    var body: some View {
        NavigationStack {
            Form {
                Section(awaitingCode ? "Reset Password" : "Find Your Account") {
                    TextField("Email, phone number, or username", text: $username)
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
                } footer: {
                    Text(
                        awaitingCode
                            ? "Enter the code Cognito sent to your verified email address or phone number."
                            : "Cognito will send a code to the verified recovery method on the account."
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
            .navigationTitle("Forgot Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
        }
    }

    private var canSubmit: Bool {
        let cleanedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanedUsername.isEmpty else {
            return false
        }

        if awaitingCode {
            return !confirmationCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                !newPassword.isEmpty &&
                !confirmedPassword.isEmpty
        }

        return true
    }

    @MainActor
    private func requestReset() async {
        let cleanedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)

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
                statusMessage = "A password-reset code was sent to the account's verified recovery method."

            case .done:
                statusMessage = "Password reset is already complete."
            }
        } catch {
            // For production, consider using a generic message so the UI does not
            // reveal whether a particular account exists.
            isError = true
            statusMessage = error.localizedDescription
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
                for: username.trimmingCharacters(in: .whitespacesAndNewlines),
                with: newPassword,
                confirmationCode: confirmationCode.trimmingCharacters(in: .whitespacesAndNewlines)
            )

            statusMessage = "Password reset successfully. You can now sign in."
        } catch {
            isError = true
            statusMessage = error.localizedDescription
        }
    }
}
