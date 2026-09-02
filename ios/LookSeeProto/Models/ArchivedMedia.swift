//
//  ArchivedMedia.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/18/26.
//

import Foundation

struct ArchivedMedia: Identifiable, Codable {
    var id = UUID()
    var title: String
    var fileName: String
    var thumbnailFileName: String
    var isVideo: Bool
    var latitude: Double
    var longitude: Double
    var dateSaved: Date
    var isFavorite: Bool?
    
    // --- NEW QUEUE FIELDS ---
    var landmarkId: String?
    var savedLabel: String?
    var savedDescription: String?
    var savedUserDescription: String?
    var negativeVideoFileName: String?
    
    // Differentiates between Tier-1 (Record) and Tier-2 (Uploads)
    var isTier2: Bool?
}
