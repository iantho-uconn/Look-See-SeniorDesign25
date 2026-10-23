//
//  Login.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/17/25.
//


import SwiftUI
import Amplify

struct Login: View {
    @ObservedObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState

    @State private var username = ""
    @State private var password = ""
    @State private var showForgotPassword = false
    
    // NEW: State for the temporary password prompt
    @State private var newPasswordInput = ""
    
    // 🚀 NEW: State variables to catch the unverified email loop
    @State private var showVerificationAlert = false
    @State private var verificationCode = ""
    
    @FocusState private var IsKeyboard: Bool


    var onSignedIn: () -> Void
    var onGoToSignup: () -> Void
    var onContinueAsGuest: () -> Void
    
    // 🚀 THE FIX: Sanitizes email so iOS AutoFill doesn't cause AWS errors
    private var sanitizedUsername: String {
        username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    // 🚀 THE FIX: Function to dismiss the keyboard when tapping the background
    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10)
                .ignoresSafeArea()
                .onTapGesture { hideKeyboard() }

            Circle()
                .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                .frame(width: 300, height: 300)
                .blur(radius: 60)
                .offset(y: -100)
                .allowsHitTesting(false)

            GeometryReader { geo in
                ScrollView {
                    VStack(spacing: 0) {
                        Spacer()

                        VStack(spacing: 12) {
                            Image("LookSee_Logo")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 350, height: 300)

                            Text("Sign in to continue")
                                .font(.subheadline)
                                .foregroundStyle(Color.white.opacity(0.4))
                        }

                        Spacer().frame(height: 32)

                        VStack(spacing: 14) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Email")
                                    .font(.caption)
                                    .foregroundStyle(Color.white.opacity(0.5))

                                TextField("you@example.com", text: $username)
                                    .textContentType(.username)
                                    .autocorrectionDisabled(true)
                                    .textInputAutocapitalization(.never)
                                    .focused($IsKeyboard)
                                    .keyboardType(.emailAddress)
                                    .padding(14)
                                    .background(Color(red: 0.18, green: 0.18, blue: 0.24))
                                    .foregroundStyle(.white)
                                    .cornerRadius(12)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12)
                                            .stroke(
                                                Color(red: 0.22, green: 0.49, blue: 1.00)
                                                    .opacity(0.3),
                                                lineWidth: 0.5
                                            )
                                    )
                                    .colorScheme(.dark)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("Password")
                                    .font(.caption)
                                    .foregroundStyle(Color.white.opacity(0.5))

                                SecureField("••••••••", text: $password)
                                    .focused($IsKeyboard)
                                    .textContentType(.password)
                                    .autocorrectionDisabled(true)
                                    .padding(14)
                                    .background(Color(red: 0.18, green: 0.18, blue: 0.24))
                                    .foregroundStyle(.white)
                                    .cornerRadius(12)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12)
                                            .stroke(
                                                Color(red: 0.22, green: 0.49, blue: 1.00)
                                                    .opacity(0.3),
                                                lineWidth: 0.5
                                            )
                                    )
                                    .colorScheme(.dark)

                                HStack {
                                    Spacer()

                                    Button("Forgot password?") {
                                        vm.errorMessage = ""
                                        showForgotPassword = true
                                    }
                                    .font(.footnote.weight(.medium))
                                    .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                }
                            }

                            if !vm.errorMessage.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(vm.errorMessage)
                                        .font(.caption)
                                        .foregroundStyle(.red)
                                    
                                    // 🚀 THE FIX: Dynamically detects unverified emails and provides an escape hatch
                                    if vm.errorMessage.lowercased().contains("verif") {
                                        Button {
                                            hideKeyboard()
                                            showVerificationAlert = true
                                        } label: {
                                            Text("Account unverified? Tap here to enter code.")
                                                .font(.caption.bold())
                                                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                        }
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }

                            if vm.isSignedIn {
                                Text("Signed in successfully!")
                                    .font(.caption)
                                    .foregroundStyle(.green)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }

                            Button {
                                hideKeyboard()
                                Task {
                                    _ = await Amplify.Auth.signOut()
                                    
                                    await MainActor.run {
                                        vm.signIn(username: sanitizedUsername, password: password)
                                    }
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Text("Sign In")
                                        .font(.system(size: 16, weight: .semibold))

                                    Image(systemName: "arrow.right")
                                        .font(.system(size: 14, weight: .semibold))
                                }
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                                .cornerRadius(14)
                            }
                            .disabled(sanitizedUsername.isEmpty || password.isEmpty)
                            .opacity(sanitizedUsername.isEmpty || password.isEmpty ? 0.5 : 1)

                            HStack(spacing: 12) {
                                Rectangle()
                                    .fill(Color.white.opacity(0.1))
                                    .frame(height: 0.5)

                                Text("or")
                                    .font(.caption)
                                    .foregroundStyle(Color.white.opacity(0.3))

                                Rectangle()
                                    .fill(Color.white.opacity(0.1))
                                    .frame(height: 0.5)
                            }

                            Button {
                                hideKeyboard()
                                Task {
                                    _ = await Amplify.Auth.signOut()
                                    
                                    await MainActor.run {
                                        onContinueAsGuest()
                                    }
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "person")
                                        .font(.system(size: 14, weight: .medium))

                                    Text("Continue as Guest")
                                        .font(.system(size: 15, weight: .medium))
                                }
                                .foregroundStyle(Color.white.opacity(0.6))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color.white.opacity(0.07))
                                .cornerRadius(14)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(Color.white.opacity(0.1), lineWidth: 0.5)
                                )
                            }

                            Button {
                                hideKeyboard()
                                onGoToSignup()
                            } label: {
                                HStack(spacing: 0) {
                                    Text("Don't have an account? ")
                                        .foregroundStyle(Color.white.opacity(0.4))

                                    Text("Sign up")
                                        .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                                }
                                .font(.footnote)
                            }
                        }
                        .padding(.horizontal, 28)
                        .padding(.bottom, max(geo.safeAreaInsets.bottom + 20, 52))
                    }
                    .frame(minHeight: geo.size.height)
                }
                .contentShape(Rectangle())
                            .onTapGesture {
                                IsKeyboard = false
                            }
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .onChange(of: vm.isSignedIn) { _, newValue in
            if newValue {
                Task { await authState.resolveTier() }
                onSignedIn()
            }
        }
        .sheet(isPresented: $showForgotPassword) {
            ForgotPasswordView(initialUsername: username)
        }
        
        .alert("Update Password", isPresented: $vm.requiresNewPassword) {
            SecureField("New Password", text: $newPasswordInput)
            
            Button("Update & Sign In") {
                vm.confirmNewPassword(newPassword: newPasswordInput)
            }
            
            Button("Cancel", role: .cancel) {
                newPasswordInput = ""
                vm.errorMessage = ""
            }
        } message: {
            Text("Your account has a temporary password. Please create a new permanent password.")
        }
        
        // 🚀 THE FIX: The missing link verification popup
        .alert("Verify Email", isPresented: $showVerificationAlert) {
            TextField("Verification Code", text: $verificationCode)
                .focused($IsKeyboard)
                .keyboardType(.numberPad)
            
            Button("Verify") {
                confirmVerification()
            }
            
            Button("Resend Code") {
                resendVerificationCode()
            }
            
            Button("Cancel", role: .cancel) {
                verificationCode = ""
            }
        }
        message: {
            Text("Enter the 6-digit code sent to \(sanitizedUsername).")
        }
    }
    
    // MARK: - Escape Hatch Functions
    
    private func confirmVerification() {
        Task {
            do {
                let result = try await Amplify.Auth.confirmSignUp(for: sanitizedUsername, confirmationCode: verificationCode)
                await MainActor.run {
                    if result.isSignUpComplete {
                        showVerificationAlert = false
                        vm.errorMessage = ""
                        verificationCode = ""
                        // 🚀 Log them straight in since they already typed their password!
                        vm.signIn(username: sanitizedUsername, password: password)
                    } else {
                        vm.errorMessage = "Verification incomplete. Please try again."
                    }
                }
            } catch {
                await MainActor.run {
                    vm.errorMessage = "Verification failed: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func resendVerificationCode() {
        Task {
            do {
                _ = try await Amplify.Auth.resendSignUpCode(for: sanitizedUsername)
                await MainActor.run {
                    vm.errorMessage = "A new code was sent! Tap 'Account unverified' to enter it."
                }
            } catch {
                await MainActor.run {
                    vm.errorMessage = "Failed to resend code: \(error.localizedDescription)"
                }
            }
        }
    }
}
