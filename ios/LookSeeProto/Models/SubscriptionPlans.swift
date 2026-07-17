//
//  SubscriptionPlans.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/16/26.
//

import SwiftUI

struct SubscriptionPlans: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var authState: AuthState
    @State private var selectedPlan: Int = 1
    @State private var navigateToSetup = false
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()
                
                GeometryReader { geo in
                    ZStack {
                        Circle()
                            .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15))
                            .frame(width: geo.size.width * 0.8, height: geo.size.width * 0.8)
                            .blur(radius: 60)
                            .offset(x: -geo.size.width * 0.3, y: -geo.size.height * 0.2)
                        
                        Circle()
                            .fill(Color(red: 0.11, green: 0.22, blue: 0.55).opacity(0.15))
                            .frame(width: geo.size.width * 0.9, height: geo.size.width * 0.9)
                            .blur(radius: 80)
                            .offset(x: geo.size.width * 0.4, y: geo.size.height * 0.4)
                        
                        Image(systemName: "building.2.crop.circle.fill")
                            .font(.system(size: geo.size.width * 1.2))
                            .foregroundStyle(Color.white.opacity(0.02))
                            .offset(x: geo.size.width * 0.25, y: geo.size.height * 0.15)
                    }
                }
                .ignoresSafeArea()
                
                VStack(spacing: 24) {
                    HStack {
                        Spacer()
                        Button {
                            dismiss()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title2)
                                .foregroundStyle(.white.opacity(0.4))
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 16)
                    
                    VStack(spacing: 6) {
                        Text("LookSee")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                        
                        Text(authState.tier == .business ? "Manage Membership" : "Upgrade to LookSee Business")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                    }
                    
                    TabView(selection: $selectedPlan) {
                        planCard(title: "Classic", price: "$10", unit: "/year", description: "Perfect for local independent shops", features: ["Up to 5 active landmarks", "2 model swaps per month", "Standard model delivery"], badge: "MOST AFFORDABLE", index: 0)
                            .tag(0)
                        
                        planCard(title: "Intermediate", price: "$40", unit: "/year", description: "For expanding storefronts and regional venues", features: ["Up to 20 active landmarks", "5 model swaps per month", "On-demand extra swaps ($3/ea)", "Add landmarks ($5/ea, cap 5/mo)"], badge: "MOST POPULAR", index: 1)
                            .tag(1)
                        
                        planCard(title: "Advanced", price: "$75", unit: "/year", description: "For extensive properties and museums", features: ["Up to 100 active landmarks", "10 model swaps per month", "On-demand extra swaps ($3/ea)", "Add landmarks ($2/ea, cap 10/mo)"], badge: "MAXIMUM SCALE", index: 2)
                            .tag(2)
                    }
                    .tabViewStyle(.page(indexDisplayMode: .always))
                    .frame(height: 420)
                    
                    Spacer()
                    
                    HStack(spacing: 6) {
                        Image(systemName: "lock.shield.fill")
                        Text("Secured by Stripe. Cancel or upgrade at any time.")
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 16)
                }
            }
            .navigationDestination(isPresented: $navigateToSetup) {
                BusinessSetup(selectedPlanIndex: selectedPlan, isAnnualPlan: true)
            }
        }
    }
    
    @ViewBuilder
    private func planCard(title: String, price: String, unit: String, description: String, features: [String], badge: String, index: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Text(badge)
                    .font(.system(size: 10, weight: .black))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Spacer()
            }
            .padding(.top, -10)
            .padding(.bottom, 6)
            
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
            
            HStack(alignment: .bottom, spacing: 2) {
                Text(price)
                    .font(.system(size: 34, weight: .black, design: .rounded))
                    .foregroundStyle(.white)
                Text(unit)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 6)
            }
            .padding(.top, 4)
            
            Text(description)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .padding(.top, 2)
                .fixedSize(horizontal: false, vertical: true)
            
            Divider()
                .background(Color.white.opacity(0.12))
                .padding(.vertical, 14)
            
            VStack(alignment: .leading, spacing: 10) {
                ForEach(features, id: \.self) { feature in
                    HStack(spacing: 10) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .font(.system(size: 15))
                        Text(feature)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
            
            Spacer()
            
            Button {
                navigateToSetup = true
            } label: {
                Text(authState.tier == .business ? "Upgrade Plan" : "Select Plan")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 36)
        .padding(.bottom, 40)
        .background(Color.white.opacity(0.04).background(.ultraThinMaterial))
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.8), lineWidth: 1.5)
        )
        .padding(.horizontal, 24)
    }
}
