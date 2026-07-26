//
//  GuestSignUpView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//

import SwiftUI
import Amplify

struct GuestSignUpView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var authState: AuthState
    @EnvironmentObject var vm: AuthViewModel
    
    @State private var email = ""
    @State private var password = ""
    @State private var phoneNumber = ""
    @State private var verificationCode = ""
    
    @State private var showVerification = false
    @State private var isProcessing = false
    @State private var errorMessage = ""
    
    private var sanitizedEmail: String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
    
    private var sanitizedCode: String {
        verificationCode.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    
    private func isValidPassword(_ pass: String) -> Bool {
        let passwordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$"
        return NSPredicate(format: "SELF MATCHES %@", passwordRegex).evaluate(with: pass)
    }
    
    private var isFormValid: Bool {
        !sanitizedEmail.isEmpty &&
        !phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        isValidPassword(password)
    }
    
    private var isVerificationValid: Bool {
        sanitizedCode.count >= 6
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()
                    .onTapGesture { hideKeyboard() }
                
                ScrollView {
                    VStack(spacing: 24) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(showVerification ? "Verify Email" : "Create Account")
                                .font(.system(size: 28, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                            Text(showVerification ? "Enter the 6-digit code we sent to \(sanitizedEmail)." : "Set up your LookSee identity to proceed to secure checkout.")
                                .font(.system(size: 14))
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 16)
                        
                        if !showVerification {
                            VStack(spacing: 18) {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Email Address").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                    TextField("name@example.com", text: $email)
                                        .keyboardType(.emailAddress)
                                        .textInputAutocapitalization(.never)
                                        .autocorrectionDisabled(true)
                                        .padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                                }
                                
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Password").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                    SecureField("Create a strong password", text: $password)
                                        .padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                                    
                                    if !password.isEmpty && !isValidPassword(password) {
                                        Text("Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.")
                                            .font(.system(size: 11)).foregroundStyle(.red).padding(.leading, 4)
                                    }
                                }
                                
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Phone Number").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                    TextField("123-456-7890", text: $phoneNumber)
                                        .keyboardType(.phonePad)
                                        .padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                                        .onChange(of: phoneNumber) { _, newValue in
                                            let filtered = newValue.filter { "0123456789".contains($0) }
                                            if filtered.count > 10 {
                                                phoneNumber = String(filtered.prefix(10))
                                            } else if phoneNumber != filtered {
                                                phoneNumber = filtered
                                            }
                                        }
                                }
                            }
                        } else {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Verification Code").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                TextField("123456", text: $verificationCode)
                                    .keyboardType(.numberPad)
                                    .font(.system(size: 24, weight: .bold, design: .monospaced))
                                    .multilineTextAlignment(.center)
                                    .padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                            }
                        }
                        
                        if !errorMessage.isEmpty {
                            Text(errorMessage)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.red)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        
                        Spacer().frame(height: 32)
                        
                        Button {
                            if showVerification { verifyCodeAndSignIn() } else { signUp() }
                        } label: {
                            HStack(spacing: 8) {
                                if isProcessing {
                                    ProgressView().tint(.white)
                                } else {
                                    Text(showVerification ? "Verify & Continue" : "Create Account")
                                        .font(.system(size: 16, weight: .bold))
                                }
                            }
                            .foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 16)
                            .background((showVerification ? isVerificationValid : isFormValid) && !isProcessing ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.gray.opacity(0.3))
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .disabled((showVerification ? !isVerificationValid : !isFormValid) || isProcessing)
                    }
                    .padding(.horizontal, 24)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 24)).foregroundStyle(.gray.opacity(0.8)) }
                }
            }
        }
    }
    
    private func signUp() {
        isProcessing = true
        errorMessage = ""
        Task {
            do {
                let result = try await AuthService.shared.signUp(username: sanitizedEmail, password: password, email: sanitizedEmail, group: "business-users")
                
                if result.isSignUpComplete {
                    _ = await Amplify.Auth.signOut()
                    let signInResult = try await AuthService.shared.signIn(username: sanitizedEmail, password: password)
                    if signInResult.isSignedIn {
                        await vm.fetchUserDetails()
                        await vm.fetchUserUsageStats() // 🚀 FORCES DATA SYNC
                        DispatchQueue.main.async {
                            vm.isSignedIn = true
                            dismiss()
                        }
                    } else {
                        errorMessage = "Account created successfully! Please log in."
                    }
                } else {
                    withAnimation { showVerification = true }
                }
            } catch let error as AuthError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
            isProcessing = false
        }
    }
    
    private func verifyCodeAndSignIn() {
        isProcessing = true
        errorMessage = ""
        Task {
            do {
                let result = try await Amplify.Auth.confirmSignUp(for: sanitizedEmail, confirmationCode: sanitizedCode)
                if result.isSignUpComplete {
                    _ = await Amplify.Auth.signOut()
                    let signInResult = try await AuthService.shared.signIn(username: sanitizedEmail, password: password)
                    if signInResult.isSignedIn {
                        await vm.fetchUserDetails()
                        await vm.fetchUserUsageStats() // 🚀 FORCES DATA SYNC
                        DispatchQueue.main.async {
                            vm.isSignedIn = true
                            dismiss()
                        }
                    } else {
                        errorMessage = "Verified successfully, but please log in manually from the main menu."
                    }
                } else {
                    errorMessage = "Verification incomplete. Please check the code."
                }
            } catch let error as AuthError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
            isProcessing = false
        }
    }
}
