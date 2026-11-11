//
//  OfflineMediaManager.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/18/26.
//  Updated for Outbox/Queue functionality
//

import Foundation
import CoreLocation
import SwiftUI
import Combine
import AVFoundation

@MainActor
class OfflineMediaManager: ObservableObject {
    static let shared = OfflineMediaManager()
    
    @Published var archivedItems: [ArchivedMedia] = []
    
    // In-memory cache to keep negative photos alive during your app session
    @Published var negativeCache: [UUID: [CapturedNegativePhoto]] = [:]
    
    private let ledgerKey = "LookSeeArchiveLedger"
    
    private init() {
        loadArchive()
    }
    
    func getDocumentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    func getFileURL(for media: ArchivedMedia) -> URL {
        getDocumentsDirectory().appendingPathComponent(media.fileName)
    }
    
    func getThumbnailURL(for media: ArchivedMedia) -> URL {
        getDocumentsDirectory().appendingPathComponent(media.thumbnailFileName)
    }
    
    func getNegativeVideoURL(for media: ArchivedMedia) -> URL? {
        guard let negName = media.negativeVideoFileName else { return nil }
        return getDocumentsDirectory().appendingPathComponent(negName)
    }
    
    // Bumps an item to the absolute front of the queue by spoofing an old date
    func prioritizeAndRetry(media: ArchivedMedia) {
        if let index = archivedItems.firstIndex(where: { $0.id == media.id }) {
            guard archivedItems[index].deletionBlocked != true else { return }
            archivedItems[index].lastUploadError = nil
            archivedItems[index].dateSaved = Date(timeIntervalSince1970: 0)
            archivedItems.sort { $0.dateSaved < $1.dateSaved }
            saveArchive()

            if let targetId = archivedItems[index].landmarkId ?? archivedItems[index].queueLandmarkId {
                VariableContainer.shared.markLandmarkProcessingNewData(landmarkId: targetId)
            }
        }
    }
    
    func recordUploadFailure(id: UUID, message: String, deletionBlocked: Bool) {
        guard let index = archivedItems.firstIndex(where: { $0.id == id }) else { return }
        archivedItems[index].lastUploadError = message
        archivedItems[index].deletionBlocked = deletionBlocked
        saveArchive()

        if let targetId = archivedItems[index].landmarkId ?? archivedItems[index].queueLandmarkId {
            VariableContainer.shared.clearLandmarkProcessingNewData(landmarkId: targetId)
        }
    }

    func clearRetryableFailures() {
        for index in archivedItems.indices where archivedItems[index].deletionBlocked != true {
            archivedItems[index].lastUploadError = nil
            if let targetId = archivedItems[index].landmarkId ?? archivedItems[index].queueLandmarkId {
                VariableContainer.shared.markLandmarkProcessingNewData(landmarkId: targetId)
            }
        }
        saveArchive()
    }

    func prepareUploadID(for media: ArchivedMedia) -> String {
        let landmarkID = media.landmarkId ?? media.queueLandmarkId
            ?? "landmark_\(UUID().uuidString.prefix(8))"
        if let index = archivedItems.firstIndex(where: { $0.id == media.id }) {
            archivedItems[index].queueLandmarkId = landmarkID
            saveArchive()
        }
        return landmarkID
    }

    func recordPositiveCompletion(id: UUID, landmarkID: String) {
        guard let index = archivedItems.firstIndex(where: { $0.id == id }) else { return }
        archivedItems[index].queueLandmarkId = landmarkID
        archivedItems[index].positiveUploadCompleted = true
        saveArchive()
        VariableContainer.shared.markLandmarkUploadCompleted(landmarkId: landmarkID)
    }

    // MARK: - Archive Video (Queue) - Background Optimized
    func archiveVideo(
        tempURL: URL,
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoURL: URL?,
        isTier2: Bool = false
    ) async -> ArchivedMedia? {
        // Instantly mark existing landmark as Processing New Data so UI updates without delay
        if let landmarkId, !landmarkId.isEmpty {
            VariableContainer.shared.markLandmarkProcessingNewData(landmarkId: landmarkId)
        }
        
        let uniqueID = UUID().uuidString
        let fileName = uniqueID + ".mov"
        let thumbName = uniqueID + "_thumb.jpg"
        
        let docsDir = getDocumentsDirectory()
        let permanentURL = docsDir.appendingPathComponent(fileName)
        
        let newEntry = await Task.detached(priority: .userInitiated) { () -> ArchivedMedia? in
            var permanentNegName: String? = nil
            
            do {
                try FileManager.default.copyItem(at: tempURL, to: permanentURL)
                
                let asset = AVAsset(url: permanentURL)
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                
                if let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) {
                    let thumbnail = UIImage(cgImage: cgImage)
                    if let data = thumbnail.jpegData(compressionQuality: 0.8) {
                        let thumbURL = docsDir.appendingPathComponent(thumbName)
                        try? data.write(to: thumbURL)
                    }
                }
                
                if let negURL = negativeVideoURL {
                    let negName = uniqueID + "_negative.mov"
                    let permanentNegURL = docsDir.appendingPathComponent(negName)
                    try FileManager.default.copyItem(at: negURL, to: permanentNegURL)
                    permanentNegName = negName
                }
                
                return ArchivedMedia(
                    title: label,
                    fileName: fileName,
                    thumbnailFileName: thumbName,
                    isVideo: true,
                    latitude: lat,
                    longitude: lon,
                    dateSaved: Date(),
                    isFavorite: false,
                    landmarkId: landmarkId,
                    savedLabel: label,
                    savedDescription: shortDesc,
                    savedUserDescription: userDesc,
                    negativeVideoFileName: permanentNegName,
                    isTier2: isTier2
                )
                
            } catch {
                print("❌ Failed to archive video: \(error)")
                return nil
            }
        }.value
        
        if let entry = newEntry {
            self.archivedItems.append(entry)
            self.archivedItems.sort { $0.dateSaved < $1.dateSaved }
            self.saveArchive()
        }
        
        return newEntry
    }
    
    // MARK: - Archive Photo (Queue) - Background Optimized
    func archivePhoto(
        image: UIImage,
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoURL: URL?,
        isTier2: Bool = false
    ) async -> ArchivedMedia? {
        if let landmarkId, !landmarkId.isEmpty {
            VariableContainer.shared.markLandmarkProcessingNewData(landmarkId: landmarkId)
        }
        
        let uniqueID = UUID().uuidString
        let fileName = uniqueID + ".jpg"
        let thumbName = uniqueID + "_thumb.jpg"
        let docsDir = getDocumentsDirectory()
        
        let newEntry = await Task.detached(priority: .userInitiated) { () -> ArchivedMedia? in
            var permanentNegName: String? = nil
            
            guard let data = image.jpegData(compressionQuality: 0.8),
                  let thumbData = image.jpegData(compressionQuality: 0.3) else {
                return nil
            }
            
            do {
                let url = docsDir.appendingPathComponent(fileName)
                let thumbURL = docsDir.appendingPathComponent(thumbName)
                
                try data.write(to: url)
                try thumbData.write(to: thumbURL)
                
                if let negURL = negativeVideoURL {
                    let negName = uniqueID + "_negative.mov"
                    let permanentNegURL = docsDir.appendingPathComponent(negName)
                    try FileManager.default.copyItem(at: negURL, to: permanentNegURL)
                    permanentNegName = negName
                }
                
                return ArchivedMedia(
                    title: label,
                    fileName: fileName,
                    thumbnailFileName: thumbName,
                    isVideo: false,
                    latitude: lat,
                    longitude: lon,
                    dateSaved: Date(),
                    isFavorite: false,
                    landmarkId: landmarkId,
                    savedLabel: label,
                    savedDescription: shortDesc,
                    savedUserDescription: userDesc,
                    negativeVideoFileName: permanentNegName,
                    isTier2: isTier2
                )
            } catch {
                print("❌ Failed to archive photo: \(error)")
                return nil
            }
        }.value
        
        if let entry = newEntry {
            self.archivedItems.append(entry)
            self.archivedItems.sort { $0.dateSaved < $1.dateSaved }
            self.saveArchive()
        }
        
        return newEntry
    }
    
    // MARK: - Draft Updates
    func updateDraft(media: ArchivedMedia, label: String, shortDesc: String, userDesc: String?) {
        if let index = archivedItems.firstIndex(where: { $0.id == media.id }) {
            archivedItems[index].savedLabel = label
            archivedItems[index].savedDescription = shortDesc
            archivedItems[index].savedUserDescription = userDesc
            archivedItems[index].title = label
            saveArchive()
        }
    }
    
    func renameArchive(media: ArchivedMedia, newTitle: String) {
        if let index = archivedItems.firstIndex(where: { $0.id == media.id }) {
            archivedItems[index].title = newTitle
            saveArchive()
        }
    }
    
    func toggleFavorite(media: ArchivedMedia) {
        if let index = archivedItems.firstIndex(where: { $0.id == media.id }) {
            let current = archivedItems[index].isFavorite ?? false
            archivedItems[index].isFavorite = !current
            saveArchive()
        }
    }
    
    // MARK: - Delete (Background Optimized)
    func deleteArchive(media: ArchivedMedia, isCompletedUpload: Bool = false) {
        let fileURL = getFileURL(for: media)
        let thumbURL = getThumbnailURL(for: media)
        let negURL = getNegativeVideoURL(for: media)
        let targetLandmarkId = media.landmarkId ?? media.queueLandmarkId
        
        Task.detached(priority: .background) {
            try? FileManager.default.removeItem(at: fileURL)
            try? FileManager.default.removeItem(at: thumbURL)
            if let nURL = negURL {
                try? FileManager.default.removeItem(at: nURL)
            }
        }
        
        negativeCache.removeValue(forKey: media.id)
        archivedItems.removeAll { $0.id == media.id }
        saveArchive()

        if !isCompletedUpload, let targetLandmarkId {
            let stillHasOtherUploads = archivedItems.contains {
                ($0.landmarkId == targetLandmarkId || $0.queueLandmarkId == targetLandmarkId) && $0.deletionBlocked != true
            }
            if !stillHasOtherUploads {
                VariableContainer.shared.clearLandmarkProcessingNewData(landmarkId: targetLandmarkId)
            }
        }
    }
    
    private func saveArchive() {
        if let encoded = try? JSONEncoder().encode(archivedItems) {
            UserDefaults.standard.set(encoded, forKey: ledgerKey)
        }
    }
    
    private func loadArchive() {
        if let savedData = UserDefaults.standard.data(forKey: ledgerKey),
           let decoded = try? JSONDecoder().decode([ArchivedMedia].self, from: savedData) {
            archivedItems = decoded.sorted { $0.dateSaved < $1.dateSaved }
        }
    }
}
