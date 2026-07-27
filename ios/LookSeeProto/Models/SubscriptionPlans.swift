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
    @EnvironmentObject var vm: AuthViewModel
    @ObservedObject var presenter: SettingsPresenter
    
    @State private var selectedTab: Int
    @State private var isProcessing = false
    @State private var paymentSheet: PaymentSheet?
    @State private var showPaymentSheet = false
    @State private var paymentStatusMessage: String?
    
    @State private var pendingTokenReward: Int = 0
    @State private var pendingSubscriptionId: String? = nil
    @State private var selectedAddOnIndex: Int = 0
    
    let addOns = [
        (tokens: 0, cents: 0, label: "None"),
        (tokens: 1, cents: 300, label: "1 Token (+$3.00)"),
        (tokens: 5, cents: 1000, label: "5 Tokens (+$10.00)"),
        (tokens: 10, cents: 1500, label: "10 Tokens (+$15.00)"),
        (tokens: 25, cents: 3500, label: "25 Tokens (+$35.00)"),
        (tokens: 50, cents: 6000, label: "50 Tokens (+$60.00)"),
        (tokens: 100, cents: 10000, label: "100 Tokens (+$100.00)")
    ]
    
    private let primaryColor = Color(red: 0.22, green: 0.49, blue: 1.00)
    private let secondaryTextColor = Color.white.opacity(0.58)
    private let disabledTextColor = Color.white.opacity(0.45)
    
    private var isFullyLoggedIn: Bool {
        return vm.isSignedIn && !vm.userId.isEmpty && !vm.userEmail.isEmpty
    }
    
    private var isTrial: Bool {
        return UserDefaults.standard.bool(forKey: "isFreeTrial_\(vm.userEmail)")
    }
    
    private var isEligibleForTrial: Bool {
        if vm.hasActiveSubscription { return false }
        if !vm.stripeSubscriptionId.isEmpty { return false }
        if vm.tokenBalance > 0 { return false }
        return true
    }
    
    init(presenter: SettingsPresenter) {
        self.presenter = presenter
        _selectedTab = State(initialValue: presenter.subscriptionStartingTab)
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
                        Button { dismiss() } label: {
                            Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.white.opacity(0.4))
                        }
                        .disabled(isProcessing)
                    }
                    .padding(.horizontal, 24).padding(.top, 16)
                    
                    VStack(spacing: 6) {
                        Text("LookSee").font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(primaryColor)
                        Text(vm.hasActiveSubscription ? "Manage Membership" : "Upgrade to Business")
                            .font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    }
                    
                    Picker("Options", selection: $selectedTab) {
                        Text("Plan").tag(0)
                        Text("Tokens").tag(1)
                        Text("Free Trial").tag(2)
                    }
                    .pickerStyle(.segmented).padding(.horizontal, 24).padding(.vertical, 8)
                    
                    if selectedTab == 0 {
                        yearlyPlanView
                    } else if selectedTab == 1 {
                        tokenPurchaserView
                    } else {
                        freeTrialView
                    }
                    
                    Spacer()
                    
                    HStack(spacing: 6) {
                        Image(systemName: "lock.shield.fill")
                        Text("Secured by Stripe. Cancel at any time.")
                    }
                    .font(.system(size: 11)).foregroundStyle(secondaryTextColor).padding(.bottom, 16)
                }
            }
            .onAppear {
                if let action = presenter.resumeCheckoutAction {
                    isProcessing = true
                    if action == "yearly" {
                        selectedAddOnIndex = presenter.savedAddOnIndex
                        selectedTab = 0
                        Task { await preparePaymentSheet(purchaseType: "yearly_subscription") }
                    } else if action == "trial" {
                        selectedTab = 2
                        Task { await preparePaymentSheet(purchaseType: "free_trial") }
                    } else if action == "tokens" {
                        selectedTab = 1
                        Task { await preparePaymentSheet(purchaseType: "token_pack", amountCents: presenter.savedTokenCents, tokenCount: presenter.savedTokenCount) }
                    }
                    presenter.resumeCheckoutAction = nil
                }
            }
            .background(
                Group {
                    if let ps = paymentSheet { Color.clear.paymentSheet(isPresented: $showPaymentSheet, paymentSheet: ps, onCompletion: onPaymentCompletion) }
                }
            )
        }
        // This screen always uses a dark visual design, so keep system controls
        // such as the segmented picker and materials in dark appearance too.
        .environment(\.colorScheme, .dark)
        .interactiveDismissDisabled(isProcessing)
        .overlay {
            if isProcessing {
                ZStack {
                    Color.black.opacity(0.4).ignoresSafeArea()
                    VStack(spacing: 16) {
                        ProgressView().tint(.white).scaleEffect(1.5)
                        Text("Connecting to Stripe...").font(.headline).foregroundStyle(.white)
                    }.padding(32).background(.ultraThinMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }
    
    private var yearlyPlanView: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Text("RECOMMENDED").font(.system(size: 10, weight: .black)).foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 4).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 8))
                Spacer()
            }.padding(.top, -10).padding(.bottom, 6)
            
            Text("Yearly Premium").font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            
            HStack(alignment: .bottom, spacing: 2) {
                Text("$10").font(.system(size: 34, weight: .black, design: .rounded)).foregroundStyle(.white)
                Text("/year").font(.system(size: 14)).foregroundStyle(secondaryTextColor).padding(.bottom, 6)
            }.padding(.top, 4)
            
            Text("Unlimited active landmarks. Just pay-per-upload.").font(.system(size: 13)).foregroundStyle(secondaryTextColor).padding(.top, 2)
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                featureRow("10 Tokens included instantly")
                featureRow("Add or swap landmarks anytime")
                featureRow("Unlock promotion dashboard")
            }
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 8) {
                Text("Optional Token Add-on").font(.system(size: 12, weight: .bold)).foregroundStyle(secondaryTextColor).textCase(.uppercase)
                Picker("Add-on", selection: $selectedAddOnIndex) {
                    ForEach(0..<addOns.count, id: \.self) { index in
                        Text(addOns[index].label).tag(index)
                    }
                }
                .pickerStyle(.menu)
                .tint(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10).background(Color.white.opacity(0.05)).clipShape(RoundedRectangle(cornerRadius: 10))
            }
            
            Spacer()
            
            Button {
                if vm.hasActiveSubscription && !isTrial { return }
                
                if !isFullyLoggedIn {
                    presenter.savedAddOnIndex = selectedAddOnIndex
                    presenter.resumeCheckoutAction = "yearly"
                    dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
                } else {
                    isProcessing = true
                    Task { await preparePaymentSheet(purchaseType: "yearly_subscription") }
                }
            } label: {
                Group {
                    if vm.hasActiveSubscription {
                        if isTrial {
                            let addOnCents = Double(addOns[selectedAddOnIndex].cents) / 100.0
                            let totalStr = String(format: "%.2f", 10.00 + addOnCents)
                            Text("Upgrade to Yearly - $\(totalStr)")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                        } else {
                            Text("Current Plan")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                    } else {
                        let addOnCents = Double(addOns[selectedAddOnIndex].cents) / 100.0
                        let totalStr = String(format: "%.2f", 10.00 + addOnCents)
                        Text("Subscribe - $\(totalStr)")
                            .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
            .disabled((vm.hasActiveSubscription && !isTrial) || isProcessing)
        }
        .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 24)
        .background(Color.white.opacity(0.04).background(.ultraThinMaterial)).clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke((vm.hasActiveSubscription && !isTrial) ? Color.green.opacity(0.8) : primaryColor.opacity(0.8), lineWidth: (vm.hasActiveSubscription && !isTrial) ? 2.5 : 1.5))
        .padding(.horizontal, 24)
    }

    private var freeTrialView: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Text("NEW USERS").font(.system(size: 10, weight: .black)).foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 4).background(Color.orange).clipShape(RoundedRectangle(cornerRadius: 8))
                Spacer()
            }.padding(.top, -10).padding(.bottom, 6)
            
            Text("14-Day Trial").font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            
            HStack(alignment: .bottom, spacing: 2) {
                Text("$0").font(.system(size: 34, weight: .black, design: .rounded)).foregroundStyle(.white)
                Text("/14 days").font(.system(size: 14)).foregroundStyle(secondaryTextColor).padding(.bottom, 6)
            }.padding(.top, 4)
            
            Text("Test out the platform risk-free before committing to a yearly subscription.").font(.system(size: 13)).foregroundStyle(secondaryTextColor).padding(.top, 2)
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                featureRow("Includes exactly 5 Tokens")
                featureRow("Full access to business tools")
                featureRow("No add-ons available during trial")
            }
            
            Spacer()
            
            Button {
                if !isEligibleForTrial { return }
                
                if !isFullyLoggedIn {
                    presenter.resumeCheckoutAction = "trial"
                    dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
                } else {
                    isProcessing = true
                    Task { await preparePaymentSheet(purchaseType: "free_trial") }
                }
            } label: {
                Group {
                    if vm.hasActiveSubscription {
                        if isTrial {
                            Text("Active (Free Trial)")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                        } else {
                            Text("Unavailable for Active Accounts")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                    } else if !isEligibleForTrial {
                        Text("Not Eligible for Free Trial")
                            .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                    } else {
                        Text("Start Free Trial")
                            .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.orange).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
            .disabled(!isEligibleForTrial || isProcessing)
            
            if let msg = paymentStatusMessage {
                Text(msg).font(.caption).foregroundStyle(msg.contains("successful") ? .green : .red).padding(.top, 8)
            }
        }
        .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 24)
        .background(Color.white.opacity(0.04).background(.ultraThinMaterial)).clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke((vm.hasActiveSubscription && isTrial) ? Color.green.opacity(0.8) : Color.orange.opacity(0.5), lineWidth: (vm.hasActiveSubscription && isTrial) ? 2.5 : 1.5))
        .padding(.horizontal, 24)
    }

    private func featureRow(_ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(primaryColor).font(.system(size: 15))
            Text(text).font(.system(size: 13)).foregroundStyle(.white.opacity(0.8))
        }
    }
    
    private var tokenPurchaserView: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "circle.hexagongrid.fill").font(.system(size: 48)).foregroundStyle(primaryColor)
                    Text("\(vm.tokenBalance)").font(.system(size: 42, weight: .black, design: .rounded)).foregroundStyle(.white)
                    Text("Tokens Available").font(.system(size: 14, weight: .bold)).foregroundStyle(secondaryTextColor).textCase(.uppercase)
                }.padding(.top, 20)
                
                VStack(spacing: 6) {
                    Text("What are tokens?").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                    Text("A token can be used to add another landmark to your account or swap an existing one out. Removing a landmark is free.")
                        .font(.system(size: 13)).foregroundStyle(secondaryTextColor).multilineTextAlignment(.center).padding(.horizontal, 32)
                }
                
                VStack(alignment: .leading, spacing: 8) {
                    Text("Buy Token Packs").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(secondaryTextColor).textCase(.uppercase).padding(.horizontal, 20)
                    
                    // 🚀 THE FIX: Hide tokens and explain why if they are on a free trial
                    VStack(spacing: 0) {
                        if !isFullyLoggedIn || !vm.hasActiveSubscription {
                            Text("Please subscribe to the Premium plan to purchase tokens.")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(20)
                        } else if isTrial {
                            Text("Token add-ons are not available during the Free Trial.\n\nPlease upgrade to the Yearly Premium plan to purchase tokens.")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(24)
                        } else {
                            bundleRow(tokens: 1, price: "$3.00", amountCents: 300)
                            Divider().padding(.leading, 68)
                            bundleRow(tokens: 5, price: "$10.00", amountCents: 1000)
                            Divider().padding(.leading, 68)
                            bundleRow(tokens: 10, price: "$15.00", amountCents: 1500)
                            Divider().padding(.leading, 68)
                            bundleRow(tokens: 25, price: "$35.00", amountCents: 3500)
                            Divider().padding(.leading, 68)
                            bundleRow(tokens: 50, price: "$60.00", amountCents: 6000)
                            Divider().padding(.leading, 68)
                            bundleRow(tokens: 100, price: "$100.00", amountCents: 10000)
                        }
                    }
                    .background(Color.white.opacity(0.04)).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.horizontal)
                    
                    if let message = paymentStatusMessage {
                        Text(message).font(.system(size: 13, weight: .bold)).foregroundStyle(message.contains("successful") ? .green : .red).padding(.horizontal, 24).padding(.top, 8)
                    }
                }
            }
        }
    }
    
    private func bundleRow(tokens: Int, price: String, amountCents: Int) -> some View {
        Button {
            paymentStatusMessage = nil
            
            if !isFullyLoggedIn {
                presenter.savedTokenCount = tokens
                presenter.savedTokenCents = amountCents
                presenter.resumeCheckoutAction = "tokens"
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
            } else {
                isProcessing = true
                Task { await preparePaymentSheet(purchaseType: "token_pack", amountCents: amountCents, tokenCount: tokens) }
            }
        } label: {
            HStack(spacing: 16) {
                ZStack { Circle().fill(primaryColor.opacity(0.15)).frame(width: 36, height: 36); Text("\(tokens)").font(.system(size: 14, weight: .bold)).foregroundStyle(primaryColor) }
                Text("\(tokens) Tokens").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                Spacer()
                Text(price).font(.system(size: 16, weight: .bold, design: .rounded)).foregroundStyle(.white)
            }.padding(16)
        }
        .disabled(isProcessing)
    }

    private func preparePaymentSheet(purchaseType: String, amountCents: Int = 0, tokenCount: Int = 0) async {
        await vm.fetchUserDetails()
        
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else {
            isProcessing = false; paymentStatusMessage = "Invalid API URL."; return
        }
        
        if purchaseType == "token_pack" {
            pendingTokenReward = tokenCount
        } else if purchaseType == "yearly_subscription" {
            pendingTokenReward = 10 + addOns[selectedAddOnIndex].tokens
        } else if purchaseType == "free_trial" {
            pendingTokenReward = 5
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        var body: [String: Any] = ["purchaseType": purchaseType, "userId": vm.userId, "userEmail": vm.userEmail]
        
        if purchaseType == "token_pack" {
            body["amountCents"] = amountCents
            body["tokenCount"] = tokenCount
        } else if purchaseType == "yearly_subscription" {
            body["addOnCents"] = addOns[selectedAddOnIndex].cents
            body["addOnTokens"] = addOns[selectedAddOnIndex].tokens
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let errorMsg = json["error"] as? String {
                    DispatchQueue.main.async { self.isProcessing = false; self.presenter.resumeCheckoutAction = nil; self.paymentStatusMessage = "Stripe Error: \(errorMsg)" }
                    return
                }
                
                if let subId = json["subscriptionId"] as? String {
                    self.pendingSubscriptionId = subId
                }
                
                if let clientSecret = json["setupIntent"] as? String, let customerId = json["customer"] as? String, let ephemeralKeySecret = json["ephemeralKey"] as? String, let publishableKey = json["publishableKey"] as? String {
                    STPAPIClient.shared.publishableKey = publishableKey
                    var configuration = PaymentSheet.Configuration()
                    configuration.merchantDisplayName = "LookSee"
                    configuration.customer = .init(id: customerId, ephemeralKeySecret: ephemeralKeySecret)
                    configuration.applePay = .init(merchantId: "merchant.com.looksee.app", merchantCountryCode: "US")
                    
                    DispatchQueue.main.async {
                        if clientSecret.hasPrefix("seti_") {
                            self.paymentSheet = PaymentSheet(setupIntentClientSecret: clientSecret, configuration: configuration)
                        } else {
                            self.paymentSheet = PaymentSheet(paymentIntentClientSecret: clientSecret, configuration: configuration)
                        }
                        
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.showPaymentSheet = true }
                    }
                } else {
                    DispatchQueue.main.async { self.isProcessing = false; self.presenter.resumeCheckoutAction = nil }
                }
            } else {
                DispatchQueue.main.async { self.isProcessing = false; self.presenter.resumeCheckoutAction = nil }
            }
        } catch {
            DispatchQueue.main.async { self.isProcessing = false; self.presenter.resumeCheckoutAction = nil }
        }
    }
    
    private func confirmPurchaseOnBackend() async -> Bool {
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        var body: [String: Any] = [
            "purchaseType": "confirm_success",
            "userId": vm.userId,
            "addTokens": pendingTokenReward,
            "isBusiness": selectedTab != 1
        ]
        
        if let subId = pendingSubscriptionId {
            body["subscriptionId"] = subId
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                return true
            }
        } catch {
            return false
        }
        return false
    }
    
    private func onPaymentCompletion(result: PaymentSheetResult) {
        switch result {
        case .completed:
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
            
            Task {
                let dbSuccess = await confirmPurchaseOnBackend()
                
                await MainActor.run {
                    if dbSuccess {
                        presenter.justPurchased = true
                        vm.tokenBalance += pendingTokenReward
                        
                        if selectedTab != 1 {
                            vm.hasActiveSubscription = true
                            
                            if selectedTab == 2 {
                                UserDefaults.standard.set(true, forKey: "isFreeTrial_\(vm.userEmail)")
                            } else {
                                UserDefaults.standard.set(false, forKey: "isFreeTrial_\(vm.userEmail)")
                            }
                        }
                        
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                            presenter.resumeCheckoutAction = nil
                            isProcessing = false
                            dismiss()
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { presenter.justPurchased = false }
                        
                    } else {
                        isProcessing = false
                        presenter.resumeCheckoutAction = nil
                    }
                }
            }
            
        case .canceled, .failed:
            DispatchQueue.main.async {
                self.isProcessing = false
                self.presenter.resumeCheckoutAction = nil
            }
        }
    }
}
