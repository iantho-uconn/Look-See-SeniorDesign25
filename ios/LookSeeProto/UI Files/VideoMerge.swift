//
//  VideoMerge.swift
//  LookSeeProto
//
//  Concatenates multiple recorded clips into a single video file before
//  upload, so the backend only ever receives one file per submission.
//

import Foundation
import AVFoundation

enum VideoMergeError: LocalizedError {
    case noClips
    case trackCreationFailed
    case exportSessionCreationFailed
    case exportFailed(String)
    case tooShort(actual: Double, minimum: Double)

    var errorDescription: String? {
        switch self {
        case .noClips:
            return "No video clips were provided to merge."
        case .trackCreationFailed:
            return "Could not prepare the video clips for merging."
        case .exportSessionCreationFailed:
            return "Could not start merging the video clips."
        case .exportFailed(let reason):
            return "Merging the video clips failed: \(reason)"
        case .tooShort(let actual, let minimum):
            return "The combined video is only \(String(format: "%.1f", actual))s long. It must be at least \(String(format: "%.0f", minimum))s."
        }
    }
}

enum VideoMerger {

    static func mergeAndValidate(
        clipURLs urls: [URL],
        minimumDuration: Double = 1.0
    ) async throws -> URL {

        guard !urls.isEmpty else {
            throw VideoMergeError.noClips
        }

        if urls.count == 1 {
            let asset = AVURLAsset(url: urls[0])
            let duration = try await asset.load(.duration)
            let seconds = CMTimeGetSeconds(duration)
            
            guard seconds.isFinite, seconds >= minimumDuration else {
                throw VideoMergeError.tooShort(actual: seconds, minimum: minimumDuration)
            }
            return urls[0]
        }

        // 🚀 EXACT REPLICA OF YOUR ORIGINAL WORKING LOGIC
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw VideoMergeError.trackCreationFailed
        }
        
        var currentTime = CMTime.zero
        var renderSize = CGSize(width: 1080, height: 1920)
        
        for url in urls {
            let asset = AVURLAsset(url: url)
            do {
                guard let assetVideoTrack = try await asset.loadTracks(withMediaType: .video).first else { continue }
                let duration = try await asset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                
                try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
                
                let transform = try await assetVideoTrack.load(.preferredTransform)
                videoTrack.preferredTransform = transform
                
                let naturalSize = try await assetVideoTrack.load(.naturalSize)
                if transform.a == 0 && transform.d == 0 && (transform.b == 1.0 || transform.b == -1.0) {
                    renderSize = CGSize(width: naturalSize.height, height: naturalSize.width)
                } else {
                    renderSize = naturalSize
                }
                
                currentTime = CMTimeAdd(currentTime, duration)
            } catch {
                print("Could not merge clip: \(error)")
            }
        }
        
        let totalSeconds = CMTimeGetSeconds(currentTime)
        guard totalSeconds.isFinite, totalSeconds >= minimumDuration else {
            throw VideoMergeError.tooShort(actual: totalSeconds, minimum: minimumDuration)
        }

        composition.naturalSize = renderSize
        
        let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_merged.mov")
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }
        
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1920x1080) else {
            throw VideoMergeError.exportSessionCreationFailed
        }
        
        exporter.outputURL = outputURL
        exporter.outputFileType = .mov
        exporter.shouldOptimizeForNetworkUse = true
        
        await exporter.export()
        
        if exporter.status == .completed {
            return outputURL
        } else {
            let reason = exporter.error?.localizedDescription ?? "Unknown error"
            throw VideoMergeError.exportFailed(reason)
        }
    }
}
