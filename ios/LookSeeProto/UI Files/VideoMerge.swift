//
//  VideoMerger.swift
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

    /// Concatenates `clipURLs` in order into a single .mov file in a temp
    /// directory, then verifies the combined duration meets `minimumDuration`.
    static func mergeAndValidate(
        clipURLs: [URL],
        minimumDuration: Double = 15
    ) async throws -> URL {

        guard !clipURLs.isEmpty else {
            throw VideoMergeError.noClips
        }

        // Single clip: skip composition entirely, just validate duration.
        if clipURLs.count == 1 {
            let asset = AVURLAsset(url: clipURLs[0])
            let duration = try await asset.load(.duration)
            let seconds = CMTimeGetSeconds(duration)

            guard seconds.isFinite, seconds >= minimumDuration else {
                throw VideoMergeError.tooShort(actual: seconds, minimum: minimumDuration)
            }
            return clipURLs[0]
        }

        let composition = AVMutableComposition()

        guard
            let videoTrack = composition.addMutableTrack(
                withMediaType: .video,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ),
            let audioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            )
        else {
            throw VideoMergeError.trackCreationFailed
        }

        var cursor = CMTime.zero
        var referenceTransform: CGAffineTransform?

        for clipURL in clipURLs {
            let asset = AVURLAsset(url: clipURL)
            let duration = try await asset.load(.duration)
            let range = CMTimeRange(start: .zero, duration: duration)

            if let sourceVideoTrack = try await asset.loadTracks(withMediaType: .video).first {
                try videoTrack.insertTimeRange(range, of: sourceVideoTrack, at: cursor)

                if referenceTransform == nil {
                    referenceTransform = try await sourceVideoTrack.load(.preferredTransform)
                }
            }

            if let sourceAudioTrack = try await asset.loadTracks(withMediaType: .audio).first {
                try audioTrack.insertTimeRange(range, of: sourceAudioTrack, at: cursor)
            }

            cursor = CMTimeAdd(cursor, duration)
        }

        if let referenceTransform {
            videoTrack.preferredTransform = referenceTransform
        }

        let totalSeconds = CMTimeGetSeconds(cursor)
        guard totalSeconds.isFinite, totalSeconds >= minimumDuration else {
            throw VideoMergeError.tooShort(actual: totalSeconds, minimum: minimumDuration)
        }

        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("mov")

        guard let exportSession = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw VideoMergeError.exportSessionCreationFailed
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mov
        exportSession.shouldOptimizeForNetworkUse = true

        await exportSession.export()

        guard exportSession.status == .completed else {
            let reason = exportSession.error?.localizedDescription ?? "Unknown export error."
            throw VideoMergeError.exportFailed(reason)
        }

        return outputURL
    }
}
