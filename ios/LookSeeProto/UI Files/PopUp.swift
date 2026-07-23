//
//  PopUp.swift
//  LookSeeProto
//

import SwiftUI

struct PopUp: View {
    @ObservedObject private var infoView = VariableContainer.shared
    
    var body: some View {
        VStack(spacing: 0) {
            
            // MARK: - Adaptive Header (Shows full image if present, or compact gradient if not)
            ZStack(alignment: .topTrailing) {
                if !infoView.landmarkURL.isEmpty, let url = URL(string: infoView.landmarkURL) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(height: 180)
                                .clipped()
                        } else if phase.error != nil {
                            compactGradientHeader
                        } else {
                            ZStack {
                                compactGradientHeader
                                ProgressView().tint(.white)
                            }
                        }
                    }
                    .frame(height: 180)
                } else {
                    compactGradientHeader
                }
                
                // Floating Close X Button
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    infoView.dismissLandmark()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(.ultraThinMaterial)
                        .clipShape(Circle())
                        .padding(12)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(6)
            
            // MARK: - Self-Sizing Content Area
            VStack(alignment: .leading, spacing: 16) {
                
                VStack(alignment: .leading, spacing: 6) {
                    Text(infoView.landmarkName)
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    
                    Text(infoView.landmarkDescription)
                        .font(.system(size: 15, weight: .regular, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                
                // MARK: - Promotion Section (Only expands card when present)
                if infoView.promoName != "No active promotion" && !infoView.promoName.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Image(systemName: "sparkles")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(Color.orange)
                            Text("Active Promotion")
                                .font(.system(size: 11, weight: .bold, design: .rounded))
                                .foregroundStyle(Color.orange)
                                .textCase(.uppercase)
                        }
                        
                        Text(infoView.promoName)
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                        
                        if !infoView.promoDescription.isEmpty {
                            Text(infoView.promoDescription)
                                .font(.system(size: 14, weight: .medium, design: .rounded))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.orange.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.orange.opacity(0.25), lineWidth: 1)
                    )
                }
                
                // MARK: - Action Button
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    infoView.dismissLandmark()
                } label: {
                    Text("Got it")
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .foregroundStyle(Color(uiColor: .systemBackground))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.primary)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(.ultraThickMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .stroke(Color.white.opacity(0.2), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.3), radius: 30, x: 0, y: 15)
        .padding(.horizontal, 28)
    }
    
    // Compact 70pt gradient header when no image is present
    private var compactGradientHeader: some View {
        ZStack(alignment: .leading) {
            LinearGradient(
                colors: [Color(red: 0.25, green: 0.1, blue: 0.9), Color(red: 0.5, green: 0.15, blue: 0.95)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            
            HStack(spacing: 10) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white.opacity(0.9))
                
                Text("Landmark Details")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.9))
            }
            .padding(.leading, 18)
        }
        .frame(height: 70)
    }
}
