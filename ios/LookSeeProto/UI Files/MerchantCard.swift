import SwiftUI

/// Modern state-driven card for the AR popup view
struct MerchantCardView: View {
    @ObservedObject var vm = VariableContainer.shared
    @Environment(\.openURL) var openURL
    @State private var showWebsiteAlert = false

    private let cardBackground = Color.white.opacity(0.08)
    private let cardBorder = Color.white.opacity(0.10)
    private let titleText = Color.white
    private let secondaryText = Color.white.opacity(0.78)
    private let tertiaryText = Color.white.opacity(0.62)

    var body: some View {
        if !vm.merchantName.isEmpty {
            VStack(alignment: .leading, spacing: 12) {

                // MARK: - Header & Verified Badge
                HStack {
                    HStack(spacing: 4) {
                        Image(systemName: "sparkles")
                            .font(.caption2)
                            .foregroundColor(.yellow)
                        Text("LANDMARK OWNER")
                            .font(.system(size: 10, weight: .bold))
                            .tracking(1.0)
                            .foregroundColor(tertiaryText)
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
                    if vm.merchantLogoUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Image(systemName: "storefront.fill")
                            .foregroundColor(.white)
                            .frame(width: 52, height: 52)
                            .background(Color.white.opacity(0.10))
                            .clipShape(Circle())
                    } else {
                        AsyncImage(url: URL(string: vm.merchantLogoUrl)) { phase in
                            switch phase {
                            case .empty:
                                ProgressView()
                                    .tint(.white)
                                    .frame(width: 52, height: 52)
                            case .success(let image):
                                image
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 52, height: 52)
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 1))
                            case .failure(_):
                                Image(systemName: "storefront.fill")
                                    .foregroundColor(.white)
                                    .frame(width: 52, height: 52)
                                    .background(Color.white.opacity(0.10))
                                    .clipShape(Circle())
                            @unknown default:
                                EmptyView()
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(vm.merchantName)
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundColor(titleText)

                        if !vm.merchantBio.isEmpty {
                            Text(vm.merchantBio)
                                .font(.subheadline)
                                .foregroundColor(secondaryText)
                                .lineLimit(2)
                        }
                    }
                    Spacer()
                }

                // MARK: - Contact Info Hyperlinks
                if !vm.merchantPhone.isEmpty || !vm.merchantWebsite.isEmpty || !vm.merchantAddress.isEmpty {
                    Divider()
                        .background(Color.white.opacity(0.12))
                    
                    VStack(alignment: .leading, spacing: 10) {
                        if !vm.merchantPhone.isEmpty {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                let digits = vm.merchantPhone.filter { "0123456789".contains($0) }
                                if let url = URL(string: "tel://\(digits)") {
                                    openURL(url) // iOS automatically prompts to confirm calls
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "phone.fill").font(.caption).foregroundColor(.green).frame(width: 16)
                                    Text(vm.merchantPhone).font(.subheadline).foregroundColor(.blue).underline()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                        
                        if !vm.merchantWebsite.isEmpty {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                showWebsiteAlert = true
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "globe").font(.caption).foregroundColor(.blue).frame(width: 16)
                                    Text(vm.merchantWebsite).font(.subheadline).foregroundColor(.blue).underline()
                                }
                            }
                            .buttonStyle(.plain)
                            .alert("Leave LookSee?", isPresented: $showWebsiteAlert) {
                                Button("Cancel", role: .cancel) { }
                                Button("Visit Website") {
                                    var urlStr = vm.merchantWebsite
                                    if !urlStr.lowercased().hasPrefix("http") {
                                        urlStr = "https://" + urlStr
                                    }
                                    if let url = URL(string: urlStr) {
                                        openURL(url)
                                    }
                                }
                            } message: {
                                Text("You are about to open Safari to visit this merchant's website.")
                            }
                        }
                        
                        if !vm.merchantAddress.isEmpty {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                let encodedAddress = vm.merchantAddress.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                                if let url = URL(string: "maps://?q=\(encodedAddress)") {
                                    openURL(url)
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "mappin.and.ellipse").font(.caption).foregroundColor(.red).frame(width: 16)
                                    Text(vm.merchantAddress).font(.subheadline).foregroundColor(.blue).underline()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(14)
            .background(cardBackground)
            .cornerRadius(14)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(cardBorder, lineWidth: 0.5)
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
    let website: String
    let address: String

    @Environment(\.openURL) var openURL
    @State private var showWebsiteAlert = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                if logoUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Image(systemName: "storefront.fill")
                        .foregroundColor(.white)
                        .frame(width: 50, height: 50)
                        .background(Color.gray.opacity(0.3))
                        .clipShape(Circle())
                } else {
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

            if !phone.isEmpty || !website.isEmpty || !address.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    if !phone.isEmpty {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            let digits = phone.filter { "0123456789".contains($0) }
                            if let url = URL(string: "tel://\(digits)") {
                                openURL(url)
                            }
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "phone.fill").font(.caption).foregroundColor(.green).frame(width: 16)
                                Text(phone).font(.subheadline).foregroundColor(.blue).underline()
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    
                    if !website.isEmpty {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            showWebsiteAlert = true
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "globe").font(.caption).foregroundColor(.blue).frame(width: 16)
                                Text(website).font(.subheadline).foregroundColor(.blue).underline()
                            }
                        }
                        .buttonStyle(.plain)
                        .alert("Leave LookSee?", isPresented: $showWebsiteAlert) {
                            Button("Cancel", role: .cancel) { }
                            Button("Visit Website") {
                                var urlStr = website
                                if !urlStr.lowercased().hasPrefix("http") {
                                    urlStr = "https://" + urlStr
                                }
                                if let url = URL(string: urlStr) {
                                    openURL(url)
                                }
                            }
                        } message: {
                            Text("You are about to open Safari to visit this merchant's website.")
                        }
                    }
                    
                    if !address.isEmpty {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            let encodedAddress = address.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                            if let url = URL(string: "maps://?q=\(encodedAddress)") {
                                openURL(url)
                            }
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "mappin.and.ellipse").font(.caption).foregroundColor(.red).frame(width: 16)
                                Text(address).font(.subheadline).foregroundColor(.blue).underline()
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 4)
            }
        }
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .cornerRadius(14)
    }
}
