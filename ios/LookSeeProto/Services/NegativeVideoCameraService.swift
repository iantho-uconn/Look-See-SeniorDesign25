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
    
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureMovieFileOutput()
    private var activeFileURL: URL?
    
    var onVideoRecorded: ((URL) -> Void)?

    func start() {
        checkPermissionsAndStart()
    }

    func stop() {
        session.stopRunning()
    }

    private func checkPermissionsAndStart() {
        // 1. Grab the current status
        let currentStatus = AVCaptureDevice.authorizationStatus(for: .video)
        
        // 2. Immediately update the UI state to match the hardware
        self.authorizationStatus = currentStatus
        
        switch currentStatus {
        case .authorized:
            setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    // Update state again based on the user's choice
                    self.authorizationStatus = granted ? .authorized : .denied
                    if granted { self.setupSession() }
                }
            }
        default:
            self.authorizationStatus = .denied
            errorMessage = "Camera access is denied. Please enable it in Settings."
        }
    }

    private func setupSession() {
        guard !session.isRunning else { return }
        session.beginConfiguration()
        session.sessionPreset = .high

        guard let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
              session.canAddInput(videoInput) else {
            errorMessage = "Unable to access the back camera."
            session.commitConfiguration()
            return
        }
        
        session.addInput(videoInput)

        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        session.commitConfiguration()
        
        DispatchQueue.global(qos: .userInitiated).async {
            self.session.startRunning()
        }
    }

    func startRecording() {
        guard !isRecording else { return }
        
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
        videoOutput.stopRecording()
        isRecording = false
    }
}

extension NegativeVideoCameraService: AVCaptureFileOutputRecordingDelegate {
    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            if let error = error {
                self.errorMessage = "Failed to record video: \(error.localizedDescription)"
                return
            }
            self.onVideoRecorded?(outputFileURL)
        }
    }
}
