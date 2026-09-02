//
//  TextScannerView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/16/26.
//

import SwiftUI
import VisionKit

// A wrapper sheet with a navigation bar to hold the scanner
struct ScannerSheet: View {
    @Binding var scannedText: String
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            Group {
                // Failsafe: Ensures the device actually supports live text scanning (Simulator does not)
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    TextScannerView(scannedText: $scannedText, dismiss: dismiss)
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    VStack(spacing: 16) {
                        Image(systemName: "text.viewfinder")
                            .font(.system(size: 50))
                            .foregroundColor(.gray)
                        Text("Live Text scanning is not supported on this device.")
                            .font(.headline)
                            .multilineTextAlignment(.center)
                        Text("Please use a physical iPhone running iOS 16+.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                }
            }
            .navigationTitle("Tap highlighted text to copy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// The native Apple VisionKit integration
struct TextScannerView: UIViewControllerRepresentable {
    @Binding var scannedText: String
    var dismiss: DismissAction

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.text()], // We only care about text, not barcodes
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: true,
            isHighlightingEnabled: true // Shows the yellow bounding boxes around text
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        try? uiViewController.startScanning()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var parent: TextScannerView

        init(_ parent: TextScannerView) {
            self.parent = parent
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            switch item {
            case .text(let textItem):
                // If they already typed something, append the new text with a space
                let newText = textItem.transcript
                if parent.scannedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    parent.scannedText = newText
                } else {
                    parent.scannedText += " " + newText
                }
                
                // Fire a success vibration so the user feels it grab the text
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
                
                // Auto-close the camera
                parent.dismiss()
            default:
                break
            }
        }
    }
}
