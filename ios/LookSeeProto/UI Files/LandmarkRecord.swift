//
//  LandmarkRecord.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/5/25.
//  Updated by Ian T on 02/13/26
//

import SwiftUI

struct LandmarkRecord: View {
    @State private var labelText: String = ""

    @State private var pickedVideoURL: URL? = nil
    @State private var pickedImage: UIImage? = nil

    @State private var showVideoPicker = false
    @State private var showPhotoPicker = false

    @State private var statusText: String = "No media selected."

    // A2: init submission call
    @StateObject private var uploadService = UploadService()

    private var canInit: Bool {
        let hasMedia = (pickedVideoURL != nil) || (pickedImage != nil)
        return hasMedia && !labelText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                RoundedRectangle(cornerRadius: 25)
                    .stroke(Color(red: 0.75, green: 0.85, blue: 1.00))
                    .fill(Color(red: 0.94, green: 0.96, blue: 1.00))
                    .frame(height: 125)
                    .overlay(
                        Text("Record a short video or take a photo of the landmark you’d like to add. Capture multiple angles for better recognition.")
                            .padding()
                            .foregroundStyle(Color(red: 0.11, green: 0.22, blue: 0.55))
                    )
                    .padding(.horizontal)

                // Capture buttons (always available)
                HStack(spacing: 12) {
                    Button {
                        showVideoPicker = true
                    } label: {
                        Label("Record Video", systemImage: "video")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .foregroundStyle(.white)
                    .background(Color(red: 0.11, green: 0.22, blue: 0.55))
                    .cornerRadius(15)

                    Button {
                        showPhotoPicker = true
                    } label: {
                        Label("Take Photo", systemImage: "camera")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .foregroundStyle(.white)
                    .background(Color(red: 0.11, green: 0.22, blue: 0.55))
                    .cornerRadius(15)
                }
                .padding(.horizontal)

                // Selected media status
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                // Label + init upload (shown once media exists)
                if pickedVideoURL != nil || pickedImage != nil {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Label (required)")
                            .padding(.horizontal)

                        TextField("e.g., Gampel Pavilion, Jonathan Statue, The Dairy Bar…", text: $labelText)
                            .textFieldStyle(.roundedBorder)
                            .padding(.horizontal)

                        Button {
                            Task {
                                await uploadService.upload(label: labelText, videoURL: pickedVideoURL, image: pickedImage)
                            }
                        } label: {
                            Label("Init Upload (A2)", systemImage: "arrow.up.circle")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .padding(.horizontal)
                        .foregroundStyle(.white)
                        .background(canInit ? Color(red: 0.11, green: 0.22, blue: 0.55) : .gray)
                        .cornerRadius(15)
                        .disabled(!canInit)

                        // A2 debug output
                        Text(uploadService.status)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }
                }

                Spacer(minLength: 20)
            }
            .padding(.top, 8)
        }
        // Push content below the top nav/gear area in your UI shell
        .safeAreaInset(edge: .top) { Color.clear.frame(height: 50) }

        .sheet(isPresented: $showVideoPicker) {
            VideoPicker(useCamera: true) { url in
                pickedVideoURL = url
                pickedImage = nil
                statusText = "Selected video: \(url.lastPathComponent)"
                uploadService.status = "Idle"
                uploadService.progress = 0
            }
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoPicker { image in
                pickedImage = image
                pickedVideoURL = nil
                statusText = "Selected photo."
                uploadService.status = "Idle"
                uploadService.progress = 0
            }
        }
    }
}

#Preview {
    LandmarkRecord()
}
