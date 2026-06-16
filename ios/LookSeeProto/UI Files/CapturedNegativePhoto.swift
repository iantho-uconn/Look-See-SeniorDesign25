//
//  CapturedNegativePhoto.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/16/26.
//


import Foundation
import UIKit

struct CapturedNegativePhoto: Identifiable {
    let id: UUID
    let fileURL: URL
    let thumbnail: UIImage

    var filename: String {
        fileURL.lastPathComponent
    }

    func deleteLocalFile() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return
        }

        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch {
            print("⚠️ Failed to delete negative photo \(fileURL.lastPathComponent): \(error)")
        }
    }
}
