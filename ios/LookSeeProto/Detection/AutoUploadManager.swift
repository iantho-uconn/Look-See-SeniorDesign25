// AutoUploadManager.swift
import Foundation
import SwiftUI
import Combine
import UserNotifications
import UIKit

@MainActor
class AutoUploadManager: ObservableObject {
    static let shared = AutoUploadManager()
    private var cancellables = Set<AnyCancellable>()
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    weak var globalAuthVM: AuthViewModel?
    @Published var isUploading = false
    @Published var currentlyUploadingId: UUID? = nil
    @Published var currentUploadProgress: Double = 0
    @Published private(set) var queueMessage = "Ready to upload."
    private var isPaused = false
    private let uploadService = UploadService()
    private let hardNegativeUploadService = HardNegativeUploadService()

    private init() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        uploadService.$progress.receive(on: RunLoop.main)
            .sink { [weak self] in self?.currentUploadProgress = $0 }
            .store(in: &cancellables)
        NetworkMonitor.shared.$isConnected.receive(on: RunLoop.main)
            .sink { [weak self] connected in
                guard let self else { return }
                if connected { Task { await self.autoStartIfPossible() } }
                else if !self.isUploading { self.queueMessage = "Waiting for an internet connection." }
            }.store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                // Never reset the running flag while an async upload is alive.
                Task { await self?.autoStartIfPossible() }
            }.store(in: &cancellables)
        OfflineMediaManager.shared.$archivedItems
            .map { $0.map(\.id) }.removeDuplicates().receive(on: RunLoop.main)
            .sink { [weak self] _ in Task { await self?.autoStartIfPossible() } }
            .store(in: &cancellables)
    }

    func attachAuthVM(_ vm: AuthViewModel) {
        globalAuthVM = vm
        Task { await autoStartIfPossible() }
    }

    func forceRetry() {
        guard !isUploading else { return }
        isPaused = false
        OfflineMediaManager.shared.clearRetryableFailures()
        Task { await autoStartIfPossible() }
    }

    func stopProcessing() {
        isPaused = true
        queueMessage = isUploading ? "Pausing after the current request." : "Uploads paused. Tap Retry uploads to resume."
        // Keep ownership of the active async operation until its defer runs.
    }

    func startProcessing(authViewModel: AuthViewModel) async {
        guard !isUploading else { return }
        globalAuthVM = authViewModel
        isPaused = false
        OfflineMediaManager.shared.clearRetryableFailures()
        await autoStartIfPossible()
    }

    private func autoStartIfPossible() async {
        guard !isUploading else { return }
        guard !isPaused else { return }
        guard NetworkMonitor.shared.isConnected else {
            queueMessage = "Waiting for an internet connection."
            print("[UploadQueue] Waiting: offline")
            return
        }
        guard let authVM = globalAuthVM else {
            queueMessage = "Waiting for your account to load."
            print("[UploadQueue] Waiting: account not attached")
            return
        }
        await processOfflineQueue(authVM: authVM)
    }

    private func beginBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        backgroundTaskID = UIApplication.shared.beginBackgroundTask { [weak self] in
            Task { @MainActor in self?.endBackgroundTask() }
        }
    }

    private func endBackgroundTask() {
        if backgroundTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTaskID)
            backgroundTaskID = .invalid
        }
    }

    private func nextQueuedMedia() -> ArchivedMedia? {
        OfflineMediaManager.shared.archivedItems
            .filter { $0.deletionBlocked != true && $0.lastUploadError == nil }
            .sorted { $0.dateSaved < $1.dateSaved }.first
    }

    private func processOfflineQueue(authVM: AuthViewModel) async {
        guard !isUploading, !isPaused else { return }
        guard nextQueuedMedia() != nil else {
            queueMessage = OfflineMediaManager.shared.archivedItems.isEmpty
                ? "No uploads waiting." : "Some uploads need attention. See the messages below."
            return
        }
        // Lock before the first await, including account refresh and token lookup.
        isUploading = true
        beginBackgroundTask()
        defer {
            isUploading = false
            currentlyUploadingId = nil
            currentUploadProgress = 0
            endBackgroundTask()
        }
        queueMessage = "Checking your account…"
        print("[UploadQueue] Checking account before upload")
        await authVM.fetchUserUsageStats()
        let idToken = await authVM.fetchIdToken()
        guard !idToken.isEmpty else {
            queueMessage = "Sign in to resume uploads."
            print("[UploadQueue] Waiting: no sign-in token")
            return
        }
        while let media = nextQueuedMedia() {
            if isPaused { queueMessage = "Uploads paused. Tap Retry uploads to resume."; return }
            guard NetworkMonitor.shared.isConnected else {
                queueMessage = "Waiting for an internet connection."
                print("[UploadQueue] Waiting: connection lost")
                return
            }
            guard authVM.hasActiveSubscription else {
                queueMessage = "An active subscription or free trial is required."
                print("[UploadQueue] Waiting: no active subscription")
                return
            }
            // Existing-landmark redos don't consume a creation token.
            if media.landmarkId == nil && media.positiveUploadCompleted != true && authVM.tokenBalance <= 0 {
                queueMessage = "A token is required to create this landmark."
                print("[UploadQueue] Waiting: no creation tokens")
                return
            }
            currentlyUploadingId = media.id
            currentUploadProgress = 0
            queueMessage = "Uploading media…"
            let manager = OfflineMediaManager.shared
            let landmarkID = manager.prepareUploadID(for: media)
            let label = media.savedLabel ?? media.title
            var phase = "positive submission"
            print("[UploadQueue] Starting item=\(media.id) landmark=\(landmarkID)")
            do {
                var finalLandmarkID = landmarkID
                if media.positiveUploadCompleted != true {
                    let fileURL = manager.getFileURL(for: media)
                    let result = try await uploadService.upload(
                        userEmail: authVM.userEmail, idToken: idToken,
                        label: label, landmarkId: landmarkID, landmarkLabel: label,
                        shortDescription: media.savedDescription,
                        userDescription: media.savedUserDescription,
                        latitude: media.latitude, longitude: media.longitude,
                        horizontalAccuracy: 10,
                        videoURLs: media.isVideo ? [fileURL] : [],
                        image: media.isVideo ? nil : UIImage(contentsOfFile: fileURL.path)
                    )
                    finalLandmarkID = result.landmarkId ?? landmarkID
                    manager.recordPositiveCompletion(id: media.id, landmarkID: finalLandmarkID)
                }
                if isPaused { queueMessage = "Uploads paused. Tap Retry uploads to resume."; return }
                if let negativeURL = manager.getNegativeVideoURL(for: media),
                   FileManager.default.fileExists(atPath: negativeURL.path) {
                    phase = "negative reference"
                    queueMessage = "Uploading negative reference…"
                    _ = try await hardNegativeUploadService.upload(
                        landmarkId: finalLandmarkID, idToken: idToken,
                        video: CapturedNegativeVideo(fileURL: negativeURL)
                    )
                }
                manager.deleteArchive(media: media)
                print("[UploadQueue] Completed item=\(media.id)")
                let content = UNMutableNotificationContent()
                content.title = "LookSee Upload Complete"
                content.body = "Your media for '\(label)' has been synced."
                content.sound = .default
                UNUserNotificationCenter.current().add(
                    UNNotificationRequest(
                        identifier: UUID().uuidString, content: content, trigger: nil
                    ),
                    withCompletionHandler: { error in
                        if error != nil {
                            print("[UploadQueue] Upload succeeded, but its notification could not be scheduled.")
                        }
                    }
                )
                // Use the server's balance instead of charging every redo locally.
                await authVM.fetchUserUsageStats()
            } catch {
                let failure = describeFailure(error)
                manager.recordUploadFailure(id: media.id, message: failure.message,
                                            deletionBlocked: failure.blocked)
                print("[UploadQueue] Failed item=\(media.id) phase=\(phase): \(failure.diagnostic)")
                // Failed items remain visible; deletion-blocked ones never retry.
                // Other queued items can continue without this item blocking them.
            }
            currentlyUploadingId = nil
        }
        queueMessage = managerSummary()
    }

    private func managerSummary() -> String {
        OfflineMediaManager.shared.archivedItems.isEmpty ? "No uploads waiting."
            : "Some uploads need attention. See the messages below."
    }

    private func describeFailure(_ error: Error) -> (message: String, blocked: Bool, diagnostic: String) {
        if let uploadError = error as? UploadService.UploadError,
           case let .badStatus(code, body) = uploadError {
            let json = body.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
            let serverMessage = json?["error"] as? String ?? ""
            let blocked = code == 409 && serverMessage == "Landmark deletion has been requested. Uploads are blocked."
            if blocked {
                return ("Upload blocked: deletion has been requested for this landmark. Remove this item from the queue.",
                        true, "HTTP 409: \(serverMessage)")
            }
            // Log status and the safe, known error category, never raw URLs/tokens.
            let message: String
            if code == 409 { message = "Upload conflict. Try again; if it continues, remove and re-add the media. (409)" }
            else { message = uploadError.localizedDescription + " (HTTP \(code))" }
            return (message, false, "HTTP \(code); \(message)")
        }
        return (error.localizedDescription, false, "\(type(of: error)): \(error.localizedDescription)")
    }
}
