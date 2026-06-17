//
//  VideoPicker.swift
//  LookSeeProto
//

import SwiftUI
import UIKit
import AVFoundation
import Photos
import CoreLocation
import UniformTypeIdentifiers

struct VideoPicker: UIViewControllerRepresentable {
    var useCamera: Bool = true
    
    var onPicked: (URL, CLLocationCoordinate2D?) -> Void
    var onInvalidDuration: (String) -> Void

    private let minDuration: Double = 15
    private let maxDuration: Double = 60

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        
        // Strict filtering to ensure only videos appear, not photos
        picker.mediaTypes = [UTType.movie.identifier]
        picker.videoQuality = .typeHigh

        if useCamera, UIImagePickerController.isSourceTypeAvailable(.camera) {
            picker.sourceType = .camera
            picker.cameraCaptureMode = .video
            picker.videoMaximumDuration = maxDuration
        } else {
            picker.sourceType = .photoLibrary
        }

        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(
            minDuration: minDuration,
            maxDuration: maxDuration,
            onPicked: onPicked,
            onInvalidDuration: onInvalidDuration
        )
    }

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let minDuration: Double
        let maxDuration: Double
        let onPicked: (URL, CLLocationCoordinate2D?) -> Void
        let onInvalidDuration: (String) -> Void

        init(
            minDuration: Double,
            maxDuration: Double,
            onPicked: @escaping (URL, CLLocationCoordinate2D?) -> Void,
            onInvalidDuration: @escaping (String) -> Void
        ) {
            self.minDuration = minDuration
            self.maxDuration = maxDuration
            self.onPicked = onPicked
            self.onInvalidDuration = onInvalidDuration
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            picker.dismiss(animated: true)
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]
        ) {
            picker.dismiss(animated: true)

            guard let url = info[.mediaURL] as? URL else { return }
            let asset = AVURLAsset(url: url)

            Task {
                do {
                    let duration = try await asset.load(.duration)
                    let durationSeconds = CMTimeGetSeconds(duration)
                    
                    await MainActor.run {
                        guard durationSeconds.isFinite, durationSeconds >= minDuration, durationSeconds <= maxDuration else {
                            onInvalidDuration("Video must be between 15 and 60 seconds.")
                            return
                        }

                        var extractedLocation: CLLocationCoordinate2D? = nil

                        if let phAsset = info[.phAsset] as? PHAsset, let location = phAsset.location {
                            extractedLocation = location.coordinate
                            print("📍 Extracted Location from Library: \(location.coordinate.latitude), \(location.coordinate.longitude)")
                        } else {
                            print("⚠️ No PHAsset found. Must be a live camera recording.")
                        }

                        onPicked(url, extractedLocation)
                    }
                } catch {
                    await MainActor.run {
                        onInvalidDuration("Could not read video duration.")
                    }
                }
            }
        }
    }
}
