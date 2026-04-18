//
//  Buttons.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/25/26.
//
import SwiftUI

struct Buttons: View {
    @EnvironmentObject var vm: AuthViewModel
    @EnvironmentObject var authState: AuthState
    @State private var showPromotion = false
    @State private var showBusinessAlert = false

    var body: some View {
        NavigationStack {
            ZStack {
                // Background
                Color(red: 0.06, green: 0.06, blue: 0.10)
                    .ignoresSafeArea()

                TabView {
                    Tab("Scan", systemImage: "camera.aperture") { LandmarkScan() }
                    if authState.tier == .business {
                            Tab("Record", systemImage: "video") { LandmarkRecord() }
                        }
                }

                // Top nav bar
                VStack {
                    HStack(spacing: 0) {
                        // Library
                        NavigationLink {
                            Library()
                        } label: {
                            NavButton(icon: "archivebox", label: "Library")
                        }

                        Spacer()

                        // LookSee wordmark — tapping opens PromotionEditor
                        Button {
                            if authState.tier == .business {
                                showPromotion = true
                            } else {
                                showBusinessAlert = true
                            }
                        } label: {
                            Text("LookSee")
                                .font(.system(size: 18, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                        }
                        .sheet(isPresented: $showPromotion) {
                            PromotionEditor()
                        }
                        .alert("Business Account Required", isPresented: $showBusinessAlert) {
                            Button("OK", role: .cancel) {}
                        } message: {
                            Text("You need a business account to access the Promotion Editor. Please contact us to upgrade your account.")
                        }

                        Spacer()

                        // Settings
                        NavigationLink {
                            Settings().environmentObject(vm)
                        } label: {
                            NavButton(icon: "gearshape", label: "Settings")
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 10)
                    .background(
                        Color(red: 0.06, green: 0.06, blue: 0.10)
                            .opacity(0.92)
                            .ignoresSafeArea(edges: .top)
                    )

                    Spacer()
                }
            }
        }
    }
}

// MARK: - Nav Button (top bar)
private struct NavButton: View {
    let icon: String
    let label: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.5))
        }
        .frame(width: 56, height: 48)
        .contentShape(Rectangle())
    }
}

//#Preview {
//    Buttons()
//}
