//
//  AdBannerView.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 9/9/26.
//

import SwiftUI
import GoogleMobileAds
import UserMessagingPlatform
import UIKit

struct AdBannerView: View {
    @EnvironmentObject private var consent: AdConsentManager

    var body: some View {
        GeometryReader { geometry in
            if consent.adsReady && geometry.size.width >= 320 {
                BannerContainer(recoveryGeneration: consent.recoveryGeneration)
                    .frame(width: 320, height: 50)
                    .frame(
                        width: geometry.size.width,
                        height: 50,
                        alignment: .center
                    )
            }
        }
        .frame(height: 50)
        .background(Color(uiColor: .systemBackground))
    }
}

private struct BannerContainer: UIViewRepresentable {
    let recoveryGeneration: Int

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> BannerView {
        // A compact, fixed-height banner keeps more camera area visible.
        let banner = BannerView(adSize: AdSizeBanner)

        // Test ads in every build configuration, including TestFlight.
        banner.adUnitID =
            "ca-app-pub-3940256099942544/2435281174"

        banner.delegate = context.coordinator
        context.coordinator.generation = recoveryGeneration
        context.coordinator.banner = banner
        banner.load(Request())

        return banner
    }

    func updateUIView(_ banner: BannerView, context: Context) {
        guard context.coordinator.generation != recoveryGeneration else { return }
        context.coordinator.generation = recoveryGeneration
        context.coordinator.retryIfNeeded()
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: BannerView,
        context: Context
    ) -> CGSize? {
        CGSize(width: 320, height: 50)
    }

    static func dismantleUIView(
        _ banner: BannerView,
        coordinator: Coordinator
    ) {
        coordinator.retryTask?.cancel()
        coordinator.banner = nil
        banner.delegate = nil
    }

    @MainActor
    final class Coordinator: NSObject, BannerViewDelegate {
        weak var banner: BannerView?
        var generation = 0
        var failed = false
        var retryTask: Task<Void, Never>?

        func retryIfNeeded() {
            guard failed, let banner,
                  banner.window != nil,
                  UIApplication.shared.applicationState == .active,
                  ConsentInformation.shared.canRequestAds else { return }
            failed = false
            retryTask?.cancel()
            print("[AdMob] Retrying failed banner")
            banner.load(Request())
        }

        func bannerViewDidReceiveAd(_ bannerView: BannerView) {
            failed = false
            retryTask?.cancel()
            print("[AdMob] Test banner loaded")
        }

        func bannerView(
            _ bannerView: BannerView,
            didFailToReceiveAdWithError error: Error
        ) {
            failed = true
            retryTask?.cancel()
            retryTask = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: 30_000_000_000) }
                catch { return }
                self?.retryIfNeeded()
            }
            print(
                "[AdMob] Banner failed to load: "
                + error.localizedDescription
            )
        }
    }
}
