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

class OfflineMediaManager: ObservableObject {
    static let shared = OfflineMediaManager()
    
    @Published var archivedItems: [ArchivedMedia] = []
    
    // In-memory cache to keep negative photos alive during your app session
    @Published var negativeCache: [UUID: [CapturedNegativePhoto]] = [:]
    
    private let ledgerKey = "LookSeeArchiveLedger"
    
    init() {
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
    
    // MARK: - Archive Video (Queue)
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
    ) -> ArchivedMedia? {
        
        let uniqueID = UUID().uuidString
        let fileName = uniqueID + ".mov"
        let thumbName = uniqueID + "_thumb.jpg"
        let permanentURL = getDocumentsDirectory().appendingPathComponent(fileName)
        
        var permanentNegName: String? = nil
        
        do {
            // 1. Copy Positive Video
            try FileManager.default.copyItem(at: tempURL, to: permanentURL)
            
            // 2. Generate Thumbnail
            if let thumbnail = generateVideoThumbnail(for: permanentURL) {
                _ = saveImageToDisk(image: thumbnail, fileName: thumbName)
            }
            
            // 3. Copy Negative Video (if one was recorded)
            if let negURL = negativeVideoURL {
                let negName = uniqueID + "_negative.mov"
                let permanentNegURL = getDocumentsDirectory().appendingPathComponent(negName)
                try FileManager.default.copyItem(at: negURL, to: permanentNegURL)
                permanentNegName = negName
            }
            
            // 4. Create Queue Record
            let newEntry = ArchivedMedia(
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
            
            DispatchQueue.main.async {
                self.archivedItems.append(newEntry)
                self.saveArchive()
            }
            return newEntry
            
        } catch {
            print("❌ Failed to archive video: \(error)")
            return nil
        }
    }
    
    // MARK: - Archive Photo (Queue)
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
    ) -> ArchivedMedia? {
        
        let uniqueID = UUID().uuidString
        let fileName = uniqueID + ".jpg"
        let thumbName = uniqueID + "_thumb.jpg"
        
        var permanentNegName: String? = nil
        
        guard saveImageToDisk(image: image, fileName: fileName) else { return nil }
        _ = saveImageToDisk(image: image, fileName: thumbName, compression: 0.3)
        
        do {
            // Copy Negative Video (if one was recorded)
            if let negURL = negativeVideoURL {
                let negName = uniqueID + "_negative.mov"
                let permanentNegURL = getDocumentsDirectory().appendingPathComponent(negName)
                try FileManager.default.copyItem(at: negURL, to: permanentNegURL)
                permanentNegName = negName
            }
        } catch {
            print("❌ Failed to archive negative video with photo: \(error)")
        }
        
        let newEntry = ArchivedMedia(
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
        
        DispatchQueue.main.async {
            self.archivedItems.append(newEntry)
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
    
    // MARK: - Helpers
    private func generateVideoThumbnail(for url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        do {
            let cgImage = try generator.copyCGImage(at: .zero, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            return nil
        }
    }
    
    private func saveImageToDisk(image: UIImage, fileName: String, compression: CGFloat = 0.8) -> Bool {
        guard let data = image.jpegData(compressionQuality: compression) else { return false }
        let url = getDocumentsDirectory().appendingPathComponent(fileName)
        do {
            try data.write(to: url)
            return true
        } catch {
            return false
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
    
    func deleteArchive(media: ArchivedMedia) {
        // Delete positive media
        try? FileManager.default.removeItem(at: getFileURL(for: media))
        try? FileManager.default.removeItem(at: getThumbnailURL(for: media))
        
        // Delete negative video
        if let negURL = getNegativeVideoURL(for: media) {
            try? FileManager.default.removeItem(at: negURL)
        }
        
        negativeCache.removeValue(forKey: media.id)
        
        DispatchQueue.main.async {
            self.archivedItems.removeAll { $0.id == media.id }
            self.saveArchive()
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
            archivedItems = decoded
        }
    }
}
