//
//  Signup.swift
//  LookSeeProto
//
//  Created by Sheenan Ahsan on 2/27/26.
//


import SwiftUI
import Amplify

struct Signup: View {
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var verificationCode = ""
    @State private var isPasswordVisible = false
    @State private var isConfirmPasswordVisible = false
    
    @State private var message = ""
    @State private var isLoading = false
    @State private var showVerification = false
    @State private var isBusinessAccount = false
    
    @EnvironmentObject var vm: AuthViewModel

    var onSignupSuccess: (String) -> Void
    var onGoToLogin: () -> Void
    
    private func isValidPassword(_ pass: String) -> Bool {
        let passwordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$"
        return NSPredicate(format: "SELF MATCHES %@", passwordRegex).evaluate(with: pass)
    }
    
    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
                .onTapGesture { hideKeyboard() }
            
            Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12)).frame(width: 300, height: 300).blur(radius: 60).offset(y: -100)

            ScrollView {
                VStack(spacing: 0) {
                    VStack(spacing: 2) {
                        Image("LookSee_Logo").resizable().scaledToFit().frame(width: 350, height: 300)
                        Text(showVerification ? "Check your email" : "Create your account").font(.subheadline).foregroundStyle(Color.white.opacity(0.4))
                    }
                    .padding(.top, 20)

                    VStack(spacing: 14) {
                        if !showVerification {
                            // MARK: - SIGNUP FORM
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Unique Username").font(.caption).foregroundStyle(Color.white.opacity(0.9))
                                TextField(
                                    "username",
                                    text: $username,
                                    prompt: Text("username").foregroundStyle(Color.white.opacity(0.6))
                                )
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled(true)
                                    .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).foregroundStyle(.white).cornerRadius(12)
                                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)).colorScheme(.dark)
                                    .onChange(of: username) { _, newValue in
                                        username = newValue.lowercased().filter { "abcdefghijklmnopqrstuvwxyz0123456789_".contains($0) }
                                    }
                            }
                            
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Email").font(.caption).foregroundStyle(Color.white.opacity(0.9))
                                TextField(
                                    "Email",
                                    text: $email,
                                    prompt: Text("you@example.com").foregroundStyle(Color.white.opacity(0.6))
                                )
                                    .keyboardType(.emailAddress).autocorrectionDisabled(true).textInputAutocapitalization(.never)
                                    .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).foregroundStyle(.white).cornerRadius(12)
                                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)).colorScheme(.dark)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("Password").font(.caption).foregroundStyle(Color.white.opacity(0.9))
                                HStack(spacing: 10) {
                                    Group {
                                        if isPasswordVisible {
                                            TextField(
                                                "Password",
                                                text: $password,
                                                prompt: Text("Enter your password").foregroundStyle(Color.white.opacity(0.6))
                                            )
                                        } else {
                                            SecureField(
                                                "Password",
                                                text: $password,
                                                prompt: Text("••••••••").foregroundStyle(Color.white.opacity(0.6))
                                            )
                                        }
                                    }
                                    .textContentType(.newPassword)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled(true)

                                    Button {
                                        isPasswordVisible.toggle()
                                    } label: {
                                        Image(systemName: isPasswordVisible ? "eye.slash.fill" : "eye.fill")
                                            .foregroundStyle(Color.white.opacity(0.75))
                                            .frame(width: 24, height: 24)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(isPasswordVisible ? "Hide password" : "Show password")
                                }
                                    .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).foregroundStyle(.white).cornerRadius(12)
                                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)).colorScheme(.dark)
                                
                                if !password.isEmpty && !isValidPassword(password) {
                                    Text("Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.")
                                        .font(.system(size: 11)).foregroundStyle(.red).padding(.leading, 4)
                                }
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("Confirm Password").font(.caption).foregroundStyle(Color.white.opacity(0.9))
                                HStack(spacing: 10) {
                                    Group {
                                        if isConfirmPasswordVisible {
                                            TextField(
                                                "Confirm Password",
                                                text: $confirmPassword,
                                                prompt: Text("Retype your password").foregroundStyle(Color.white.opacity(0.6))
                                            )
                                        } else {
                                            SecureField(
                                                "Confirm Password",
                                                text: $confirmPassword,
                                                prompt: Text("Retype your password").foregroundStyle(Color.white.opacity(0.6))
                                            )
                                        }
                                    }
                                    .textContentType(.newPassword)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled(true)

                                    Button {
                                        isConfirmPasswordVisible.toggle()
                                    } label: {
                                        Image(systemName: isConfirmPasswordVisible ? "eye.slash.fill" : "eye.fill")
                                            .foregroundStyle(Color.white.opacity(0.75))
                                            .frame(width: 24, height: 24)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(isConfirmPasswordVisible ? "Hide confirmation password" : "Show confirmation password")
                                }
                                    .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).foregroundStyle(.white).cornerRadius(12)
                                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)).colorScheme(.dark)

                                if !confirmPassword.isEmpty && password != confirmPassword {
                                    Text("Passwords do not match.")
                                        .font(.system(size: 11)).foregroundStyle(.red).padding(.leading, 4)
                                }
                            }

                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Business Account").font(.subheadline).foregroundStyle(.white)
                                    Text("Enables promotion management and video uploads").font(.caption).foregroundStyle(Color.white.opacity(0.4))
                                }
                                Spacer()
                                Toggle("", isOn: $isBusinessAccount).tint(Color(red: 0.22, green: 0.49, blue: 1.00))
                            }
                            .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).cornerRadius(12).overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5))
                            
                        } else {
                            // MARK: - VERIFICATION FORM
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Enter 6-Digit Code").font(.caption).foregroundStyle(Color.white.opacity(0.9))
                                TextField(
                                    "Verification Code",
                                    text: $verificationCode,
                                    prompt: Text("123456").foregroundStyle(Color.white.opacity(0.6))
                                )
                                    .keyboardType(.numberPad)
                                    .font(.system(size: 24, weight: .bold, design: .monospaced))
                                    .multilineTextAlignment(.center)
                                    .padding(14).background(Color(red: 0.18, green: 0.18, blue: 0.24)).foregroundStyle(.white).cornerRadius(12)
                                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)).colorScheme(.dark)
                            }
                        }

                        if !message.isEmpty {
                            Text(message).font(.caption).foregroundStyle(message.contains("successful") || message.contains("verified") ? .green : .red).frame(maxWidth: .infinity, alignment: .leading)
                        }

                        Button {
                            if showVerification { verifyCode() } else { signUp() }
                        } label: {
                            HStack(spacing: 8) {
                                if isLoading {
                                    ProgressView().tint(.white)
                                } else {
                                    Text(showVerification ? "Verify Account" : "Create Account").font(.system(size: 16, weight: .semibold))
                                    Image(systemName: "arrow.right").font(.system(size: 14, weight: .semibold))
                                }
                            }
                            .foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 16).background(Color(red: 0.22, green: 0.49, blue: 1.00)).cornerRadius(14)
                            .opacity(isLoading || (showVerification ? verificationCode.count < 6 : (email.isEmpty || username.isEmpty || !isValidPassword(password) || confirmPassword.isEmpty || password != confirmPassword)) ? 0.5 : 1)
                        }
                        .disabled(isLoading || (showVerification ? verificationCode.count < 6 : (email.isEmpty || username.isEmpty || !isValidPassword(password) || confirmPassword.isEmpty || password != confirmPassword)))
                        .padding(.top, 16)

                        if !showVerification {
                            Button { onGoToLogin() } label: {
                                HStack(spacing: 0) {
                                    Text("Already have an account? ").foregroundStyle(Color.white.opacity(0.4))
                                    Text("Sign in").foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                }.font(.footnote)
                            }
                            .padding(.top, 8)
                        }
                    }
                    .padding(.horizontal, 28).padding(.bottom, 52)
                }
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    // MARK: - AWS Logic
    func signUp() {
        guard password == confirmPassword else {
            message = "Passwords do not match."
            return
        }

        isLoading = true
        message = ""
        let group = isBusinessAccount ? "business-users" : "authenticated-users"
        Task {
            do {
                let result = try await AuthService.shared.signUp(username: email, password: password, email: email, group: group)
                
                if result.isSignUpComplete {
                    // 🚀 FIXED: Memorize username to be processed AFTER login!
                    await MainActor.run { vm.pendingUsernameToSave = username }
                    message = "Account created and verified! Routing to login..."
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                        onSignupSuccess(email)
                    }
                } else {
                    withAnimation { showVerification = true }
                    message = "Code sent! Please check your email."
                }
            } catch {
                message = error.localizedDescription
            }
            isLoading = false
        }
    }
    
    func verifyCode() {
        isLoading = true
        message = ""
        Task {
            do {
                let result = try await Amplify.Auth.confirmSignUp(for: email, confirmationCode: verificationCode)
                if result.isSignUpComplete {
                    // 🚀 FIXED: Memorize username to be processed AFTER login!
                    await MainActor.run { vm.pendingUsernameToSave = username }
                    message = "Verification successful! Routing to login..."
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        onSignupSuccess(email)
                    }
                } else {
                    message = "Verification incomplete. Please check the code."
                }
            } catch {
                message = error.localizedDescription
            }
            isLoading = false
        }
    }
}
