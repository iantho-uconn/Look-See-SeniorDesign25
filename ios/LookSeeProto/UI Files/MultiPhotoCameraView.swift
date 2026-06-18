//
//  MultiPhotoCameraView.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/16/26.
//


import AVFoundation
import SwiftUI
import UIKit

struct MultiPhotoCameraView: View {
    @Environment(\.dismiss) private var dismiss

    @StateObject private var cameraService: MultiPhotoCameraService

    private let minimumPhotoCount: Int
    private let maximumPhotoCount: Int
    private let onDone: ([CapturedNegativePhoto]) -> Void

    init(
        existingPhotos: [CapturedNegativePhoto],
        minimumPhotoCount: Int = 5,
        maximumPhotoCount: Int = 10,
        onDone: @escaping ([CapturedNegativePhoto]) -> Void
    ) {
        self.minimumPhotoCount = minimumPhotoCount
        self.maximumPhotoCount = maximumPhotoCount
        self.onDone = onDone

        _cameraService = StateObject(
            wrappedValue: MultiPhotoCameraService(
                initialPhotos: existingPhotos,
                maximumPhotoCount: maximumPhotoCount
            )
        )
    }

    private var hasMinimumPhotos: Bool {
        cameraService.capturedPhotos.count >= minimumPhotoCount
    }

    private var remainingRequiredPhotos: Int {
        max(
            minimumPhotoCount - cameraService.capturedPhotos.count,
            0
        )
    }

    var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()

            MultiPhotoCameraPreview(
                session: cameraService.session
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                topControls

                Spacer()

                instructions

                thumbnailStrip

                bottomControls
            }

            if let errorMessage = cameraService.errorMessage {
                cameraErrorOverlay(message: errorMessage)
            }
        }
        .onAppear {
            cameraService.start()
        }
        .onDisappear {
            cameraService.stop()
        }
    }

    private var topControls: some View {
        HStack {
            Button {
                cameraService.discardNewPhotos()
                dismiss()
            } label: {
                Text("Cancel")
                    .fontWeight(.semibold)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.55))
                    .clipShape(Capsule())
            }

            Spacer()

            Text("\(cameraService.capturedPhotos.count) / \(maximumPhotoCount)")
                .font(.headline)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.black.opacity(0.55))
                .clipShape(Capsule())
        }
        .foregroundStyle(.white)
        .padding()
    }

    private var instructions: some View {
        VStack(spacing: 5) {
            Text("Capture Negative References")
                .font(.headline)

            Text("Photograph the surrounding area, not the landmark itself.")
                .font(.footnote)
                .multilineTextAlignment(.center)

            if remainingRequiredPhotos > 0 {
                Text("\(remainingRequiredPhotos) more required")
                    .font(.footnote.bold())
                    .foregroundStyle(.yellow)
            } else {
                Text("Minimum complete")
                    .font(.footnote.bold())
                    .foregroundStyle(.green)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private var thumbnailStrip: some View {
        if !cameraService.capturedPhotos.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(cameraService.capturedPhotos) { photo in
                        ZStack(alignment: .topTrailing) {
                            Image(uiImage: photo.thumbnail)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 72, height: 72)
                                .clipShape(
                                    RoundedRectangle(cornerRadius: 10)
                                )
                                .clipped()

                            Button {
                                cameraService.removePhoto(photo)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title3)
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .red)
                            }
                            .offset(x: 6, y: -6)
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
            .background(.black.opacity(0.45))
        }
    }

    private var bottomControls: some View {
        HStack {
            Color.clear
                .frame(width: 90, height: 52)

            Spacer()

            Button {
                cameraService.capturePhoto()
            } label: {
                ZStack {
                    Circle()
                        .fill(.white)
                        .frame(width: 76, height: 76)

                    Circle()
                        .stroke(.black.opacity(0.8), lineWidth: 3)
                        .frame(width: 64, height: 64)

                    if cameraService.isCapturing {
                        ProgressView()
                            .tint(.black)
                    }
                }
            }
            .disabled(!cameraService.canCaptureAnotherPhoto)
            .opacity(
                cameraService.canCaptureAnotherPhoto ? 1 : 0.45
            )

            Spacer()

            Button {
                onDone(cameraService.capturedPhotos)
                dismiss()
            } label: {
                Text("Done")
                    .fontWeight(.bold)
                    .frame(width: 90, height: 52)
                    .background(
                        hasMinimumPhotos
                        ? Color.blue
                        : Color.gray.opacity(0.75)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .disabled(!hasMinimumPhotos)
        }
        .foregroundStyle(.white)
        .padding(.horizontal)
        .padding(.top, 12)
        .padding(.bottom, 28)
        .background(.black.opacity(0.65))
    }

    private func cameraErrorOverlay(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill")
                .font(.system(size: 42))

            Text("Camera Unavailable")
                .font(.title2.bold())

            Text(message)
                .multilineTextAlignment(.center)

            if cameraService.authorizationStatus == .denied {
                Button("Open Settings") {
                    guard let settingsURL = URL(
                        string: UIApplication.openSettingsURLString
                    ) else {
                        return
                    }

                    UIApplication.shared.open(settingsURL)
                }
                .buttonStyle(.borderedProminent)
            }

            Button("Close") {
                cameraService.discardNewPhotos()
                dismiss()
            }
            .buttonStyle(.bordered)
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.9))
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .padding()
    }
}

// MARK: - Camera preview

private struct MultiPhotoCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewUIView {
        let view = CameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(
        _ uiView: CameraPreviewUIView,
        context: Context
    ) {
        if uiView.previewLayer.session !== session {
            uiView.previewLayer.session = session
        }
    }
}

private final class CameraPreviewUIView: UIView {
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override func layoutSubviews() {
        super.layoutSubviews()

        guard let connection = previewLayer.connection else {
            return
        }

        if connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }
}
