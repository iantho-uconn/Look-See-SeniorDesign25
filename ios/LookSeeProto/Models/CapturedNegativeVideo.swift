//
//  CapturedNegativeVideo.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/29/26.
//

import Foundation

struct CapturedNegativeVideo {
    let fileURL: URL
    
    var filename: String {
        fileURL.lastPathComponent
    }

    func deleteLocalFile() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch {
            print("⚠️ Failed to delete negative video \(fileURL.lastPathComponent): \(error)")
        }
    }
}
