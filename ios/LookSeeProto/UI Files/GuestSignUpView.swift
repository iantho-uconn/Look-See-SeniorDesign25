//
//  GuestSignUpView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//

import SwiftUI

struct GuestSignUpView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var authState: AuthState
    
    @State private var email = ""
    @State private var password = ""
    @State private var phoneNumber = ""
    @State private var showSubscriptionPlans = false
    
    private var isFormValid: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 24) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Create Account")
                                .font(.system(size: 28, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                            Text("Set up your LookSee identity before selecting a premium business plan.")
                                .font(.system(size: 14))
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 16)
                        
                        VStack(spacing: 18) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Email Address")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.secondary)
                                TextField("name@example.com", text: $email)
                                    .keyboardType(.emailAddress)
                                    .textInputAutocapitalization(.never)
                                    .padding()
                                    .background(Color.white.opacity(0.05))
                                    .cornerRadius(12)
                                    .foregroundStyle(.white)
                            }
                            
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Password")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.secondary)
                                SecureField("Create a strong password", text: $password)
                                    .padding()
                                    .background(Color.white.opacity(0.05))
                                    .cornerRadius(12)
                                    .foregroundStyle(.white)
                            }
                            
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Phone Number")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.secondary)
                                TextField("e.g., 860-555-0199", text: $phoneNumber)
                                    .keyboardType(.phonePad)
                                    .padding()
                                    .background(Color.white.opacity(0.05))
                                    .cornerRadius(12)
                                    .foregroundStyle(.white)
                            }
                        }
                        
                        Spacer()
                            .frame(height: 32)
                        
                        Button {
                            authState.tier = .authenticated
                            showSubscriptionPlans = true
                        } label: {
                            Text("Create Account & Continue")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(isFormValid ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.gray.opacity(0.3))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .disabled(!isFormValid)
                    }
                    .padding(.horizontal, 24)
                }
            }
            .ignoresSafeArea(.keyboard, edges: .bottom)
            .navigationDestination(isPresented: $showSubscriptionPlans) {
                SubscriptionPlans()
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 24))
                            .foregroundStyle(.gray.opacity(0.8))
                    }
                }
            }
        }
    }
}
