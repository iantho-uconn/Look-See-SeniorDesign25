//
//  MultiPhotoCameraService.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/16/26.
//

import AVFoundation
import Combine
import UIKit

final class MultiPhotoCameraService: NSObject, ObservableObject {
    @Published private(set) var capturedPhotos: [CapturedNegativePhoto]
    @Published private(set) var authorizationStatus: AVAuthorizationStatus
    @Published private(set) var isConfigured = false
    @Published private(set) var isCapturing = false
    @Published var errorMessage: String?

    let session = AVCaptureSession()

    private let photoOutput = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(
        label: "com.looksee.hard-negative-camera.session"
    )

    private let maximumPhotoCount: Int
    private let originalPhotoIDs: Set<UUID>

    private var hasConfiguredSession = false

    init(
        initialPhotos: [CapturedNegativePhoto] = [],
        maximumPhotoCount: Int = 10
    ) {
        self.capturedPhotos = initialPhotos
        self.maximumPhotoCount = maximumPhotoCount
        self.originalPhotoIDs = Set(initialPhotos.map(\.id))
        self.authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

        super.init()
    }

    var canCaptureAnotherPhoto: Bool {
        isConfigured &&
        !isCapturing &&
        capturedPhotos.count < maximumPhotoCount
    }

    func start() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)

        DispatchQueue.main.async {
            self.authorizationStatus = status
        }

        switch status {
        case .authorized:
            configureAndStartSession()

        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }

                DispatchQueue.main.async {
                    self.authorizationStatus = granted ? .authorized : .denied
                }

                if granted {
                    self.configureAndStartSession()
                } else {
                    self.setError(
                        "Camera access is required to capture negative reference photos."
                    )
                }
            }

        case .denied, .restricted:
            setError(
                "Camera access is disabled. Enable camera access in Settings to capture negative photos."
            )

        @unknown default:
            setError("The app could not determine camera authorization.")
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func capturePhoto() {
        guard canCaptureAnotherPhoto else { return }

        DispatchQueue.main.async {
            self.isCapturing = true
            self.errorMessage = nil
        }

        // A new settings object must be created for every capture.
        let settings = AVCapturePhotoSettings()
        settings.photoQualityPrioritization = .balanced

        photoOutput.capturePhoto(
            with: settings,
            delegate: self
        )
    }

    func removePhoto(_ photo: CapturedNegativePhoto) {
        guard let index = capturedPhotos.firstIndex(where: { $0.id == photo.id }) else {
            return
        }

        capturedPhotos.remove(at: index)
        photo.deleteLocalFile()
    }

    /// Used when the user cancels the camera screen.
    /// Existing photos are retained; only photos taken during this camera
    /// session are discarded.
    func discardNewPhotos() {
        let newlyCapturedPhotos = capturedPhotos.filter {
            !originalPhotoIDs.contains($0.id)
        }

        newlyCapturedPhotos.forEach {
            $0.deleteLocalFile()
        }

        capturedPhotos.removeAll {
            !originalPhotoIDs.contains($0.id)
        }
    }

    private func configureAndStartSession() {
        sessionQueue.async { [weak self] in
            guard let self else { return }

            do {
                if !self.hasConfiguredSession {
                    try self.configureSession()
                    self.hasConfiguredSession = true
                }

                if !self.session.isRunning {
                    self.session.startRunning()
                }

                DispatchQueue.main.async {
                    self.isConfigured = true
                    self.errorMessage = nil
                }
            } catch {
                self.setError(error.localizedDescription)
            }
        }
    }

    private func configureSession() throws {
        session.beginConfiguration()
        defer {
            session.commitConfiguration()
        }

        session.sessionPreset = .photo

        guard let camera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) else {
            throw CameraSetupError.cameraUnavailable
        }

        let input: AVCaptureDeviceInput

        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            throw CameraSetupError.couldNotCreateInput(error)
        }

        guard session.canAddInput(input) else {
            throw CameraSetupError.couldNotAddInput
        }

        session.addInput(input)

        guard session.canAddOutput(photoOutput) else {
            throw CameraSetupError.couldNotAddPhotoOutput
        }

        session.addOutput(photoOutput)
    }

    private func saveCapturedPhoto(data: Data) throws -> CapturedNegativePhoto {
        guard let image = UIImage(data: data) else {
            throw CameraSetupError.couldNotDecodeImage
        }

        guard let jpegData = image.jpegData(compressionQuality: 0.88) else {
            throw CameraSetupError.couldNotEncodeJPEG
        }

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(
                "looksee-hard-negatives",
                isDirectory: true
            )

        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )

        let photoID = UUID()
        let fileURL = directory
            .appendingPathComponent("negative_\(photoID.uuidString).jpg")

        try jpegData.write(
            to: fileURL,
            options: .atomic
        )

        let thumbnail = makeThumbnail(
            from: image,
            size: CGSize(width: 180, height: 180)
        )

        return CapturedNegativePhoto(
            id: photoID,
            fileURL: fileURL,
            thumbnail: thumbnail
        )
    }

    private func makeThumbnail(
        from image: UIImage,
        size: CGSize
    ) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)

        return renderer.image { _ in
            let imageSize = image.size

            guard imageSize.width > 0, imageSize.height > 0 else {
                image.draw(in: CGRect(origin: .zero, size: size))
                return
            }

            let scale = max(
                size.width / imageSize.width,
                size.height / imageSize.height
            )

            let scaledSize = CGSize(
                width: imageSize.width * scale,
                height: imageSize.height * scale
            )

            let origin = CGPoint(
                x: (size.width - scaledSize.width) / 2,
                y: (size.height - scaledSize.height) / 2
            )

            image.draw(
                in: CGRect(
                    origin: origin,
                    size: scaledSize
                )
            )
        }
    }

    private func setError(_ message: String) {
        DispatchQueue.main.async {
            self.errorMessage = message
            self.isConfigured = false
            self.isCapturing = false
        }
    }
}

// MARK: - AVCapturePhotoCaptureDelegate

extension MultiPhotoCameraService: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error {
            setError("Photo capture failed: \(error.localizedDescription)")
            return
        }

        guard let data = photo.fileDataRepresentation() else {
            setError("The camera did not return usable photo data.")
            return
        }

        do {
            let capturedPhoto = try saveCapturedPhoto(data: data)

            DispatchQueue.main.async {
                self.capturedPhotos.append(capturedPhoto)
                self.isCapturing = false
                self.errorMessage = nil
            }
        } catch {
            setError("Could not save photo: \(error.localizedDescription)")
        }
    }
}

// MARK: - Errors

private enum CameraSetupError: LocalizedError {
    case cameraUnavailable
    case couldNotCreateInput(Error)
    case couldNotAddInput
    case couldNotAddPhotoOutput
    case couldNotDecodeImage
    case couldNotEncodeJPEG

    var errorDescription: String? {
        switch self {
        case .cameraUnavailable:
            return "A rear camera is not available on this device."

        case .couldNotCreateInput(let error):
            return "Could not access the camera: \(error.localizedDescription)"

        case .couldNotAddInput:
            return "The camera input could not be added to the capture session."

        case .couldNotAddPhotoOutput:
            return "Photo capture could not be added to the capture session."

        case .couldNotDecodeImage:
            return "The captured photo could not be decoded."

        case .couldNotEncodeJPEG:
            return "The captured photo could not be converted to JPEG."
        }
    }
}
