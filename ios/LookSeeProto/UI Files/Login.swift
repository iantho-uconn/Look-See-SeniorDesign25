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

    @State private var username = ""
    @State private var password = ""
    @State private var showForgotPassword = false
    
    // NEW: State for the temporary password prompt
    @State private var newPasswordInput = ""

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
                .onTapGesture { hideKeyboard() } // Closes keyboard on tap

            Circle()
                .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                .frame(width: 300, height: 300)
                .blur(radius: 60)
                .offset(y: -100)
                .allowsHitTesting(false)

            // 🚀 THE FIX: ScrollView & GeometryReader allows the screen to slide up above the keyboard
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
                                Text(vm.errorMessage)
                                    .font(.caption)
                                    .foregroundStyle(.red)
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
                                    // 🚀 THE GHOST BUSTER: Forces Keychain to wipe any stuck deleted-user tokens before attempting to log in
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
                                    // 🚀 GHOST BUSTER for Guests too! Guarantees a truly clean slate.
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
                    .frame(minHeight: geo.size.height) // Centers content cleanly
                }
                .scrollDismissesKeyboard(.interactively) // Lets you pull keyboard down naturally
            }
        }
        .onChange(of: vm.isSignedIn) { _, newValue in
            if newValue {
                onSignedIn()
            }
        }
        .sheet(isPresented: $showForgotPassword) {
            ForgotPasswordView(initialUsername: username)
        }
        
        // MARK: - NEW ALERT BLOCK
        // Pops up cleanly when AWS requires a new password
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
    }
}
