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
    @State private var selectedPlanIndex: Int = 0 // 0: 1-Year, 1: 3-Year, 2: 5-Year
    @State private var isProcessing = false
    @State private var paymentSheet: PaymentSheet?
    @State private var showPaymentSheet = false
    @State private var paymentStatusMessage: String?
    
    @State private var pendingTokenReward: Int = 0
    @State private var pendingSubscriptionId: String? = nil
    @State private var selectedAddOnIndex: Int = 0
    
    // Multi-Year Plan Tiers
    let plans = [
        (years: 1, priceCents: 1000, priceString: "$10", baseTokens: 10, label: String(localized: "1 Year")),
        (years: 3, priceCents: 2500, priceString: "$25", baseTokens: 25, label: String(localized: "3 Years")),
        (years: 5, priceCents: 3500, priceString: "$35", baseTokens: 35, label: String(localized: "5 Years"))
    ]
    
    let addOns = [
        (tokens: 0, cents: 0, label: String(localized: "None")),
        (tokens: 1, cents: 300, label: String(localized: "1 Token (+$3.00)")),
        (tokens: 5, cents: 1000, label: String(localized: "5 Tokens (+$10.00)")),
        (tokens: 10, cents: 1500, label: String(localized: "10 Tokens (+$15.00)")),
        (tokens: 25, cents: 3500, label: String(localized: "25 Tokens (+$35.00)")),
        (tokens: 50, cents: 6000, label: String(localized: "50 Tokens (+$60.00)")),
        (tokens: 100, cents: 10000, label: String(localized: "100 Tokens (+$100.00)"))
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
    
    private var activePlanCents: Int {
        return vm.activePlanCents > 0 ? vm.activePlanCents : 1000
    }
    
    private var isTokenOnlyMode: Bool {
        return presenter.subscriptionStartingTab == 1 && vm.hasActiveSubscription
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
                        
                        Text(isTokenOnlyMode ? "Token Store" : (vm.hasActiveSubscription ? "Manage Membership" : "Upgrade to Business"))
                            .font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    }
                    
                    if !isTokenOnlyMode {
                        Picker("Options", selection: $selectedTab) {
                            Text("Free Trial").tag(2)
                            Text("Plan").tag(0)
                        }
                        .pickerStyle(.segmented).padding(.horizontal, 24).padding(.vertical, 8)
                    }
                    
                    if isTokenOnlyMode || selectedTab == 1 {
                        tokenPurchaserView
                    } else if selectedTab == 0 {
                        yearlyPlanView
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
                if selectedTab == 1 && !vm.hasActiveSubscription {
                    selectedTab = 0
                }
                
                if let action = presenter.resumeCheckoutAction {
                    if action == "yearly" {
                        selectedAddOnIndex = presenter.savedAddOnIndex
                        selectedTab = 0
                        isProcessing = true
                        Task { await preparePaymentSheet(purchaseType: "yearly_subscription") }
                    } else if action == "trial" {
                        selectedTab = 2
                        isProcessing = true
                        // 🚀 Reroute to Stripe instead of the old direct bypass
                        Task { await preparePaymentSheet(purchaseType: "yearly_subscription", isFreeTrial: true) }
                    } else if action == "tokens" {
                        selectedTab = 1
                        isProcessing = true
                        Task { await preparePaymentSheet(purchaseType: "token_pack", amountCents: presenter.savedTokenCents, tokenCount: presenter.savedTokenCount) }
                    }
                    presenter.resumeCheckoutAction = nil
                }
            }
            .onDisappear {
                presenter.subscriptionStartingTab = 0
            }
            .onChange(of: vm.hasActiveSubscription) { _, hasSub in
                if !hasSub && selectedTab == 1 {
                    selectedTab = 0
                }
            }
            .background(
                Group {
                    if let ps = paymentSheet { Color.clear.paymentSheet(isPresented: $showPaymentSheet, paymentSheet: ps, onCompletion: onPaymentCompletion) }
                }
            )
        }
        .environment(\.colorScheme, .dark)
        .interactiveDismissDisabled(isProcessing)
        .overlay {
            if isProcessing {
                ZStack {
                    Color.black.opacity(0.4).ignoresSafeArea()
                    VStack(spacing: 16) {
                        ProgressView().tint(.white).scaleEffect(1.5)
                        Text(selectedTab == 2 ? "Preparing Trial..." : "Connecting to Stripe...").font(.headline).foregroundStyle(.white)
                    }.padding(32).background(.ultraThinMaterial).clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }
    
    private var yearlyPlanView: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Text("POPULAR").font(.system(size: 10, weight: .black)).foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 4).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 8))
                Spacer()
            }.padding(.top, -10).padding(.bottom, 6)
            
            Text("Business Membership").font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            Text("Select plan duration and included tokens.").font(.system(size: 13)).foregroundStyle(secondaryTextColor).padding(.top, 2)
            
            HStack(spacing: 8) {
                ForEach(0..<plans.count, id: \.self) { index in
                    let plan = plans[index]
                    let isSelected = selectedPlanIndex == index
                    
                    Button {
                        selectedPlanIndex = index
                    } label: {
                        VStack(spacing: 4) {
                            Text(plan.label)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(isSelected ? .white : secondaryTextColor)
                            Text(plan.priceString)
                                .font(.system(size: 18, weight: .black, design: .rounded))
                                .foregroundStyle(isSelected ? primaryColor : .white)
                            Text("\(plan.baseTokens) Tokens")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(secondaryTextColor)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(isSelected ? primaryColor.opacity(0.2) : Color.white.opacity(0.05))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(isSelected ? primaryColor : Color.white.opacity(0.1), lineWidth: isSelected ? 2 : 1)
                        )
                    }
                }
            }
            .padding(.top, 12)
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                let currentPlan = plans[selectedPlanIndex]
                featureRow(String(localized: "\(currentPlan.baseTokens) Tokens included instantly"))
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
                let selectedPlan = plans[selectedPlanIndex]
                if vm.hasActiveSubscription && !isTrial && selectedPlan.priceCents <= activePlanCents {
                    return
                }
                
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
                    let selectedPlan = plans[selectedPlanIndex]
                    let totalCents = selectedPlan.priceCents + addOns[selectedAddOnIndex].cents
                    let totalStr = String(format: "%.2f", Double(totalCents) / 100.0)
                    
                    if vm.hasActiveSubscription {
                        if isTrial {
                            Text("Upgrade to \(selectedPlan.label) - $\(totalStr)")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                        } else if selectedPlan.priceCents > activePlanCents {
                            Text("Upgrade to \(selectedPlan.label) - $\(totalStr)")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                        } else if selectedPlan.priceCents == activePlanCents {
                            Text("Current Plan")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                        } else {
                            Text("Included in Active Plan")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(disabledTextColor).frame(maxWidth: .infinity).padding(.vertical, 14).background(Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                    } else {
                        Text("Subscribe - $\(totalStr)")
                            .font(.system(size: 15, weight: .bold)).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 14).background(primaryColor).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
            .disabled((vm.hasActiveSubscription && !isTrial && plans[selectedPlanIndex].priceCents <= activePlanCents) || isProcessing)
        }
        .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 24)
        .background(Color.white.opacity(0.04).background(.ultraThinMaterial)).clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke((vm.hasActiveSubscription && !isTrial) ? Color.green.opacity(0.8) : primaryColor.opacity(0.8), lineWidth: (vm.hasActiveSubscription && !isTrial) ? 2.5 : 1.5))
        .padding(.horizontal, 24)
    }

    private var freeTrialView: some View {
        VStack(alignment: .leading, spacing: 0) {
            
            Text("14-Day Free Trial").font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            
            HStack(alignment: .bottom, spacing: 2) {
                Text("$0").font(.system(size: 34, weight: .black, design: .rounded)).foregroundStyle(.white)
                Text("/14 days").font(.system(size: 14)).foregroundStyle(secondaryTextColor).padding(.bottom, 6)
            }.padding(.top, 4)
            
            Text("Test out the platform risk-free with zero commitment.").font(.system(size: 13)).foregroundStyle(secondaryTextColor).padding(.top, 2)
            
            Divider().background(Color.white.opacity(0.12)).padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                // 🚀 Changed token count and updated feature descriptions
                featureRow("Includes exactly 2 Tokens")
                featureRow("Full access to business tools")
                featureRow("Auto-renews to 1-Year Plan ($10)")
            }
            
            Spacer()
            
            // 🚀 Clear payment disclaimer before they trigger Apple Pay
            Text("Payment information is required to start your trial. You will not be charged today. If you do not cancel before your 14 days are up, you will be billed $10 for the 1-Year Business Plan. Cancel anytime.")
                .font(.system(size: 11))
                .foregroundStyle(secondaryTextColor)
                .padding(.bottom, 16)
            
            Button {
                if !isEligibleForTrial { return }
                
                if !isFullyLoggedIn {
                    presenter.resumeCheckoutAction = "trial"
                    dismiss()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { presenter.showSignUpSheet = true }
                } else {
                    isProcessing = true
                    // 🚀 Requests payment sheet for the Trial via Stripe Subscription payload
                    Task { await preparePaymentSheet(purchaseType: "yearly_subscription", isFreeTrial: true) }
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
                Text(msg).font(.caption).foregroundStyle(msg.contains("Activated") ? .green : .red).padding(.top, 8)
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
                    
                    VStack(spacing: 0) {
                        if !isFullyLoggedIn || !vm.hasActiveSubscription {
                            Text("Please subscribe to a Business plan to purchase tokens.")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(20)
                        } else if isTrial {
                            Text("Token add-ons are not available during the Free Trial.\n\nPlease upgrade to a paid Business plan to purchase tokens.")
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
                        Text(message).font(.system(size: 13, weight: .bold)).foregroundStyle(message.contains(String(localized: "successful")) ? .green : .red).padding(.horizontal, 24).padding(.top, 8)
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

    // 🚀 THE FIX: preparePaymentSheet now handles isFreeTrial safely
    private func preparePaymentSheet(purchaseType: String, amountCents: Int = 0, tokenCount: Int = 0, isFreeTrial: Bool = false) async {
        await vm.fetchUserDetails()
        
        guard let url = URL(string: "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout") else {
            isProcessing = false; paymentStatusMessage = String(localized: "Invalid API URL."); return
        }
        
        let selectedPlan = plans[selectedPlanIndex]
        
        if purchaseType == "token_pack" {
            pendingTokenReward = tokenCount
        } else if purchaseType == "yearly_subscription" {
            if isFreeTrial {
                pendingTokenReward = 2 // 🚀 Local reference updated to 2
            } else {
                pendingTokenReward = selectedPlan.baseTokens + addOns[selectedAddOnIndex].tokens
            }
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        var body: [String: Any] = ["purchaseType": purchaseType, "userId": vm.userId, "userEmail": vm.userEmail]
        
        if purchaseType == "token_pack" {
            body["amountCents"] = amountCents
            body["tokenCount"] = tokenCount
        } else if purchaseType == "yearly_subscription" {
            if isFreeTrial {
                body["planYears"] = 1
                body["planCents"] = 1000
                body["addOnCents"] = 0
                body["tokenCount"] = 2
                body["isFreeTrial"] = true
            } else {
                body["planYears"] = selectedPlan.years
                body["planCents"] = selectedPlan.priceCents
                body["addOnCents"] = addOns[selectedAddOnIndex].cents
                body["tokenCount"] = selectedPlan.baseTokens + addOns[selectedAddOnIndex].tokens
            }
        }
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let errorMsg = json["error"] as? String {
                    DispatchQueue.main.async { self.isProcessing = false; self.presenter.resumeCheckoutAction = nil; self.paymentStatusMessage = String(localized: "Stripe Error: \(errorMsg)") }
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
        
        if selectedTab == 0 {
            let selectedPlan = plans[selectedPlanIndex]
            body["planCents"] = selectedPlan.priceCents
            body["planYears"] = selectedPlan.years
        } else if selectedTab == 2 {
            // 🚀 Ensure Tier 1 plan details are passed on free trial success
            body["planCents"] = 1000
            body["planYears"] = 1
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
                        // Note: This updates the local UI immediately. The webhook will permanently secure this in DynamoDB.
                        vm.tokenBalance += pendingTokenReward
                        
                        if selectedTab != 1 {
                            vm.hasActiveSubscription = true
                            
                            if selectedTab == 0 {
                                let selectedPlan = plans[selectedPlanIndex]
                                vm.activePlanCents = selectedPlan.priceCents
                                vm.activePlanYears = selectedPlan.years
                                UserDefaults.standard.set(false, forKey: "isFreeTrial_\(vm.userEmail)")
                            } else if selectedTab == 2 {
                                vm.activePlanCents = 1000
                                vm.activePlanYears = 1
                                UserDefaults.standard.set(true, forKey: "isFreeTrial_\(vm.userEmail)")
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
