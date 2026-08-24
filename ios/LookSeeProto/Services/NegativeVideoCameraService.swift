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
    private var isConfigured = false

    private let sessionQueue = DispatchQueue(label: "com.looksee.camera.sessionQueue")

    private var segmentURLs: [URL] = []
    private var isIntentionalStop = false
    private var wasRecordingBeforeInterruption = false

    var onVideoRecorded: ((URL) -> Void)?

    func start() {
        checkPermissionsAndStart()
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self = self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    private func checkPermissionsAndStart() {
        let currentStatus = AVCaptureDevice.authorizationStatus(for: .video)
        self.authorizationStatus = currentStatus
        
        switch currentStatus {
        case .authorized:
            setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.authorizationStatus = granted ? .authorized : .denied
                    if granted { self?.setupSession() }
                }
            }
        default:
            self.authorizationStatus = .denied
            errorMessage = "Camera access is denied. Please enable it in Settings."
        }
    }

    private func resumeRunningIfNeeded() {
        sessionQueue.async { [weak self] in
            guard let self = self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

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
        if isConfigured {
            resumeRunningIfNeeded()
            return
        }

        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            
            self.session.beginConfiguration()
            
            if self.session.canSetSessionPreset(.hd1920x1080) {
                self.session.sessionPreset = .hd1920x1080
            } else {
                self.session.sessionPreset = .high
            }

            guard let videoDevice = self.getBestCamera(),
                  let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
                  self.session.canAddInput(videoInput) else {
                Task { @MainActor in
                    self.errorMessage = "Unable to access the back camera."
                }
                self.session.commitConfiguration()
                return
            }
            
            self.session.addInput(videoInput)

            if self.session.canAddOutput(self.videoOutput) {
                self.session.addOutput(self.videoOutput)
                if let connection = self.videoOutput.connection(with: .video) {
                    if connection.isVideoStabilizationSupported {
                        connection.preferredVideoStabilizationMode = .cinematicExtended
                    }
                }
            }

            self.session.commitConfiguration()
            self.session.startRunning()

            Task { @MainActor in
                self.isConfigured = true
            }
        }
    }

    func startRecording() {
        guard !isRecording else { return }
        segmentURLs = []
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
    }

    // MARK: - Interruption handling

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
        resumeRunningIfNeeded()
        guard wasRecordingBeforeInterruption else { return }
        wasRecordingBeforeInterruption = false

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
        
        // 🚀 THE FIX: Removed the rogue internal merger and routed safely through the fixed VideoMerger!
        if let merged = try? await VideoMerger.mergeAndValidate(clipURLs: urls, minimumDuration: 1.0) {
            for url in urls { try? FileManager.default.removeItem(at: url) }
            onVideoRecorded?(merged)
        } else {
            onVideoRecorded?(first)
        }
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
                    self.errorMessage = "Failed to record video: \(error.localizedDescription)"
                    return
                }

                self.segmentURLs.append(outputFileURL)

                if wasIntentional {
                    await self.finishAndDeliverSegments()
                }
                return
            }

            self.segmentURLs.append(outputFileURL)
            await self.finishAndDeliverSegments()
        }
    }
}
