//
//  GuidedCaptureOverlay.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/14/26.
//


import SwiftUI

enum RecordingPhase: String, CaseIterable {
    case front = "Step 1: Record the FRONT of the landmark."
    case left = "Step 2: Move to the LEFT side."
    case right = "Step 3: Move to the RIGHT side."
    case last = "Step 4: Capture any remaining details!"
    case negative = "Pan around the room slowly. Do NOT include the landmark in the frame."
}

struct GuidedCaptureOverlay: View {
    let isNegative: Bool
    let isRecording: Bool
    
    @State private var showPopup: Bool = true
    @State private var currentPhase: RecordingPhase = .front
    @State private var timer: Timer?

    init(isNegative: Bool, isRecording: Bool) {
        self.isNegative = isNegative
        self.isRecording = isRecording
        // Set initial state based on negative vs positive
        self._currentPhase = State(initialValue: isNegative ? .negative : .front)
    }

    var body: some View {
        ZStack {
            // Invisible catch-all for taps to dismiss
            Color.black.opacity(0.0001).ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) { showPopup = false }
                }
            
            if showPopup {
                VStack(spacing: 16) {
                    Image(systemName: "info.circle.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(.blue)
                    
                    Text(currentPhase.rawValue)
                        .font(.system(size: 18, weight: .bold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                    
                    Text("Tap anywhere to dismiss")
                        .font(.caption)
                        .foregroundStyle(.gray)
                }
                .padding(24)
                .frame(width: 320, height: 220)
                .background(Color(red: 0.11, green: 0.11, blue: 0.16).opacity(0.95))
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.blue.opacity(0.5), lineWidth: 2))
                .offset(y: -120) // Positioned higher up
                .transition(.scale.combined(with: .opacity))
            } else {
                // Recall button in top right
                VStack {
                    HStack {
                        Spacer()
                        Button {
                            withAnimation(.spring()) { showPopup = true }
                        } label: {
                            Image(systemName: "questionmark.circle.fill")
                                .font(.system(size: 32))
                                .foregroundStyle(.white)
                                .background(Circle().fill(Color.black.opacity(0.5)))
                                .shadow(radius: 4)
                        }
                        .padding(.top, 50)
                        .padding(.trailing, 20)
                    }
                    Spacer()
                }
            }
        }
        .onChange(of: isRecording) { _, recording in
            if recording {
                if !isNegative { startPhaseTimer() }
            } else {
                timer?.invalidate()
                timer = nil
                if !isNegative { currentPhase = .front }
            }
        }
    }

    private func startPhaseTimer() {
        var count = 0
        timer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { _ in
            count += 1
            let phases: [RecordingPhase] = [.front, .left, .right, .last]
            
            if count < phases.count {
                withAnimation(.spring()) {
                    currentPhase = phases[count]
                    showPopup = true
                }
            } else {
                timer?.invalidate()
            }
        }
    }
}
