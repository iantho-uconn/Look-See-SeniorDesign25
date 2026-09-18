import Combine
import GoogleMobileAds
import UserMessagingPlatform
import Network
import UIKit

@MainActor
final class AdConsentManager: ObservableObject {
    @Published private(set) var adsReady = false
    @Published private(set) var isPrivacyOptionsRequired = false
    @Published private(set) var lastError: String?
    @Published private(set) var recoveryGeneration = 0

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "LookSee.AdConnectivity")
    private var foregroundObserver: NSObjectProtocol?
    private var lastPathSatisfied: Bool?
    private var didStartConsentFlow = false
    private var consentNeedsRetry = false
    private var consentInProgress = false
    private var didStartAdsSDK = false
    private var sdkInitialized = false
    private var isPresentingPrivacyOptions = false

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                let previous = self.lastPathSatisfied
                self.lastPathSatisfied = connected
                if connected && previous != true && self.didStartConsentFlow {
                    await self.recover()
                }
            }
        }
        monitor.start(queue: monitorQueue)
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.recover()
            }
        }
    }

    deinit {
        monitor.cancel()
        if let foregroundObserver {
            NotificationCenter.default.removeObserver(foregroundObserver)
        }
    }

    func start() async {
        guard !didStartConsentFlow else { return }
        didStartConsentFlow = true
        #if DEBUG
        // Explicit testing only. Remove this launch argument after one run.
        if ProcessInfo.processInfo.arguments.contains("-LookSeeResetConsent") {
            ConsentInformation.shared.reset()
            print("[AdMob][Debug] Consent reset for this test launch")
        }
        #endif
        await updateConsent()
    }

    private func recover() async {
        guard didStartConsentFlow,
              UIApplication.shared.applicationState == .active,
              lastPathSatisfied != false,
              !consentInProgress,
              !isPresentingPrivacyOptions else { return }
        if consentNeedsRetry {
            print("[AdMob] Retrying consent after connection or foreground change")
            await updateConsent()
        }
        recoveryGeneration += 1
    }

    private func updateConsent() async {
        guard !consentInProgress else { return }
        consentInProgress = true
        defer { consentInProgress = false }
        lastError = nil
        consentNeedsRetry = false
        print("[AdMob] Checking consent")

        let parameters = RequestParameters()
        #if DEBUG
        let debugSettings = DebugSettings()
        debugSettings.testDeviceIdentifiers = [
            "D38B8231-6C90-4FBF-9B56-9F57A104E979"
        ]
        if ProcessInfo.processInfo.arguments.contains("-LookSeeConsentEEA") {
            debugSettings.geography = .EEA
            print("[AdMob][Debug] Simulating EEA on registered test devices")
        }
        parameters.debugSettings = debugSettings
        #endif
        let updateError: Error? = await withCheckedContinuation { continuation in
            ConsentInformation.shared.requestConsentInfoUpdate(with: parameters) { error in
                continuation.resume(returning: error)
            }
        }
        if let updateError {
            recordError(updateError)
            consentNeedsRetry = true
        } else {
            do {
                try await ConsentForm.loadAndPresentIfRequired(from: nil)
            } catch {
                recordError(error)
                consentNeedsRetry = true
            }
        }
        Self.logPrivacyDiagnostics("Consent flow finished")
        refreshConsentState()
        initializeAdsIfAllowed()
    }

    func showPrivacyOptions() async {
        guard isPrivacyOptionsRequired,
              !isPresentingPrivacyOptions,
              !consentInProgress else { return }
        #if DEBUG
        let before = Self.consentSnapshot()
        Self.logPrivacyDiagnostics("Before opening privacy options")
        #endif
        isPresentingPrivacyOptions = true
        lastError = nil
        adsReady = false
        defer {
            isPresentingPrivacyOptions = false
            Self.logPrivacyDiagnostics("Privacy options closed")
            #if DEBUG
            let after = Self.consentSnapshot()
            let changedKeys = Set(before.keys).union(after.keys)
                .filter { before[$0] != after[$0] }.sorted()
            print("[AdMob][Debug] Saved consent keys changed: \(changedKeys.isEmpty ? "none" : changedKeys.joined(separator: ", "))")
            #endif
            refreshConsentState()
            initializeAdsIfAllowed()
        }
        do {
            try await ConsentForm.presentPrivacyOptionsForm(from: nil)
        } catch {
            recordError(error)
        }
    }

    private func refreshConsentState() {
        let consent = ConsentInformation.shared
        isPrivacyOptionsRequired = consent.privacyOptionsRequirementStatus == .required
        adsReady = sdkInitialized && consent.canRequestAds && !isPresentingPrivacyOptions
    }

    private func initializeAdsIfAllowed() {
        guard ConsentInformation.shared.canRequestAds else {
            adsReady = false
            print("[AdMob] Ads are not currently allowed by UMP")
            return
        }
        guard !didStartAdsSDK else { return }
        didStartAdsSDK = true
        MobileAds.shared.start { _ in
            Task { @MainActor in
                self.sdkInitialized = true
                self.refreshConsentState()
                print("[AdMob] SDK initialized")
            }
        }
    }

    #if DEBUG
    // Read only. Full consent strings are compared in memory, never printed.
    private static func consentSnapshot() -> [String: String] {
        let keys = [
            "IABTCF_TCString", "IABTCF_PurposeConsents",
            "IABTCF_PurposeLegitimateInterests", "IABTCF_VendorConsents",
            "IABTCF_VendorLegitimateInterests", "IABTCF_SpecialFeaturesOptIns",
            "IABGPP_HDR_GppString"
        ]
        var values: [String: String] = [:]
        for key in keys {
            if let value = UserDefaults.standard.string(forKey: key) {
                values[key] = value
            }
        }
        return values
    }
    #endif

    /// Diagnostics only; never use stored strings to authorize ads.
    static func logPrivacyDiagnostics(_ stage: String) {
        #if DEBUG
        let consent = ConsentInformation.shared
        let defaults = UserDefaults.standard
        let hasGPP = !(defaults.string(forKey: "IABGPP_HDR_GppString") ?? "").isEmpty
        let hasTCF = !(defaults.string(forKey: "IABTCF_TCString") ?? "").isEmpty
        let rdp: String
        if defaults.object(forKey: "gad_rdp") == nil {
            rdp = "unset (GPP may still signal restrictions)"
        } else {
            rdp = String(defaults.bool(forKey: "gad_rdp"))
        }
        print("[AdMob][Debug] \(stage): canRequestAds=\(consent.canRequestAds), consentStatus=\(consent.consentStatus), privacyOptions=\(consent.privacyOptionsRequirementStatus)")
        print("[AdMob][Debug] Stored signals: GPP present=\(hasGPP), TCF present=\(hasTCF), gad_rdp=\(rdp)")
        for key in ["IABTCF_PurposeConsents", "IABTCF_PurposeLegitimateInterests",
                    "IABTCF_SpecialFeaturesOptIns"] {
            print("[AdMob][Debug] \(key)=\(defaults.string(forKey: key) ?? "missing")")
        }
        if let vendors = defaults.string(forKey: "IABTCF_VendorConsents") {
            let granted = vendors.filter { $0 == "1" }.count
            print("[AdMob][Debug] Vendor consent bits granted=\(granted), total bits=\(vendors.count)")
        } else {
            print("[AdMob][Debug] Vendor consent bits missing")
        }
        #endif
    }

    private func recordError(_ error: Error) {
        lastError = error.localizedDescription
        print("[AdMob] Consent error: \(error.localizedDescription)")
    }
}
