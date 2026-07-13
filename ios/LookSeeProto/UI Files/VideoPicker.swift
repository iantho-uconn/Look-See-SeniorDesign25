//
//  VideoPicker.swift
//  LookSeeProto
//
//  Per-clip minimum duration was removed — clips are now combined and
//  validated as a group in LandmarkRecord (sum of all clips must be >= 15s).
//  A per-clip maximum is kept so no single clip is unreasonably long.
//

import SwiftUI
import UIKit
import AVFoundation
import Photos
import CoreLocation

struct VideoPicker: UIViewControllerRepresentable {
    var useCamera: Bool = true
    var onPicked: (URL, CLLocationCoordinate2D?) -> Void
    var onInvalidDuration: (String) -> Void

    private let maxDuration: Double = 60

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.mediaTypes = ["public.movie"]
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
            maxDuration: maxDuration,
            onPicked: onPicked,
            onInvalidDuration: onInvalidDuration
        )
    }

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let maxDuration: Double
        let onPicked: (URL, CLLocationCoordinate2D?) -> Void
        let onInvalidDuration: (String) -> Void

        init(
            maxDuration: Double,
            onPicked: @escaping (URL, CLLocationCoordinate2D?) -> Void,
            onInvalidDuration: @escaping (String) -> Void
        ) {
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
            let durationSeconds = CMTimeGetSeconds(asset.duration)

            guard durationSeconds.isFinite else {
                onInvalidDuration("Could not read video duration.")
                return
            }

            // No per-clip minimum anymore — clips are combined and the
            // combined total is validated against the 15s minimum elsewhere.
            if durationSeconds > maxDuration {
                onInvalidDuration("Each clip must be 60 seconds or less.")
                return
            }

            // Extract original GPS location directly from the Gallery asset
            var extractedLocation: CLLocationCoordinate2D? = nil
            if let phAsset = info[.phAsset] as? PHAsset, let location = phAsset.location {
                extractedLocation = location.coordinate
            }

            onPicked(url, extractedLocation)
        }
    }
}
