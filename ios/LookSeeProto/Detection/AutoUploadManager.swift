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
    
    // Published properties so the UI updates live
    @Published var isUploading = false
    @Published var currentlyUploadingId: UUID? = nil
    @Published var currentUploadProgress: Double = 0.0
    
    private var isPaused = false
    
    private let uploadService = UploadService()
    private let hardNegativeUploadService = HardNegativeUploadService()
    
    private init() {
        requestNotificationPermission()
        
        // Tie the upload service's progress to our manager's progress
        uploadService.$progress
            .receive(on: RunLoop.main)
            .sink { [weak self] p in
                self?.currentUploadProgress = p
            }
            .store(in: &cancellables)
        
        // Listen for network changes to auto-start if connected
        NetworkMonitor.shared.$isConnected
            .dropFirst()
            .sink { [weak self] isConnected in
                if isConnected {
                    Task { await self?.autoStartIfPossible() }
                }
            }
            .store(in: &cancellables)
    }
    
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted { print("✅ Notifications authorized for Auto-Uploads") }
        }
    }
    
    // Pause the queue
    func stopProcessing() {
        isPaused = true
        isUploading = false
        // Note: The *current* active upload file will finish to prevent corruption,
        // but it will pause before moving to the next item in the array.
    }
    
    // Manually start the queue
    func startProcessing(authViewModel: AuthViewModel) async {
        isPaused = false
        await processOfflineQueue(authVM: authViewModel)
    }
    
    // Auto-start via Network Monitor
    private func autoStartIfPossible() async {
        guard !isPaused else { return }
        let authVM = AuthViewModel()
        await authVM.fetchUserEmail()
        await processOfflineQueue(authVM: authVM)
    }
    
    // Core Engine Loop
    private func processOfflineQueue(authVM: AuthViewModel) async {
        guard !isUploading else { return }
        guard !isPaused else { return }
        
        let pendingMedia = OfflineMediaManager.shared.archivedItems
        guard !pendingMedia.isEmpty else { return }
        
        isUploading = true
        
        let idToken = await authVM.fetchIdToken()
        guard !idToken.isEmpty else {
            print("⚠️ Cannot auto-upload: User is not fully authenticated.")
            isUploading = false
            return
        }
        
        print("🚀 Processing \(pendingMedia.count) item(s) from the Outbox...")
        
        for media in pendingMedia {
            if isPaused { break }
            
            currentlyUploadingId = media.id
            currentUploadProgress = 0.0
            
            do {
                let fileURL = OfflineMediaManager.shared.getFileURL(for: media)
                let isVideo = media.isVideo
                
                // Fallbacks
                let landmarkId = media.landmarkId ?? "landmark_\(UUID().uuidString.prefix(8))"
                let label = media.savedLabel ?? media.title
                
                print("📤 Auto-uploading: \(label)")
                
                // 1. Upload Positive Media
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
                
                // 2. Upload Negative Reference Video
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
                
                // 3. Success! Delete from Outbox
                print("✅ Auto-upload complete for: \(label)")
                OfflineMediaManager.shared.deleteArchive(media: media)
                sendSuccessNotification(landmarkName: label)
                
            } catch {
                print("❌ Background upload failed for \(media.id): \(error.localizedDescription)")
                // Stop the entire queue on a failure (like losing connection)
                isUploading = false
                currentlyUploadingId = nil
                return
            }
        }
        
        isUploading = false
        currentlyUploadingId = nil
    }
    
    private func sendSuccessNotification(landmarkName: String) {
        let content = UNMutableNotificationContent()
        content.title = "LookSee Upload Complete! 🎉"
        content.body = "Your offline media for '\(landmarkName)' has been successfully synced."
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
