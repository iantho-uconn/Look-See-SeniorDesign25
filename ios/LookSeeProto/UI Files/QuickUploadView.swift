//
//  QuickUploadView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/19/26.
//


import SwiftUI
import AVFoundation
import Combine 

struct QuickUploadView: View {
    let landmark: NearbyLandmark
    @Environment(\.dismiss) var dismiss
    
    // Now that Combine is imported, @StateObject works correctly
    @StateObject private var cameraManager = CameraManager()
    
    @State private var isRecording = false
    @State private var isUploading = false
    @State private var uploadProgress: Double = 0.0
    @State private var showSuccess = false
    
    private let primaryColor = Color(red: 0.11, green: 0.22, blue: 0.55)

    var body: some View {
        NavigationStack {
            ZStack {
                Color(red: 0.04, green: 0.04, blue: 0.06).ignoresSafeArea()
                
                VStack(spacing: 20) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Contribution").font(.caption.bold()).foregroundStyle(.gray)
                        Text(landmark.label).font(.title2.bold()).foregroundStyle(.white)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding()

                    ZStack {
                        CameraPreviewView(session: cameraManager.session)
                            .clipShape(RoundedRectangle(cornerRadius: 20))
                    }
                    .frame(height: 350)
                    .padding(.horizontal)

                    Spacer()

                    VStack(spacing: 12) {
                        Button(action: {
                            if cameraManager.isRecording { cameraManager.stopRecording() }
                            else { cameraManager.startRecording() }
                        }) {
                            Text(cameraManager.isRecording ? "Stop Recording" : "Start Recording")
                                .font(.headline)
                                .frame(maxWidth: .infinity).padding().background(cameraManager.isRecording ? Color.red : primaryColor).clipShape(RoundedRectangle(cornerRadius: 12))
                                .foregroundStyle(.white)
                        }
                        
                        Button("Submit Training Data") {
                            isUploading = true
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity).padding().background(Color.green).clipShape(RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(.white)
                    }.padding()
                }
            }
            .navigationTitle("Upload")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } } }
        }
    }
}

// MARK: - Camera Manager
final class CameraManager: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    
    @Published var isRecording = false // Published property for UI updates
    
    override init() {
        super.init()
        session.beginConfiguration()
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
        }
        session.commitConfiguration()
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func startRecording() {
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("upload.mov")
        movieOutput.startRecording(to: tempURL, recordingDelegate: self)
        DispatchQueue.main.async { self.isRecording = true }
    }

    func stopRecording() {
        movieOutput.stopRecording()
        DispatchQueue.main.async { self.isRecording = false }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        print("✅ Video captured to: \(outputFileURL)")
    }
    
    func stop() { session.stopRunning() }
}

struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: UIScreen.main.bounds)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
