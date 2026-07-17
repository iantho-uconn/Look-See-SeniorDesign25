//
//  BusinessSetup.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//

import SwiftUI

struct BusinessSetup: View {
    let selectedPlanIndex: Int
    let isAnnualPlan: Bool
    
    @State private var businessName = ""
    @State private var industryType = ""
    @State private var contactPhone = ""
    @State private var navigateToCheckout = false
    
    private var isFormValid: Bool {
        !businessName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !industryType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !contactPhone.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    
    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10)
                .ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Profile")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                        Text("Tell us a little bit about your business venue before moving to secure checkout.")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.top, 16)
                    
                    VStack(spacing: 18) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Business Name")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.secondary)
                            TextField("e.g., Mystic Seaport Museum", text: $businessName)
                                .padding()
                                .background(Color.white.opacity(0.05))
                                .cornerRadius(12)
                                .foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Venue / Industry Type")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.secondary)
                            TextField("e.g., Museum, Retail, University", text: $industryType)
                                .padding()
                                .background(Color.white.opacity(0.05))
                                .cornerRadius(12)
                                .foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Contact Phone Number")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.secondary)
                            TextField("e.g., 860-555-0199", text: $contactPhone)
                                .keyboardType(.phonePad)
                                .padding()
                                .background(Color.white.opacity(0.05))
                                .cornerRadius(12)
                                .foregroundStyle(.white)
                        }
                    }
                    .padding(.horizontal, 24)
                    
                    Spacer()
                        .frame(height: 32)
                    
                    Button {
                        navigateToCheckout = true
                    } label: {
                        Text("Proceed to Secure Payment")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(isFormValid ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.gray.opacity(0.3))
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .disabled(!isFormValid)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
                }
            }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .navigationDestination(isPresented: $navigateToCheckout) {
            StripeCheckoutView()
        }
    }
}
