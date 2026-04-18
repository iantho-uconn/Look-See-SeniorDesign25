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

        VStack {

            Form {

                Section(header: Text("Create Account")) {

                    TextField("Email", text: $email)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)

                    SecureField("Password", text: $password)
                    
                    // Business account toggle
                    Toggle(isOn: $isBusinessAccount) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Business Account")
                            Text("Enables promotion management and video uploads")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Button {
                    signUp()
                } label: {
                    if isLoading {
                        ProgressView()
                    } else {
                        Text("Sign Up")
                    }
                }
                .disabled(isLoading || email.isEmpty || password.isEmpty)

                if !message.isEmpty {
                    Text(message)
                        .foregroundColor(didSignup ? .green : .red)
                }
                Button {
                    onGoToLogin()
                } label: {
                    Text("Already have an account? Sign in")
                        .font(.footnote)
                        .foregroundColor(.blue)
                }
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
