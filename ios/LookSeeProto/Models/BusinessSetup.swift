//
//  BusinessSetup.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//


import SwiftUI
import StripePaymentSheet

struct BusinessSetup: View {
    let selectedPlanIndex: Int
    let isAnnualPlan: Bool
    
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var authState: AuthState
    @EnvironmentObject var vm: AuthViewModel
    
    @State private var businessName = ""
    @State private var industryType = ""
    @State private var contactPhone = ""
    
    @State private var isProcessing = false
    @State private var paymentSheet: PaymentSheet?
    @State private var showPaymentSheet = false
    @State private var paymentStatusMessage: String?
    
    // Strict 10-digit US phone validation
    private var cleanPhoneDigits: String {
        contactPhone.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
    }
    
    private var isFormValid: Bool {
        !businessName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !industryType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        cleanPhoneDigits.count == 10
    }
    
    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
            
            ScrollView {
                VStack(spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Business Profile").font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.white)
                        Text("Tell us a little bit about your business venue before moving to secure checkout.").font(.system(size: 14)).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 24).padding(.top, 16)
                    
                    VStack(spacing: 18) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Business Name").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                            TextField("e.g., Mystic Seaport Museum", text: $businessName).padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Venue / Industry Type").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                            TextField("e.g., Museum, Retail, University", text: $industryType).padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                        }
                        
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Contact Phone Number").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                            TextField("10-digit phone number", text: $contactPhone)
                                .keyboardType(.numberPad)
                                .onChange(of: contactPhone) { _, newValue in
                                    formatPhoneNumber(newValue)
                                }
                                .padding().background(Color.white.opacity(0.05)).cornerRadius(12).foregroundStyle(.white)
                            
                            if !contactPhone.isEmpty && cleanPhoneDigits.count != 10 {
                                Text("Please enter a valid 10-digit phone number.")
                                    .font(.system(size: 11))
                                    .foregroundStyle(.red)
                                    .padding(.leading, 4)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    
                    if let message = paymentStatusMessage {
                        Text(message)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(message.contains("successful") ? .green : .red)
                            .padding(.horizontal, 24)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    
                    Spacer().frame(height: 32)
                    
                    Button {
                        Task { await preparePaymentSheet() }
                    } label: {
                        if isProcessing {
                            ProgressView().tint(.white)
                        } else {
                            Text("Proceed to Secure Payment").font(.system(size: 16, weight: .bold))
                        }
                    }
                    .foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(isFormValid && !isProcessing ? Color(red: 0.22, green: 0.49, blue: 1.00) : Color.gray.opacity(0.3)).clipShape(RoundedRectangle(cornerRadius: 14))
                    .disabled(!isFormValid || isProcessing).padding(.horizontal, 24).padding(.bottom, 24)
                }
            }
        }
        .background(
            Group {
                if let ps = paymentSheet {
                    Color.clear.paymentSheet(isPresented: $showPaymentSheet, paymentSheet: ps, onCompletion: onPaymentCompletion)
                }
            }
        )
    }
    
    // Auto-format phone input to (XXX) XXX-XXXX and limit to 10 digits
    private func formatPhoneNumber(_ input: String) {
        let numbers = input.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
        let max10 = String(numbers.prefix(10))
        
        var result = ""
        for (index, ch) in max10.enumerated() {
            if index == 0 { result.append("(") }
            if index == 3 { result.append(") ") }
            if index == 6 { result.append("-") }
            result.append(ch)
        }
        contactPhone = result
    }
    
    private func preparePaymentSheet() async {
        isProcessing = true
        paymentStatusMessage = nil
        await vm.fetchUserDetails()
        
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else {
            isProcessing = false; paymentStatusMessage = "Invalid API URL."; return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = [
            "purchaseType": "subscription",
            "userId": vm.userId,
            "userEmail": vm.userEmail,
            "selectedPlanIndex": selectedPlanIndex,
            "stripeSubscriptionId": vm.stripeSubscriptionId
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let rawString = String(data: data, encoding: .utf8) ?? "Empty Response"
            
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
                DispatchQueue.main.async {
                    self.isProcessing = false
                    self.paymentStatusMessage = "Backend Error (\(httpResponse.statusCode)): \(rawString)"
                }
                return
            }
            
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let errorMsg = json["error"] as? String {
                    DispatchQueue.main.async { self.isProcessing = false; self.paymentStatusMessage = "Stripe Error: \(errorMsg)" }
                    return
                }
                
                if let clientSecret = json["setupIntent"] as? String {
                    if clientSecret == "trial_started" {
                        isProcessing = false; handleTrialStarted(); return
                    }
                    
                    guard let customerId = json["customer"] as? String,
                          let ephemeralKeySecret = json["ephemeralKey"] as? String,
                          let publishableKey = json["publishableKey"] as? String else {
                        isProcessing = false; paymentStatusMessage = "Missing API keys from server."; return
                    }
                    
                    STPAPIClient.shared.publishableKey = publishableKey
                    var configuration = PaymentSheet.Configuration()
                    configuration.merchantDisplayName = "LookSee"
                    configuration.customer = .init(id: customerId, ephemeralKeySecret: ephemeralKeySecret)
                    configuration.applePay = .init(merchantId: "merchant.com.looksee.app", merchantCountryCode: "US")
                    
                    DispatchQueue.main.async {
                        self.paymentSheet = PaymentSheet(paymentIntentClientSecret: clientSecret, configuration: configuration)
                        self.isProcessing = false
                        self.showPaymentSheet = true
                    }
                } else {
                    isProcessing = false
                    paymentStatusMessage = "Server error: \(rawString)"
                }
            } else {
                isProcessing = false
                paymentStatusMessage = "Could not parse server response."
            }
        } catch { isProcessing = false; paymentStatusMessage = "Network error: \(error.localizedDescription)" }
    }
    
    private func onPaymentCompletion(result: PaymentSheetResult) {
        switch result {
        case .completed:
            paymentStatusMessage = "Payment successful! Updating account..."
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                Task {
                    await vm.fetchUserUsageStats()
                    withAnimation { authState.tier = .business; dismiss() }
                }
            }
        case .canceled: paymentStatusMessage = "Payment was canceled."
        case .failed(let error): paymentStatusMessage = "Payment failed: \(error.localizedDescription)"
        }
    }
    
    private func handleTrialStarted() {
        paymentStatusMessage = "Trial activated successfully! Updating account..."
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            Task {
                await vm.fetchUserUsageStats()
                withAnimation { authState.tier = .business; dismiss() }
            }
        }
    }
}
