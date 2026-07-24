//
//  SubscriptionPlans.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//


import SwiftUI
import StripePaymentSheet

struct SubscriptionPlans: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var authState: AuthState
    @EnvironmentObject var vm: AuthViewModel
    
    @State private var selectedTab: Int
    @State private var selectedPlan: Int = 1
    @State private var navigateToSetup = false
    @State private var showAuthModal = false
    
    @State private var isProcessing = false
    @State private var paymentSheet: PaymentSheet?
    @State private var showPaymentSheet = false
    @State private var paymentStatusMessage: String?
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    
    init(startingTab: Int = 0) {
        _selectedTab = State(initialValue: startingTab)
    }
    
    private func capacityForPlan(index: Int) -> Int {
        switch index {
        case 0: return 5    // Trial
        case 1: return 5    // Classic
        case 2: return 20   // Intermediate
        case 3: return 100  // Advanced
        default: return 5
        }
    }
    
    private func priceForPlan(index: Int) -> Int {
        switch index {
        case 0: return 0
        case 1: return 10
        case 2: return 25
        case 3: return 50
        default: return 10
        }
    }
    
    private func buttonStateForPlan(index: Int) -> (text: String, isCurrent: Bool) {
        if authState.tier == .guest || vm.maxLandmarksCapacity == 0 {
            return ("Subscribe", false)
        }
        
        let viewedCapacity = capacityForPlan(index: index)
        let currentCapacity = vm.maxLandmarksCapacity
        
        if index == 0 { return ("Unavailable for Active Accounts", true) }
        
        if viewedCapacity == currentCapacity {
            return ("Current Plan", true)
        } else if viewedCapacity > currentCapacity {
            let currentPrice = (currentCapacity <= 5) ? 10 : (currentCapacity <= 20 ? 25 : 50)
            let newPrice = priceForPlan(index: index)
            let diff = newPrice - currentPrice
            return diff > 0 ? ("Upgrade for $\(diff)", false) : ("Upgrade Plan", false)
        } else {
            return ("Downgrade Plan", false)
        }
    }
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
                
                GeometryReader { geo in
                    ZStack {
                        Circle().fill(primaryColor.opacity(0.15)).frame(width: geo.size.width * 0.8).blur(radius: 60).offset(x: -geo.size.width * 0.3, y: -geo.size.height * 0.2)
                        Circle().fill(Color(red: 0.11, green: 0.22, blue: 0.55).opacity(0.15)).frame(width: geo.size.width * 0.9).blur(radius: 80).offset(x: geo.size.width * 0.4, y: geo.size.height * 0.4)
                    }
                }.ignoresSafeArea()
                
                VStack(spacing: 16) {
                    HStack {
                        Spacer()
                        Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.white.opacity(0.4)) }
                    }
                    .padding(.horizontal, 24).padding(.top, 16)
                    
                    VStack(spacing: 6) {
                        Text("LookSee").font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(primaryColor)
                        Text(authState.tier == .business && vm.maxLandmarksCapacity > 0 ? "Manage Membership" : "Upgrade to Business")
                            .font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    }
                    
                    Picker("Options", selection: $selectedTab) {
                        Text("Plans").tag(0)
                        Text("Tokens").tag(1)
                        Text("Free Trial").tag(2)
                    }
                    .pickerStyle(.segmented).padding(.horizontal, 24).padding(.vertical, 8)
                    
                    if selectedTab == 0 {
                        TabView(selection: $selectedPlan) {
                            planCard(title: "Classic", price: "$10", unit: "/month", description: "Perfect for local independent shops.", features: ["Up to 5 active landmarks", "Up to 10 tokens for purchase/mo"], badge: "AFFORDABLE", index: 1).tag(1)
                            planCard(title: "Intermediate", price: "$25", unit: "/month", description: "For expanding storefronts and regional venues.", features: ["Up to 20 active landmarks", "Up to 50 tokens for purchase/mo"], badge: "POPULAR", index: 2).tag(2)
                            planCard(title: "Advanced", price: "$50", unit: "/month", description: "For extensive properties and museums.", features: ["Up to 100 active landmarks", "Up to 100 tokens for purchase/mo"], badge: "MAX SCALE", index: 3).tag(3)
                        }
                        .tabViewStyle(.page(indexDisplayMode: .always)).frame(height: 440)
                    } else if selectedTab == 1 {
                        tokenPurchaserView
                    } else {
                        TabView(selection: $selectedPlan) {
                            planCard(title: "14-Day Trial", price: "$0", unit: "/14 days", description: "Test out the platform risk-free before committing to a tier.", features: ["Up to 5 active landmarks", "Up to 5 tokens for purchase/mo"], badge: "NEW USERS", index: 0).tag(0)
                        }
                        .tabViewStyle(.page(indexDisplayMode: .always)).frame(height: 440)
                    }
                    
                    Spacer()
                    
                    HStack(spacing: 6) {
                        Image(systemName: "lock.shield.fill")
                        Text("Secured by Stripe. Cancel or upgrade at any time.")
                    }
                    .font(.system(size: 11)).foregroundStyle(.secondary).padding(.bottom, 16)
                }
            }
            .navigationDestination(isPresented: $navigateToSetup) {
                BusinessSetup(selectedPlanIndex: selectedPlan, isAnnualPlan: true)
            }
            .sheet(isPresented: $showAuthModal) {
                GuestSignUpView()
            }
            // 🚀 THE LOOP FIX: Automatically pushes you to checkout right after you finish signing in!
            .onChange(of: authState.tier) { _, newTier in
                if (newTier == .authenticated || newTier == .business) && !vm.userId.isEmpty {
                    if showAuthModal {
                        showAuthModal = false
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                            navigateToSetup = true
                        }
                    }
                }
            }
            .background(
                Group {
                    if let ps = paymentSheet { Color.clear.paymentSheet(isPresented: $showPaymentSheet, paymentSheet: ps, onCompletion: onPaymentCompletion) }
                }
            )
        }
    }
    
    @ViewBuilder
    private func planCard(title: String, price: String, unit: String, description: String, features: [String], badge: String, index: Int) -> some View {
        let buttonState = buttonStateForPlan(index: index)
        
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Text(badge).font(.system(size: 10, weight: .black)).foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 4).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 8))
                Spacer()
            }.padding(.top, -10).padding(.bottom, 6)
            
            Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            
            HStack(alignment: .bottom, spacing: 2) {
                Text(price).font(.system(size: 34, weight: .black, design: .rounded)).foregroundStyle(.white)
                Text(unit).font(.system(size: 14)).foregroundStyle(.secondary).padding(.bottom, 6)
            }.padding(.top, 4)
            
            Text(description).font(.system(size: 13)).foregroundStyle(.secondary).padding(.top, 2).fixedSize(horizontal: false, vertical: true)
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                ForEach(features, id: \.self) { feature in
                    HStack(spacing: 10) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(primaryColor).font(.system(size: 15))
                        Text(feature).font(.system(size: 13)).foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
            Spacer()
            Button {
                if !buttonState.isCurrent {
                    selectedPlan = index
                    if authState.tier == .guest || vm.userId.isEmpty {
                        showAuthModal = true
                    } else {
                        navigateToSetup = true
                    }
                }
            } label: {
                Text(buttonState.text)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(buttonState.isCurrent ? .gray : .white).frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(buttonState.isCurrent ? Color.white.opacity(0.1) : primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .disabled(buttonState.isCurrent)
        }
        .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 40)
        .background(Color.white.opacity(0.04).background(.ultraThinMaterial)).clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(buttonState.isCurrent ? Color.green.opacity(0.8) : primaryColor.opacity(0.8), lineWidth: buttonState.isCurrent ? 2.5 : 1.5))
        .padding(.horizontal, 24)
    }
    
    private var tokenPurchaserView: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "circle.hexagongrid.fill").font(.system(size: 48)).foregroundStyle(primaryColor)
                    Text("\(vm.tokenBalance)").font(.system(size: 42, weight: .black, design: .rounded)).foregroundStyle(.white)
                    Text("Tokens Available").font(.system(size: 14, weight: .bold)).foregroundStyle(.secondary).textCase(.uppercase)
                }.padding(.top, 20)
                
                VStack(spacing: 6) {
                    Text("What are tokens?").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                    Text("Tokens are used to update your landmarks' digital inventory or details without waiting. 1 Token = 1 Update.")
                        .font(.system(size: 13)).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 32)
                }
                
                VStack(alignment: .leading, spacing: 8) {
                    Text("Buy Token Packs").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(.secondary).textCase(.uppercase).padding(.horizontal, 20)
                    
                    VStack(spacing: 0) {
                        if authState.tier == .guest || vm.maxLandmarksCapacity == 0 {
                            Text("Please subscribe to a membership plan to purchase tokens.").font(.system(size: 14, weight: .medium)).foregroundStyle(.secondary).padding(20)
                        } else {
                            if vm.maxLandmarksCapacity <= 5 {
                                bundleRow(tokens: 1, price: "$2.00", amountCents: 200)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 5, price: "$10.00", amountCents: 1000)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 10, price: "$20.00", amountCents: 2000)
                            } else if vm.maxLandmarksCapacity <= 20 {
                                bundleRow(tokens: 10, price: "$20.00", amountCents: 2000)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 25, price: "$45.00", amountCents: 4500)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 50, price: "$90.00", amountCents: 9000)
                            } else {
                                bundleRow(tokens: 25, price: "$35.00", amountCents: 3500)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 50, price: "$70.00", amountCents: 7000)
                                Divider().padding(.leading, 68)
                                bundleRow(tokens: 100, price: "$140.00", amountCents: 14000)
                            }
                        }
                    }
                    .background(Color.white.opacity(0.04)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal)
                    
                    if let message = paymentStatusMessage {
                        Text(message).font(.system(size: 13, weight: .bold)).foregroundStyle(message.contains("successful") ? .green : .red).padding(.horizontal, 24).padding(.top, 8)
                    }
                }
            }
            .overlay {
                if isProcessing {
                    ZStack {
                        Color.black.opacity(0.4).ignoresSafeArea()
                        VStack(spacing: 16) { ProgressView().tint(.white).scaleEffect(1.5); Text("Connecting to Stripe...").font(.headline).foregroundStyle(.white) }.padding(32).background(.ultraThinMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }
            }
        }
    }
    
    private func bundleRow(tokens: Int, price: String, amountCents: Int) -> some View {
        Button {
            paymentStatusMessage = nil
            if authState.tier == .guest || vm.userId.isEmpty {
                showAuthModal = true
            } else {
                Task { await preparePaymentSheet(tokenCount: tokens, amountCents: amountCents) }
            }
        } label: {
            HStack(spacing: 16) {
                ZStack { Circle().fill(primaryColor.opacity(0.15)).frame(width: 36, height: 36); Text("\(tokens)").font(.system(size: 14, weight: .bold)).foregroundStyle(primaryColor) }
                Text("\(tokens) Tokens").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                Spacer()
                Text(price).font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.white)
            }.padding(16)
        }
    }
    
    private func preparePaymentSheet(tokenCount: Int, amountCents: Int) async {
        isProcessing = true
        await vm.fetchUserDetails()
        
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else {
            isProcessing = false; paymentStatusMessage = "Invalid API URL."; return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = ["purchaseType": "token_pack", "userId": vm.userId, "userEmail": vm.userEmail, "amountCents": amountCents, "tokenCount": tokenCount]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let errorMsg = json["error"] as? String {
                    DispatchQueue.main.async { self.isProcessing = false; self.paymentStatusMessage = "Stripe Error: \(errorMsg)" }
                    return
                }
                
                if let clientSecret = json["setupIntent"] as? String, let customerId = json["customer"] as? String, let ephemeralKeySecret = json["ephemeralKey"] as? String, let publishableKey = json["publishableKey"] as? String {
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
                    let rawString = String(data: data, encoding: .utf8) ?? "Empty Response"
                    isProcessing = false; paymentStatusMessage = "Missing setup keys: \(rawString)"
                }
            } else {
                let rawString = String(data: data, encoding: .utf8) ?? "Empty Response"
                isProcessing = false; paymentStatusMessage = "Failed to parse JSON: \(rawString)"
            }
        } catch { isProcessing = false; paymentStatusMessage = "Network error: \(error.localizedDescription)" }
    }
    
    private func onPaymentCompletion(result: PaymentSheetResult) {
        switch result {
        case .completed:
            paymentStatusMessage = "Payment successful! Tokens will appear momentarily."
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { Task { await vm.fetchUserUsageStats() } }
        case .canceled: paymentStatusMessage = "Payment was canceled."
        case .failed(let error): paymentStatusMessage = "Payment failed: \(error.localizedDescription)"
        }
    }
}
