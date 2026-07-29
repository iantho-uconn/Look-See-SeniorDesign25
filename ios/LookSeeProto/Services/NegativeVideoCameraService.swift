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
    
    // Smart Camera Selector for virtual multi-lens arrays
    private func getBestCamera() -> AVCaptureDevice? {
        if let device = AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back) {
            return device // Pro models (0.5x, 1x, Telephoto)
        }
        if let device = AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back) {
            return device // Standard models (0.5x, 1x)
        }
        if let device = AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back) {
            return device // Older Plus models (1x, Telephoto)
        }
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    private func setupSession() {
        guard !session.isRunning else { return }
        session.beginConfiguration()
        
        // 🚀 THE FIX: Force maximum explicit resolution instead of ambiguous ".high"
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
            
            // 🚀 THE FIX: Enable cinematic hardware stabilization for sharper ML frames
            if let connection = videoOutput.connection(with: .video) {
                if connection.isVideoStabilizationSupported {
                    connection.preferredVideoStabilizationMode = .cinematicExtended
                }
            }
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
