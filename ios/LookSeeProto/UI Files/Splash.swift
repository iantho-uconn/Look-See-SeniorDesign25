//
//  Splash.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/8/25.
//
import SwiftUI

struct Splash: View {
    @State private var logoScale: CGFloat = 0.7
    @State private var logoOpacity: Double = 0
    @State private var textOpacity: Double = 0
    @State private var buttonOpacity: Double = 0

    var body: some View {
        NavigationView {
            ZStack {
                // Background
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()

                // Subtle glow behind logo
                Circle()
                    .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.15))
                    .frame(width: 340, height: 340)
                    .blur(radius: 60)
                    .offset(y: -60)

                VStack(spacing: 0) {

                    Spacer()

                    // Logo
                    VStack(spacing: 16) {
                        ZStack {
                            Circle()
                                .fill(Color(red: 0.22, green: 0.49, blue: 1.00).opacity(0.12))
                                .frame(width: 120, height: 120)
                            Image(systemName: "eye.square.fill")
                                .font(.system(size: 64))
                                .foregroundStyle(Color(red: 0.22, green: 0.49, blue: 1.00))
                        }
                        .scaleEffect(logoScale)
                        .opacity(logoOpacity)

                        VStack(spacing: 8) {
                            Text("LookSee")
                                .font(.system(size: 36, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                            Text("Explore landmarks and buildings around you")
                                .font(.subheadline)
                                .foregroundStyle(Color.white.opacity(0.5))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)
                        }
                        .opacity(textOpacity)
                    }

                    Spacer()

                    // Bottom section
                    VStack(spacing: 12) {
                        NavigationLink(destination: Main().toolbarVisibility(.hidden)) {
                            HStack(spacing: 10) {
                                Text("Get Started")
                                    .font(.system(size: 17, weight: .semibold))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 15, weight: .semibold))
                            }
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                            .background(Color(red: 0.22, green: 0.49, blue: 1.00))
                            .cornerRadius(16)
                        }

                        Text("By continuing you agree to our Terms & Privacy Policy")
                            .font(.caption2)
                            .foregroundStyle(Color.white.opacity(0.3))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 28)
                    .padding(.bottom, 48)
                    .opacity(buttonOpacity)
                }
            }
            .onAppear {
                withAnimation(.spring(duration: 0.7)) {
                    logoScale = 1.0
                    logoOpacity = 1.0
                }
                withAnimation(.easeOut(duration: 0.6).delay(0.3)) {
                    textOpacity = 1.0
                }
                withAnimation(.easeOut(duration: 0.6).delay(0.55)) {
                    buttonOpacity = 1.0
                }
            }
        }
    }
}

#Preview {
    Splash()
}
