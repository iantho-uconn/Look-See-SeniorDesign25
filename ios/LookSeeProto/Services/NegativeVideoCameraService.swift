//
//  NegativeVideoCameraService.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/29/26.
//

import AVFoundation
import UIKit
import Combine

@MainActor
final class NegativeVideoCameraService: NSObject, ObservableObject {
    @Published var isRecording = false
    @Published var errorMessage: String?
    @Published var authorizationStatus: AVAuthorizationStatus = .notDetermined
    @Published var isInterrupted = false

    let session = AVCaptureSession()
    private let videoOutput = AVCaptureMovieFileOutput()
    private var activeFileURL: URL?
    private var isConfigured = false   // NEW: tracks whether inputs/outputs are already attached

    private var segmentURLs: [URL] = []
    private var isIntentionalStop = false
    private var wasRecordingBeforeInterruption = false

    var onVideoRecorded: ((URL) -> Void)?

    // ... init/deinit unchanged ...

    func start() {
        checkPermissionsAndStart()
    }

    func stop() {
        session.stopRunning()
    }

    private func checkPermissionsAndStart() {
        let currentStatus = AVCaptureDevice.authorizationStatus(for: .video)
        self.authorizationStatus = currentStatus
        
        switch currentStatus {
        case .authorized:
            setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    self.authorizationStatus = granted ? .authorized : .denied
                    if granted { self.setupSession() }
                }
            }
        default:
            self.authorizationStatus = .denied
            errorMessage = "Camera access is denied. Please enable it in Settings."
        }
    }

    

    private func resumeRunningIfNeeded() {
        guard !session.isRunning else { return }
        errorMessage = nil // clear any stale error from before
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    // ... rest unchanged (startRecording, beginNewSegment, stopRecording,
    //     interruption handlers, segment merging, delegate extension) ...

    private func getBestCamera() -> AVCaptureDevice? {
        if let device = AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back) {
            return device
        }
        if let device = AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back) {
            return device
        }
        if let device = AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back) {
            return device
        }
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    private func setupSession() {
        // Already wired up (inputs/outputs attached) -- just make sure it's running.
        if isConfigured {
            resumeRunningIfNeeded()
            return
        }
        guard !session.isRunning else { return }

        session.beginConfiguration()
        
        if session.canSetSessionPreset(.hd4K3840x2160) {
            session.sessionPreset = .hd4K3840x2160
        } else if session.canSetSessionPreset(.hd1920x1080) {
            session.sessionPreset = .hd1920x1080
        } else {
            session.sessionPreset = .high
        }

        guard let videoDevice = getBestCamera(),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
              session.canAddInput(videoInput) else {
            errorMessage = "Unable to access the back camera."
            session.commitConfiguration()
            return
        }
        
        session.addInput(videoInput)

        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
            if let connection = videoOutput.connection(with: .video) {
                if connection.isVideoStabilizationSupported {
                    connection.preferredVideoStabilizationMode = .cinematicExtended
                }
            }
        }

        session.commitConfiguration()
        isConfigured = true
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.session.startRunning()
        }
    }

    func startRecording() {
        guard !isRecording else { return }
        segmentURLs = [] // fresh logical "recording" starts here
        beginNewSegment()
    }

    private func beginNewSegment() {
        let tempDir = FileManager.default.temporaryDirectory
        let filename = UUID().uuidString + ".mov"
        let fileURL = tempDir.appendingPathComponent(filename)
        activeFileURL = fileURL
        
        if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        
        videoOutput.startRecording(to: fileURL, recordingDelegate: self)
        isRecording = true
    }

    func stopRecording() {
        guard isRecording else { return }
        isIntentionalStop = true
        videoOutput.stopRecording()
        // isRecording flips to false in the delegate callback, once the file is actually closed.
    }

    // MARK: - Interruption handling (backgrounding, phone calls, Control Center camera, etc.)

    @objc nonisolated private func handleSessionWasInterrupted(_ notification: Notification) {
        Task { @MainActor in self.handleInterruptionBegan() }
    }

    @objc nonisolated private func handleSessionInterruptionEnded(_ notification: Notification) {
        Task { @MainActor in self.handleInterruptionEnded() }
    }

    private func handleInterruptionBegan() {
        wasRecordingBeforeInterruption = isRecording
        isInterrupted = true
    }

    private func handleInterruptionEnded() {
        isInterrupted = false

        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
            }
        }

        guard wasRecordingBeforeInterruption else { return }
        wasRecordingBeforeInterruption = false

        // Give the session a beat to fully come back before starting the next segment.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.resumeRecordingAfterInterruption()
        }
    }

    private func resumeRecordingAfterInterruption() {
        guard !isRecording else { return }
        beginNewSegment()
    }

    // MARK: - Segment merging

    private func finishAndDeliverSegments() async {
        let urls = segmentURLs
        segmentURLs = []
        guard let first = urls.first else { return }

        if urls.count == 1 {
            onVideoRecorded?(first)
            return
        }
        if let merged = await mergeSegments(urls) {
            for url in urls { try? FileManager.default.removeItem(at: url) }
            onVideoRecorded?(merged)
        } else {
            // Merge failed for some reason -- don't strand the user, hand back the first segment.
            onVideoRecorded?(first)
        }
    }

    private func mergeSegments(_ urls: [URL]) async -> URL? {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        var currentTime = CMTime.zero
        for url in urls {
            let asset = AVURLAsset(url: url)
            do {
                guard let assetVideoTrack = try await asset.loadTracks(withMediaType: .video).first else { continue }
                let duration = try await asset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
                videoTrack.preferredTransform = try await assetVideoTrack.load(.preferredTransform)
                currentTime = CMTimeAdd(currentTime, duration)
            } catch { return nil }
        }
        let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_resumed_stitched.mov")
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else { return nil }
        exporter.outputURL = outputURL
        exporter.outputFileType = .mov
        await exporter.export()
        return exporter.status == .completed ? outputURL : nil
    }
}

extension NegativeVideoCameraService: AVCaptureFileOutputRecordingDelegate {
    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            self.isRecording = false
            let wasIntentional = self.isIntentionalStop
            self.isIntentionalStop = false

            if let error = error as NSError? {
                let finishedSuccessfully = (error.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool) ?? false

                if !finishedSuccessfully {
                    // A genuine failure (disk issue, hardware fault, etc.) -- still surface it.
                    self.errorMessage = "Failed to record video: \(error.localizedDescription)"
                    return
                }

                // File is valid but got cut short -- almost always the app backgrounding.
                self.segmentURLs.append(outputFileURL)

                if wasIntentional {
                    // User tapped stop right as the interruption landed -- finish normally.
                    await self.finishAndDeliverSegments()
                }
                // Otherwise: leave it pending. handleInterruptionEnded() resumes recording
                // automatically, and this segment gets stitched into the final clip later.
                return
            }

            // Clean, uninterrupted stop.
            self.segmentURLs.append(outputFileURL)
            await self.finishAndDeliverSegments()
        }
    }
}
