//
//  AutoUploadManager.swift
//  LookSeeProto
//

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
    @Published var currentUploadProgress: Double = 0.0
    
    private var isPaused = false
    
    private let uploadService = UploadService()
    private let hardNegativeUploadService = HardNegativeUploadService()
    
    private init() {
        requestNotificationPermission()
        
        uploadService.$progress
            .receive(on: RunLoop.main)
            .sink { [weak self] p in
                self?.currentUploadProgress = p
            }
            .store(in: &cancellables)
        
        NetworkMonitor.shared.$isConnected
            .dropFirst()
            .sink { [weak self] isConnected in
                if isConnected {
                    // 🚀 THE FIX: Slight delay so network status stabilizes before hitting the DB
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        Task { await self?.autoStartIfPossible() }
                    }
                }
            }
            .store(in: &cancellables)
            
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
            .sink { [weak self] _ in
                if self?.isUploading == true {
                    self?.isUploading = false
                    self?.currentlyUploadingId = nil
                }
                
                // 🚀 THE FIX: Gives iOS 1.5 seconds to wake up the Camera and Network stack
                // before we try to process the queue. Prevents main-thread freezing!
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    Task { await self?.autoStartIfPossible() }
                }
            }
            .store(in: &cancellables)
    }
    
    func attachAuthVM(_ vm: AuthViewModel) {
        self.globalAuthVM = vm
    }
    
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted { print("✅ Notifications authorized for Auto-Uploads") }
        }
    }
    
    func forceRetry() {
        isPaused = false
        Task { await autoStartIfPossible() }
    }
    
    func stopProcessing() {
        isPaused = true
        isUploading = false
        endBackgroundTask()
    }
    
    func startProcessing(authViewModel: AuthViewModel) async {
        isPaused = false
        attachAuthVM(authViewModel)
        await processOfflineQueue(authVM: authViewModel)
    }
    
    private func autoStartIfPossible() async {
        guard !isPaused else { return }
        
        // 🚀 THE FIX: Hard check for Wi-Fi/Cellular. If offline, abort immediately so the UI doesn't hang!
        guard NetworkMonitor.shared.isConnected else {
            print("⚠️ Device is offline. Pausing auto-upload queue.")
            return
        }
        
        guard let authVM = globalAuthVM else {
            print("⚠️ AutoUploadManager has no AuthViewModel attached. Cannot auto-start.")
            return
        }
        
        await authVM.fetchUserUsageStats()
        await processOfflineQueue(authVM: authVM)
    }
    
    private func beginBackgroundTask() {
        if backgroundTaskID == .invalid {
            backgroundTaskID = UIApplication.shared.beginBackgroundTask { [weak self] in
                self?.endBackgroundTask()
            }
        }
    }

    private func endBackgroundTask() {
        if backgroundTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTaskID)
            backgroundTaskID = .invalid
        }
    }
    
    private func processOfflineQueue(authVM: AuthViewModel) async {
        guard !isUploading else { return }
        guard !isPaused else { return }
        
        let pendingMedia = OfflineMediaManager.shared.archivedItems.sorted { $0.dateSaved < $1.dateSaved }
        guard !pendingMedia.isEmpty else { return }
        
        isUploading = true
        beginBackgroundTask()
        
        let idToken = await authVM.fetchIdToken()
        guard !idToken.isEmpty else {
            print("⚠️ Cannot auto-upload: User is not fully authenticated.")
            isUploading = false
            endBackgroundTask()
            return
        }
        
        print("🚀 Processing \(pendingMedia.count) item(s) from the Outbox...")
        
        for media in pendingMedia {
            if isPaused { break }
            
            // Double check connection right before uploading the heavy video
            if !NetworkMonitor.shared.isConnected {
                print("⚠️ Connection lost. Pausing auto-upload queue.")
                isPaused = true
                break
            }
            
            if !authVM.hasActiveSubscription {
                print("🛑 NO ACTIVE SUBSCRIPTION: Stopping auto-upload queue.")
                isPaused = true
                isUploading = false
                endBackgroundTask()
                sendLimitNotification(
                    title: "Subscription Required",
                    body: "You need an active subscription or Free Trial to upload landmarks."
                )
                return
            }
            
            if authVM.tokenBalance <= 0 {
                print("🛑 OUT OF TOKENS: Stopping auto-upload queue.")
                isPaused = true
                isUploading = false
                endBackgroundTask()
                sendLimitNotification(
                    title: "Out of Tokens",
                    body: "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                )
                return
            }
            
            currentlyUploadingId = media.id
            currentUploadProgress = 0.0
            
            do {
                let fileURL = OfflineMediaManager.shared.getFileURL(for: media)
                let isVideo = media.isVideo
                
                let landmarkId = media.landmarkId ?? "landmark_\(UUID().uuidString.prefix(8))"
                let label = media.savedLabel ?? media.title
                
                print("📤 Auto-uploading: \(label)")
                
                let positiveResult = try await uploadService.upload(
                    userEmail: authVM.userEmail,
                    idToken: idToken,
                    label: label,
                    landmarkId: landmarkId,
                    landmarkLabel: label,
                    shortDescription: media.savedDescription,
                    userDescription: media.savedUserDescription,
                    latitude: media.latitude,
                    longitude: media.longitude,
                    horizontalAccuracy: 10.0,
                    videoURLs: isVideo ? [fileURL] : [],
                    image: isVideo ? nil : UIImage(contentsOfFile: fileURL.path)
                )
                
                let finalLandmarkId = positiveResult.landmarkId ?? landmarkId
                
                if let negativeURL = OfflineMediaManager.shared.getNegativeVideoURL(for: media),
                   FileManager.default.fileExists(atPath: negativeURL.path) {
                    
                    print("📤 Auto-uploading negative reference video...")
                    let negativeVideo = CapturedNegativeVideo(fileURL: negativeURL)
                    
                    _ = try await hardNegativeUploadService.upload(
                        landmarkId: finalLandmarkId,
                        idToken: idToken,
                        video: negativeVideo
                    )
                }
                
                print("✅ Auto-upload complete for: \(label)")
                
                authVM.tokenBalance -= 1
                authVM.activeLandmarksCount += 1
                
                OfflineMediaManager.shared.deleteArchive(media: media)
                sendSuccessNotification(landmarkName: label)
                
            } catch {
                print("❌ Background upload failed for \(media.id): \(error.localizedDescription)")
                isUploading = false
                currentlyUploadingId = nil
                endBackgroundTask()
                return
            }
        }
        
        isUploading = false
        currentlyUploadingId = nil
        endBackgroundTask()
    }
    
    private func sendSuccessNotification(landmarkName: String) {
        let content = UNMutableNotificationContent()
        content.title = "LookSee Upload Complete! 🎉"
        content.body = "Your offline media for '\(landmarkName)' has been successfully synced. (1 Token consumed)."
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
    
    private func sendLimitNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
