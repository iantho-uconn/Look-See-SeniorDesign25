//
//  MerchantCard.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/27/26.
//

import SwiftUI

struct MerchantCard: View {
    let storeName: String
    let logoUrl: String
    let bio: String
    let phone: String
    
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 16) {
                ZStack {
                    Circle().fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15))
                    
                    if let url = URL(string: logoUrl), !logoUrl.isEmpty {
                        AsyncImage(url: url) { phase in
                            if let image = phase.image {
                                image.resizable().scaledToFill().clipShape(Circle())
                            } else if phase.error != nil {
                                Image(systemName: "storefront.fill").font(.system(size: 24)).foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                            } else {
                                ProgressView()
                            }
                        }
                    } else {
                        Image(systemName: "storefront.fill").font(.system(size: 24)).foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                    }
                }
                .frame(width: 56, height: 56)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(storeName)
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("Verified Business")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(.green)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.green.opacity(0.15))
                        .clipShape(Capsule())
                }
                Spacer()
            }
            
            Text(bio)
                .font(.system(size: 14, weight: .regular))
                .foregroundStyle(.secondary)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
            
            if !phone.isEmpty {
                Divider().padding(.vertical, 4)
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").font(.system(size: 14)).foregroundStyle(.gray).frame(width: 20)
                    Text(phone).font(.system(size: 14, weight: .medium)).foregroundStyle(.primary)
                }
            }
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.05), lineWidth: 1))
        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 4)
    }
}
