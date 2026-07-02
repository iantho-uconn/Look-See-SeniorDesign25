//
//  NegativeGalleryPicker.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/30/26.
//

import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation

struct NegativeGalleryPicker: UIViewControllerRepresentable {
    let onPicked: (URL) -> Void
    let onInvalidDuration: (String) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .videos
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: NegativeGalleryPicker

        init(_ parent: NegativeGalleryPicker) {
            self.parent = parent
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            
            guard let provider = results.first?.itemProvider else { return }
            let typeIdentifier = UTType.movie.identifier
            
            if provider.hasItemConformingToTypeIdentifier(typeIdentifier) {
                provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                    guard let url = url else { return }
                    
                    let tempDir = FileManager.default.temporaryDirectory
                    let newURL = tempDir.appendingPathComponent(UUID().uuidString + ".mov")
                    try? FileManager.default.copyItem(at: url, to: newURL)
                    
                    // VALIDATION: Check for both minimum AND maximum video length
                    Task {
                        let asset = AVAsset(url: newURL)
                        do {
                            let duration = try await asset.load(.duration)
                            let seconds = duration.seconds
                            
                            await MainActor.run {
                                if seconds < 10.0 {
                                    try? FileManager.default.removeItem(at: newURL)
                                    self.parent.onInvalidDuration("Negative videos must be at least 10 seconds long.")
                                } else if seconds > 15.0 {
                                    try? FileManager.default.removeItem(at: newURL)
                                    self.parent.onInvalidDuration("Negative videos cannot be longer than 15 seconds.")
                                } else {
                                    self.parent.onPicked(newURL)
                                }
                            }
                        } catch {
                            await MainActor.run {
                                self.parent.onInvalidDuration("Could not verify video length.")
                            }
                        }
                    }
                }
            }
        }
    }
}
