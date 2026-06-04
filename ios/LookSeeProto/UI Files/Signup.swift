//
//  Signup.swift
//  LookSeeProto
//
//  Created by Sheenan Ahsan on 2/27/26.
//
import SwiftUI

struct Signup: View {
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var message = ""
    @State private var isLoading = false
    @State private var didSignup = false
    @State private var isBusinessAccount = false

    var onSignupSuccess: (String) -> Void
    var onGoToLogin: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10)
                .ignoresSafeArea()

            Circle()
                .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                .frame(width: 300, height: 300)
                .blur(radius: 60)
                .offset(y: -100)

            VStack(spacing: 0) {
                Spacer()

                // Logo
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                            .frame(width: 80, height: 80)
                        Image(systemName: "eye.square.fill")
                            .font(.system(size: 40))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                    }
                    Text("LookSee")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Text("Create your account")
                        .font(.subheadline)
                        .foregroundStyle(Color.white.opacity(0.4))
                }

                Spacer()

                VStack(spacing: 14) {
                    // Email
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Email")
                            .font(.caption)
                            .foregroundStyle(Color.white.opacity(0.5))
                        TextField("you@example.com", text: $email)
                            .keyboardType(.emailAddress)
                            .autocorrectionDisabled(true)
                            .textInputAutocapitalization(.never)
                            .padding(14)
                            .background(Color(red: 0.18, green: 0.18, blue: 0.24))
                            .foregroundStyle(.white)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)
                            )
                            .colorScheme(.dark)
                    }

                    // Password
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Password")
                            .font(.caption)
                            .foregroundStyle(Color.white.opacity(0.5))
                        SecureField("••••••••", text: $password)
                            .autocorrectionDisabled(true)
                            .padding(14)
                            .background(Color(red: 0.18, green: 0.18, blue: 0.24))
                            .foregroundStyle(.white)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)
                            )
                            .colorScheme(.dark)
                    }

                    // Business account toggle
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Business Account")
                                .font(.subheadline)
                                .foregroundStyle(.white)
                            Text("Enables promotion management and video uploads")
                                .font(.caption)
                                .foregroundStyle(Color.white.opacity(0.4))
                        }
                        Spacer()
                        Toggle("", isOn: $isBusinessAccount)
                            .tint(Color(red: 0.22, green: 0.49, blue: 1.00))
                    }
                    .padding(14)
                    .background(Color(red: 0.18, green: 0.18, blue: 0.24))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.3), lineWidth: 0.5)
                    )

                    // Message
                    if !message.isEmpty {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(didSignup ? .green : .red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // Sign up button
                    Button {
                        signUp()
                    } label: {
                        HStack(spacing: 8) {
                            if isLoading {
                                ProgressView()
                                    .tint(.white)
                            } else {
                                Text("Create Account")
                                    .font(.system(size: 16, weight: .semibold))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 14, weight: .semibold))
                            }
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                        .cornerRadius(14)
                        .opacity(isLoading || email.isEmpty || password.isEmpty ? 0.5 : 1)
                    }
                    .disabled(isLoading || email.isEmpty || password.isEmpty)

                    // Go to login
                    Button {
                        onGoToLogin()
                    } label: {
                        HStack(spacing: 0) {
                            Text("Already have an account? ")
                                .foregroundStyle(Color.white.opacity(0.4))
                            Text("Sign in")
                                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                        }
                        .font(.footnote)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.bottom, 52)
            }
        }
    }

    func signUp() {
        isLoading = true
        message = ""
        let group = isBusinessAccount ? "business-users" : "authenticated-users"
        Task {
            do {
                _ = try await AuthService.shared.signUp(
                    username: email,
                    password: password,
                    email: email,
                    group: group
                )
                didSignup = true
                message = "Signup successful. Check your email for confirmation code."
                onSignupSuccess(email)
            } catch {
                didSignup = false
                message = error.localizedDescription
                print("❌ Full signup error: \(error)")
            }
            isLoading = false
        }
    }
}
