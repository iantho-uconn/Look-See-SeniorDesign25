//
//  LandmarkScan.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct LandmarkScan: View {
    
    //create detection object for camera preview
    @StateObject private var detector = Detector()
    
    // Variable to allow the info pop-up to appear
    @ObservedObject var infoView = VariableContainer.shared
    
    @State private var blurAmount : Float = 0.00
    
    var body: some View {
        ZStack {
            let blurAmount = infoView.infoView ? 5.0 : 0.0
            //initialize camera preview with detection object
            CameraPreview(detector: detector)
                .ignoresSafeArea()
                .blur(radius: blurAmount)
            if(infoView.infoView){PopUp()}
            /*
            LinearGradient(
                gradient: Gradient(colors:[
                    Color(red: 1.0, green: 1.0, blue: 1.00),
                    Color(red: 0.95, green: 0.21, blue: 0.62)
                ]),
                startPoint: .top,
                endPoint: .bottom
            )
            .opacity(0.20)
            .ignoresSafeArea()
            
             */
             /*
            
            //temp hud for testing
            VStack(alignment: .leading, spacing: 6) {
                            Text(detector.isModelLoaded ? "Model: Loaded" : "Model: Loading…")
                            Text("Detections: \(detector.detections.count)")
                            if let top = detector.detections.max(by: { $0.confidence < $1.confidence }) {
                                Text("Top: \(top.label) (\(Int(top.confidence * 100))%)")
                            }
                        }
                        .font(.caption)
                        .padding(10)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .padding()
             */
        }
    }
}

//#Preview {
//    LandmarkScan()
//}
