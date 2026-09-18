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

    func makeUIView(context: Context) -> FixedBannerHost {
        let host = FixedBannerHost()
        let coordinator = context.coordinator
        coordinator.generation = recoveryGeneration
        coordinator.host = host
        host.banner.delegate = coordinator
        host.onReady = { [weak coordinator] in
            coordinator?.loadIfNeeded()
        }
        return host
    }

    func updateUIView(_ host: FixedBannerHost, context: Context) {
        let coordinator = context.coordinator
        if coordinator.generation != recoveryGeneration {
            coordinator.generation = recoveryGeneration
            coordinator.retryIfNeeded()
        }
        host.setNeedsLayout()
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: FixedBannerHost,
        context: Context
    ) -> CGSize? {
        CGSize(width: 320, height: 50)
    }

    static func dismantleUIView(_ host: FixedBannerHost, coordinator: Coordinator) {
        host.onReady = nil
        coordinator.retryTask?.cancel()
        coordinator.host = nil
        host.banner.delegate = nil
    }

    @MainActor
    final class Coordinator: NSObject, BannerViewDelegate {
        weak var host: FixedBannerHost?
        var requested = false
        var generation = 0
        var failed = false
        var retryTask: Task<Void, Never>?

        func loadIfNeeded() {
            guard !requested, let host, host.window != nil,
                  host.bounds.width >= 320, host.bounds.height >= 50,
                  UIApplication.shared.applicationState == .active,
                  ConsentInformation.shared.canRequestAds else { return }
            host.layoutIfNeeded()
            // Resolve a presenter only after attachment to this window.
            var responder: UIResponder? = host
            while let current = responder {
                if let controller = current as? UIViewController {
                    host.banner.rootViewController = controller
                    break
                }
                responder = current.next
            }
            guard host.banner.rootViewController != nil else { return }
            requested = true
            failed = false
            host.banner.adSize = AdSizeBanner
            AdConsentManager.logPrivacyDiagnostics("Requesting attached 320x50 test banner")
            #if DEBUG
            print("[AdMob][Debug] Banner bounds=\(host.banner.bounds), adSize=\(host.banner.adSize.size)")
            #endif
            host.banner.load(Request())
        }

        func retryIfNeeded() {
            if failed {
                requested = false
                retryTask?.cancel()
            }
            loadIfNeeded()
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

// SwiftUI sizes the host; the SDK view always keeps its explicit 320x50 frame.
@MainActor
private final class FixedBannerHost: UIView {
    let banner = BannerView(adSize: AdSizeBanner)
    var onReady: (() -> Void)?
    private var readinessScheduled = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        banner.adUnitID = "ca-app-pub-3940256099942544/2435281174"
        banner.frame = CGRect(x: 0, y: 0, width: 320, height: 50)
        addSubview(banner)
    }

    convenience init() {
        self.init(frame: CGRect(x: 0, y: 0, width: 320, height: 50))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: 320, height: 50)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        banner.frame = CGRect(x: max(0, (bounds.width - 320) / 2),
                              y: 0, width: 320, height: 50)
        guard window != nil, bounds.width >= 320, bounds.height >= 50,
              !readinessScheduled else { return }
        readinessScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.readinessScheduled = false
            self.onReady?()
        }
    }
}
