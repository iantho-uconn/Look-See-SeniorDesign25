//
//  MerchantCard.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/27/26.
//


import SwiftUI

/// Modern state-driven card for the AR popup view
struct MerchantCardView: View {
    @ObservedObject var vm = VariableContainer.shared

    var body: some View {
        // Only show the card if we actually have merchant data loaded
        if !vm.merchantName.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                
                // MARK: - Header & Verified Badge
                HStack {
                    HStack(spacing: 4) {
                        Image(systemName: "sparkles")
                            .font(.caption2)
                            .foregroundColor(.yellow)
                        Text("LANDMARK SPONSORED BY")
                            .font(.system(size: 10, weight: .bold))
                            .tracking(1.0)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.caption2)
                            .foregroundColor(.blue)
                        Text("Verified")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(.blue)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.blue.opacity(0.15))
                    .cornerRadius(6)
                }

                // MARK: - Logo & Business Info
                HStack(alignment: .center, spacing: 12) {
                    AsyncImage(url: URL(string: vm.merchantLogoUrl)) { phase in
                        switch phase {
                        case .empty:
                            ProgressView()
                                .frame(width: 52, height: 52)
                        case .success(let image):
                            image
                                .resizable()
                                .scaledToFill()
                                .frame(width: 52, height: 52)
                                .clipShape(Circle())
                                .overlay(Circle().stroke(Color.white.opacity(0.2), lineWidth: 1))
                        case .failure(_):
                            Image(systemName: "storefront.fill")
                                .foregroundColor(.white)
                                .frame(width: 52, height: 52)
                                .background(Color.gray.opacity(0.3))
                                .clipShape(Circle())
                        @unknown default:
                            EmptyView()
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(vm.merchantName)
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)

                        if !vm.merchantBio.isEmpty {
                            Text(vm.merchantBio)
                                .font(.subheadline)
                                .foregroundColor(.white.opacity(0.75))
                                .lineLimit(2)
                        }
                    }
                    Spacer()
                }

                // MARK: - Phone Number Row (Optional)
                if !vm.merchantPhone.isEmpty {
                    Divider()
                        .background(Color.white.opacity(0.1))

                    HStack(spacing: 8) {
                        Image(systemName: "phone.fill")
                            .font(.caption)
                            .foregroundColor(.green)
                        Text(vm.merchantPhone)
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.9))
                        Spacer()
                    }
                }
            }
            .padding(14)
            .background(Color(uiColor: .systemGray6).opacity(0.2))
            .cornerRadius(14)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.white.opacity(0.1), lineWidth: 0.5)
            )
        }
    }
}

/// Legacy Parameterized Merchant Card (Required by Settings.swift)
struct MerchantCard: View {
    let storeName: String
    let logoUrl: String
    let bio: String
    let phone: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                AsyncImage(url: URL(string: logoUrl)) { phase in
                    switch phase {
                    case .empty:
                        ProgressView()
                            .frame(width: 50, height: 50)
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                            .frame(width: 50, height: 50)
                            .clipShape(Circle())
                    case .failure(_):
                        Image(systemName: "storefront.fill")
                            .foregroundColor(.white)
                            .frame(width: 50, height: 50)
                            .background(Color.gray.opacity(0.3))
                            .clipShape(Circle())
                    @unknown default:
                        EmptyView()
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(storeName)
                        .font(.headline)
                        .fontWeight(.semibold)
                    
                    if !bio.isEmpty {
                        Text(bio)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
            }

            if !phone.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "phone.fill")
                        .font(.caption)
                        .foregroundColor(.green)
                    Text(phone)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
    }
}
