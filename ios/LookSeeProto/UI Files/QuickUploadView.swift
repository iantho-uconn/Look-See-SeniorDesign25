//
//  QuickUploadView.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 6/19/26.
//

import SwiftUI
import AVKit

struct QuickUploadView: View {
    let landmark: NearbyLandmark
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var vm: AuthViewModel
    
    // Connect the Real AWS Upload Engine
    @StateObject private var uploadService = UploadService()
    
    // THE FIX: Use an Identifiable enum instead of a boolean to prevent SwiftUI race conditions
    enum ActivePicker: Identifiable {
        case camera, library
        var id: Int { hashValue }
    }
    @State private var activePicker: ActivePicker?
    
    @State private var selectedMediaURL: URL?
    @State private var isVideo = false
    
    // Tech UI Colors
    private let bgDark = Color(red: 0.04, green: 0.04, blue: 0.06)
    private let panelBg = Color(white: 0.08)
    private let accentCyan = Color(red: 0.0, green: 0.8, blue: 1.0)
    private let primaryBlue = Color(red: 0.11, green: 0.22, blue: 0.55)

    var body: some View {
        NavigationStack {
            ZStack {
                bgDark.ignoresSafeArea()
                
                VStack(spacing: 24) {
                    
                    // Techy Header
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("TARGETING LANDMARK")
                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                .foregroundStyle(accentCyan)
                            Text(landmark.label)
                                .font(.system(.title2, design: .rounded).weight(.heavy))
                                .foregroundStyle(.white)
                        }
                        Spacer()
                        Image(systemName: "viewfinder")
                            .font(.system(size: 32, weight: .ultraLight))
                            .foregroundStyle(accentCyan)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 10)
                    
                    // Media HUD Box
                    ZStack {
                        RoundedRectangle(cornerRadius: 24)
                            .fill(panelBg)
                            .frame(height: 420)
                            .overlay(
                                RoundedRectangle(cornerRadius: 24)
                                    .stroke(selectedMediaURL == nil ? Color.gray.opacity(0.3) : accentCyan.opacity(0.6), lineWidth: selectedMediaURL == nil ? 1 : 2)
                            )
                            .shadow(color: selectedMediaURL == nil ? .clear : accentCyan.opacity(0.3), radius: 10, x: 0, y: 0)
                        
                        if let url = selectedMediaURL {
                            // Media Preview
                            if isVideo {
                                VideoPlayer(player: AVPlayer(url: url))
                                    .clipShape(RoundedRectangle(cornerRadius: 24))
                            } else {
                                AsyncImage(url: url) { phase in
                                    if let image = phase.image {
                                        image.resizable().aspectRatio(contentMode: .fill)
                                    }
                                }
                                .frame(height: 420)
                                .clipShape(RoundedRectangle(cornerRadius: 24))
                            }
                            
                            // Discard Overlay
                            VStack {
                                HStack {
                                    Spacer()
                                    Button {
                                        withAnimation {
                                            selectedMediaURL = nil
                                            uploadService.reset()
                                        }
                                    } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 16, weight: .bold))
                                            .foregroundStyle(.white)
                                            .padding(12)
                                            .background(Circle().fill(Color.red.opacity(0.8)))
                                    }
                                    .padding(16)
                                    .disabled(uploadService.isUploading)
                                }
                                Spacer()
                            }
                        } else {
                            // Empty State
                            VStack(spacing: 20) {
                                Image(systemName: "arrow.up.doc.on.clipboard")
                                    .font(.system(size: 40, weight: .light))
                                    .foregroundStyle(.gray)
                                
                                Text("AWAITING TRAINING DATA")
                                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                                    .foregroundStyle(.gray)
                                
                                HStack(spacing: 16) {
                                    Button {
                                        activePicker = .camera // Fixed
                                    } label: {
                                        VStack(spacing: 8) {
                                            Image(systemName: "camera.viewfinder")
                                                .font(.title2)
                                            Text("CAPTURE")
                                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                        }
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 20)
                                        .background(Color.white.opacity(0.05))
                                        .foregroundStyle(accentCyan)
                                        .clipShape(RoundedRectangle(cornerRadius: 16))
                                    }
                                    
                                    Button {
                                        activePicker = .library // Fixed
                                    } label: {
                                        VStack(spacing: 8) {
                                            Image(systemName: "folder.fill")
                                                .font(.title2)
                                            Text("BROWSE")
                                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                        }
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 20)
                                        .background(Color.white.opacity(0.05))
                                        .foregroundStyle(.white)
                                        .clipShape(RoundedRectangle(cornerRadius: 16))
                                    }
                                }
                                .padding(.horizontal, 24)
                                .padding(.top, 10)
                                
                                Text("Videos must be 15 - 60 seconds.")
                                    .font(.system(.caption2, design: .monospaced))
                                    .foregroundStyle(.gray)
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                    
                    Spacer()
                    
                    // Action Footer
                    if uploadService.isUploading || uploadService.stage == .complete {
                        VStack(spacing: 12) {
                            if uploadService.stage == .complete {
                                Image(systemName: "checkmark.circle.fill").font(.title).foregroundStyle(.green)
                            } else if uploadService.stage == .failed {
                                Image(systemName: "exclamationmark.triangle.fill").font(.title).foregroundStyle(.red)
                            } else {
                                ProgressView(value: uploadService.progress)
                                    .tint(accentCyan)
                            }
                            
                            Text(uploadService.status)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(uploadService.stage == .complete ? .green : (uploadService.stage == .failed ? .red : accentCyan))
                            
                            if uploadService.stage == .complete {
                                Button("DONE") { dismiss() }
                                    .font(.system(.caption, design: .monospaced).weight(.bold))
                                    .padding(.top, 8)
                                    .foregroundStyle(.white)
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 20)
                    } else {
                        Button {
                            Task { await triggerRealUpload() }
                        } label: {
                            HStack {
                                Image(systemName: "network")
                                Text("INITIATE UPLOAD")
                                    .font(.system(.headline, design: .monospaced).weight(.bold))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                            .background(selectedMediaURL == nil ? Color.white.opacity(0.05) : primaryBlue)
                            .foregroundStyle(selectedMediaURL == nil ? Color.gray : .white)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .shadow(color: selectedMediaURL == nil ? .clear : primaryBlue.opacity(0.5), radius: 8, x: 0, y: 4)
                        }
                        .disabled(selectedMediaURL == nil || uploadService.isUploading)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 20)
                    }
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Abort") { dismiss() }
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(.red)
                        .disabled(uploadService.isUploading && uploadService.stage != .complete)
                }
            }
            // THE FIX: Open the sheet bound to the specific item enum
            .sheet(item: $activePicker) { picker in
                MediaPicker(
                    sourceType: picker == .camera ? .camera : .photoLibrary,
                    selectedURL: $selectedMediaURL,
                    isVideo: $isVideo
                )
            }
        }
    }
    
    // MARK: - The Real AWS Hand-off
    private func triggerRealUpload() async {
        guard let url = selectedMediaURL else { return }
        
        await vm.fetchUserEmail()
        
        // --- TOKEN FIX: Fetch token from AuthViewModel and pass it down ---
        let idToken = await vm.fetchIdToken()
        
        let uploadImage: UIImage? = isVideo ? nil : UIImage(contentsOfFile: url.path)
        let uploadVideoURL: URL? = isVideo ? url : nil

        do {
            let _ = try await uploadService.upload(
                userEmail: vm.userEmail,
                idToken: idToken, // <-- Passed right here!
                label: landmark.label,
                landmarkId: landmark.landmarkId,
                landmarkLabel: landmark.label,
                shortDescription: landmark.shortDescription,
                userDescription: nil,
                latitude: landmark.latitude,
                longitude: landmark.longitude,
                horizontalAccuracy: 10.0,
                videoURL: uploadVideoURL,
                image: uploadImage
            )
            print("✅ QuickUpload Completed Successfully")
        } catch {
            print("❌ QuickUpload Failed: \(error.localizedDescription)")
        }
    }
}

// MARK: - iOS Native Media Picker
struct MediaPicker: UIViewControllerRepresentable {
    var sourceType: UIImagePickerController.SourceType
    @Binding var selectedURL: URL?
    @Binding var isVideo: Bool
    @Environment(\.presentationMode) var presentationMode
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = sourceType
        picker.mediaTypes = ["public.image", "public.movie"]
        picker.videoQuality = .typeHigh
        picker.videoMaximumDuration = 60.0
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: MediaPicker
        init(_ parent: MediaPicker) { self.parent = parent }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let mediaType = info[.mediaType] as? String {
                if mediaType == "public.movie", let url = info[.mediaURL] as? URL {
                    parent.isVideo = true
                    parent.selectedURL = url
                } else if mediaType == "public.image" {
                    if let image = info[.originalImage] as? UIImage,
                       let data = image.jpegData(compressionQuality: 0.9) {
                        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
                        try? data.write(to: tempURL)
                        parent.isVideo = false
                        parent.selectedURL = tempURL
                    }
                }
            }
            parent.presentationMode.wrappedValue.dismiss()
        }
    }
}
