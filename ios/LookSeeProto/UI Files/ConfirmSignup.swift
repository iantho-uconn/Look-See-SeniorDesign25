////
////  ConfirmSignup.swift
////  LookSeeProto
////
////  Confirmation screen that works with either email or SMS delivery.
////
//
//import SwiftUI
//import Amplify
//
//struct ConfirmSignup: View {
//    // Keep the existing external label so current callers do not break.
//    var email: String
//    var deliveryHint: String? = nil
//    var onConfirmed: () -> Void
//
//    @State private var username = ""
//    @State private var code = ""
//    @State private var message = ""
//    @State private var isLoading = false
//    @State private var confirmed = false
//
//    var body: some View {
//        Form {
//            Section {
//                TextField("Email, phone number, or username", text: $username)
//                    .autocorrectionDisabled(true)
//                    .textInputAutocapitalization(.never)
//
//                TextField("Verification Code", text: $code)
//                    .keyboardType(.numberPad)
//                    .textContentType(.oneTimeCode)
//            } header: {
//                Text("Confirm Account")
//            } footer: {
//                Text(
//                    deliveryHint ??
//                    "Enter the verification code sent to the email address or phone number selected during sign-up."
//                )
//            }
//
//            Button {
//                confirmAccount()
//            } label: {
//                if isLoading {
//                    ProgressView()
//                } else {
//                    Text("Confirm")
//                }
//            }
//            .disabled(
//                isLoading ||
//                username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
//                code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
//            )
//
//            Button("Resend Code") {
//                resendCode()
//            }
//            .disabled(
//                isLoading ||
//                username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
//            )
//
//            if !message.isEmpty {
//                Label(
//                    message,
//                    systemImage: confirmed
//                        ? "checkmark.circle.fill"
//                        : "exclamationmark.triangle.fill"
//                )
//                .foregroundStyle(confirmed ? .green : .orange)
//            }
//
//            if confirmed {
//                Text("You can now sign in.")
//                    .font(.caption)
//                    .foregroundStyle(.secondary)
//            }
//        }
//        .onAppear {
//            username = email
//        }
//    }
//
//    private func confirmAccount() {
//        isLoading = true
//        message = ""
//
//        Task {
//            defer {
//                isLoading = false
//            }
//
//            do {
//                let result = try await Amplify.Auth.confirmSignUp(
//                    for: username.trimmingCharacters(in: .whitespacesAndNewlines),
//                    confirmationCode: code.trimmingCharacters(in: .whitespacesAndNewlines)
//                )
//
//                guard result.isSignUpComplete else {
//                    confirmed = false
//                    message = "Cognito requires another confirmation step."
//                    return
//                }
//
//                confirmed = true
//                message = "Account confirmed successfully!"
//                onConfirmed()
//            } catch {
//                confirmed = false
//                message = error.localizedDescription
//            }
//        }
//    }
//
//    private func resendCode() {
//        isLoading = true
//        message = ""
//
//        Task {
//            defer {
//                isLoading = false
//            }
//
//            do {
//                _ = try await Amplify.Auth.resendSignUpCode(
//                    for: username.trimmingCharacters(in: .whitespacesAndNewlines)
//                )
//
//                confirmed = false
//                message = "A new verification code was sent."
//            } catch {
//                confirmed = false
//                message = error.localizedDescription
//            }
//        }
//    }
//}
