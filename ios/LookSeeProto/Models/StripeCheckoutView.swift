//
//  StripeCheckoutView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//


import SwiftUI
import StripePaymentSheet

struct StripeCheckoutView: View {
    @EnvironmentObject var authState: AuthState
    @Environment(\.dismiss) var dismiss
    
    @State private var paymentSheet: PaymentSheet?
    @State private var paymentProcessing = false
    @State private var paymentSuccess = false
    @State private var errorMessage: String?
    
    let backendCheckoutUrl = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout")!
    
    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.06, blue: 0.10)
                .ignoresSafeArea()
            
            VStack(spacing: 28) {
                Spacer()
                
                if !paymentProcessing && !paymentSuccess {
                    VStack(spacing: 20) {
                        Image(systemName: "creditcard.and.123")
                            .font(.system(size: 60))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                        
                        Text("Stripe Payment Sheet")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(.white)
                        
                        Text("Natively presenting Apple Pay and secure credit card elements securely processed via your secure Stripe SetupIntent gateway configuration.")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    
                    Spacer()
                    
                    if let paymentSheet = paymentSheet {
                        PaymentSheet.PaymentButton(
                            paymentSheet: paymentSheet,
                            onCompletion: onPaymentCompletion
                        ) {
                            HStack(spacing: 12) {
                                Image(systemName: "applepay")
                                    .font(.system(size: 24))
                                Text("Pay with Apple Pay / Card")
                                    .font(.system(size: 16, weight: .bold))
                            }
                            .foregroundStyle(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .padding(.horizontal, 24)
                    } else {
                        ProgressView()
                            .tint(.white)
                            .padding()
                        Text("Loading secure connection...")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    
                    if let errorMessage = errorMessage {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundStyle(.red)
                            .padding(.horizontal)
                    }
                    
                    Spacer().frame(height: 24)
                    
                } else if paymentProcessing {
                    VStack(spacing: 16) {
                        ProgressView()
                            .tint(.white)
                            .scaleEffect(1.5)
                        Text("Confirming Subscription...")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Text("Securing details with Stripe network.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                } else if paymentSuccess {
                    VStack(spacing: 18) {
                        ZStack {
                            Circle()
                                .fill(Color.green.opacity(0.15))
                                .frame(width: 80, height: 80)
                            Image(systemName: "checkmark.seal.fill")
                                .font(.system(size: 40))
                                .foregroundStyle(.green)
                        }
                        
                        Text("Payment Method Saved")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                        
                        Text("Your business access will update after the LookSee backend confirms the subscription.")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 36)
                        
                        Button {
                            Task {
                                await authState.resolveTier()
                                dismiss()
                            }
                        } label: {
                            Text("Done")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        .padding(.horizontal, 24)
                        .padding(.top, 14)
                    }
                    Spacer()
                }
            }
        }
        .navigationBarBackButtonHidden(paymentProcessing || paymentSuccess)
        .task {
            await preparePaymentSheet()
        }
    }
    
    private func preparePaymentSheet() async {
        var request = URLRequest(url: backendCheckoutUrl)
        request.httpMethod = "POST"

        do {
            let idToken = try await AuthService.shared.fetchIdToken()
            request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        } catch {
            errorMessage = "Your session could not be verified. Please sign in again."
            return
        }

        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            
            if let rawString = String(data: data, encoding: .utf8) {
                print("RAW AWS RESPONSE: \(rawString)")
            }
            
            let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any]
            guard let setupIntent = json?["setupIntent"] as? String,
                  let ephemeralKey = json?["ephemeralKey"] as? String,
                  let customerId = json?["customer"] as? String,
                  let publishableKey = json?["publishableKey"] as? String else {
                errorMessage = "Invalid response from server"
                return
            }
            
            StripeAPI.defaultPublishableKey = publishableKey
            var configuration = PaymentSheet.Configuration()
            configuration.merchantDisplayName = "LookSee"
            configuration.customer = .init(id: customerId, ephemeralKeySecret: ephemeralKey)
            configuration.allowsDelayedPaymentMethods = false
            
            await MainActor.run {
                self.paymentSheet = PaymentSheet(setupIntentClientSecret: setupIntent, configuration: configuration)
            }
        } catch {
            errorMessage = "Network error: \(error.localizedDescription)"
        }
    }
    
    private func onPaymentCompletion(result: PaymentSheetResult) {
        switch result {
        case .completed:
            withAnimation { paymentSuccess = true }
        case .canceled:
            print("Payment canceled")
        case .failed(let error):
            errorMessage = "Payment failed: \(error.localizedDescription)"
        }
    }
}
