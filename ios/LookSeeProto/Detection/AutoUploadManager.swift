//
//  AutoUploadManager.swift
//  LookSeeProto
//

import Foundation
import SwiftUI
import Combine
import UserNotifications

@MainActor
class AutoUploadManager: ObservableObject {
    static let shared = AutoUploadManager()
    
    private var cancellables = Set<AnyCancellable>()
    private var isProcessingQueue = false
    
    private let uploadService = UploadService()
    private let hardNegativeUploadService = HardNegativeUploadService()
    
    private init() {
        requestNotificationPermission()
        
        // Listen for network changes. When we reconnect, check the Outbox.
        NetworkMonitor.shared.$isConnected
            .dropFirst() // Ignore the initial boot state
            .sink { [weak self] isConnected in
                if isConnected {
                    self?.processOfflineQueue()
                }
            }
            .store(in: &cancellables)
    }
    
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted {
                print("✅ Notifications authorized for Auto-Uploads")
            }
        }
    }
    
    func processOfflineQueue() {
        guard !isProcessingQueue else { return }
        
        // Grab everything currently sitting in the Outbox
        let pendingMedia = OfflineMediaManager.shared.archivedItems
        
        guard !pendingMedia.isEmpty else { return }
        isProcessingQueue = true
        print("🚀 Network restored! Processing \(pendingMedia.count) item(s) from the Outbox...")
        
        Task {
            // Spin up a temporary AuthViewModel to grab fresh Cognito tokens
            let authVM = AuthViewModel()
            await authVM.fetchUserEmail()
            let idToken = await authVM.fetchIdToken()
            
            guard !idToken.isEmpty else {
                print("⚠️ Cannot auto-upload: User is not fully authenticated.")
                isProcessingQueue = false
                return
            }
            
            for media in pendingMedia {
                do {
                    let fileURL = OfflineMediaManager.shared.getFileURL(for: media)
                    let isVideo = media.isVideo
                    
                    // Fallbacks just in case the data is missing
                    let landmarkId = media.landmarkId ?? "landmark_\(UUID().uuidString.prefix(8))"
                    let label = media.savedLabel ?? media.title
                    
                    print("📤 Auto-uploading: \(label)")
                    
                    // 1. Upload Positive Media (Video or Photo)
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
                        horizontalAccuracy: 10.0, // Standard fallback for offline uploads
                        videoURLs: isVideo ? [fileURL] : [],
                        image: isVideo ? nil : UIImage(contentsOfFile: fileURL.path)
                    )
                    
                    let finalLandmarkId = positiveResult.landmarkId ?? landmarkId
                    
                    // 2. Upload Negative Reference Video (if one was captured)
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
                    
                    // 3. Success! Delete from Outbox and notify the user
                    print("✅ Auto-upload complete for: \(label)")
                    OfflineMediaManager.shared.deleteArchive(media: media)
                    sendSuccessNotification(landmarkName: label)
                    
                } catch {
                    print("❌ Background upload failed for \(media.id): \(error.localizedDescription)")
                    // If it fails (e.g., connection drops mid-upload), we DO NOT delete it.
                    // It stays in the queue for the next network reconnect.
                }
            }
            
            isProcessingQueue = false
        }
    }
    
    private func sendSuccessNotification(landmarkName: String) {
        let content = UNMutableNotificationContent()
        content.title = "LookSee Upload Complete! 🎉"
        content.body = "Your offline media for '\(landmarkName)' has been successfully uploaded."
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
