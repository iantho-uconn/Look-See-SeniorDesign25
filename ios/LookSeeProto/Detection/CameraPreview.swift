//
//  CameraPreview.swift
//  LookSeeTake2
//
//  Created by Ian Thompson on 11/18/25.
//

import SwiftUI
import AVFoundation
import Vision
import Combine

/// Keeps the AVCaptureSession alive and exposes the video output so Detector can attach.
final class CameraSessionCoordinator {
    let session = AVCaptureSession()
    let videoOutput = AVCaptureVideoDataOutput()
    private var videoDevice: AVCaptureDevice?

    init() {
        session.beginConfiguration()
        session.sessionPreset = .high

        // Input: back wide camera
        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        videoDevice = device
        session.addInput(input)

        // Output for frames
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        // Portrait orientation if supported
        if let conn = videoOutput.connection(with: .video) {
            if conn.isVideoRotationAngleSupported(90) {
                conn.videoRotationAngle = 90
            }
        }
        
        session.commitConfiguration()
    }
    
    func setZoom(factor: CGFloat) {
        guard let device = videoDevice else { return }
        let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 5.0) // cap at 5x
        let clampedZoom = max(1.0, min(factor, maxZoom))
        
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = clampedZoom
            device.unlockForConfiguration()
        } catch {
            print("❌ Zoom error: \(error)")
        }
    }

    func start() {
        guard !session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func stop() {
        guard session.isRunning else { return }
        session.stopRunning()
    }
}

/// CoreAnimation overlay view that draws detection boxes.
import UIKit
import AVFoundation


final class OverlayView: UIView {
    weak var previewLayer: AVCaptureVideoPreviewLayer?
    
    // Variable to allow the info pop-up to appear
    @ObservedObject var infoView = VariableContainer.shared
    
    var detections: [Detection] = [] {
        didSet {
            // print("🟢 OverlayView received \(detections.count) detections")
           // for _ in detections {
                //print("🔹 \(det.label) \(Int(det.confidence*100))% → \(det.bbox)")
           // }
            setNeedsDisplay()
        }
    }

    override func draw(_ rect: CGRect) {
       /* guard let ctx = UIGraphicsGetCurrentContext(),
            let previewLayer = previewLayer else { return }
*/
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.clear(rect)
        ctx.setLineWidth(2.0)

        for det in detections {
            let bbox = det.bbox
            
            // print("RAW:", bbox)
            
            let rect = CGRect(
                x: bbox.origin.x * bounds.width,
                y: bbox.origin.y * bounds.height,
                width: bbox.width * (bounds.width * 2),
                height: bbox.height * bounds.height
            )
            
            // print("DRAW RECT:", rect)

            UIColor.systemRed.setStroke()
            ctx.stroke(rect)
        
//        // TODO: Possibly change this for AR support
//        for det in detections {
//            var bbox = det.bbox
//
//            print("RAW:", bbox)
////            // Clamp bounding box to view
////            bbox.origin.x = max(0, min(bbox.origin.x, bounds.width))
////            bbox.origin.y = max(0, min(bbox.origin.y, bounds.height))
////            bbox.size.width = max(0, min(bbox.size.width, bounds.width - bbox.origin.x))
////            bbox.size.height = max(0, min(bbox.size.height, bounds.height - bbox.origin.y))
////
//            print("corr",bbox.origin.x,bbox.origin.y,bbox.size.width,bbox.size.height)
//
//            if bbox.width <= 0 || bbox.height <= 0 { continue }
//
//            // Draw bounding box
//            UIColor.systemGreen.setStroke()
//            ctx.stroke(bbox)

            // Draw label and confidence above the box
            let labelText = "\(det.displayLabel) \(Int(det.confidence * 100))%"
            let font = UIFont.systemFont(ofSize: 14, weight: .semibold)
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white
            ]

            let textSize = labelText.size(withAttributes: attributes)

            // Put label inside top-left of box (clamped to view)
            let textX = max(rect.minX, 0)
            let textY = max(rect.minY, 0)

            let _ = CGRect(                     //let bgRect = CGRect(
                x: textX,
                y: textY,
                width: textSize.width + 8,
                height: textSize.height + 4
            )

            UIColor.systemGreen.setFill()
            //ctx.fill(bgRect)

            // labelText.draw(in: bgRect.insetBy(dx: 4, dy: 2), withAttributes: attributes)
        }
        
        // Variable to count bounding boxes
        @ObservedObject var infoView = VariableContainer.shared
        
        // Green bounding box
        if infoView.bboxCounter >= 29 {
            ctx.clear(rect)
            ctx.setLineWidth(2.0)

            for det in detections {
                let bbox = det.bbox
                
                let rect = CGRect(
                    x: bbox.origin.x * bounds.width,
                    y: bbox.origin.y * bounds.height,
                    width: bbox.width * (bounds.width * 2),
                    height: bbox.height * bounds.height
                )
                UIColor.systemGreen.setStroke()
                ctx.stroke(rect)
            }
            
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    @ObservedObject var detector: Detector
    @State private var currentZoom: CGFloat = 1.0
    @Binding var zoomLevel: CGFloat

    static let sharedSession = CameraSessionCoordinator()

    func makeUIView(context: Context) -> Preview {
        let view = Preview()
        view.backgroundColor = .black

        // camera layer
        view.videoLayer.session = CameraPreview.sharedSession.session
        view.videoLayer.videoGravity = .resizeAspectFill
        view.overlay.previewLayer = view.videoLayer

        // detector -> overlay binding
        context.coordinator.overlay = view.overlay
        context.coordinator.view = view

        // attach detector to video frames once
        detector.attach(to: CameraPreview.sharedSession.videoOutput)
        CameraPreview.sharedSession.start()
        
//        // Tap gesture recognizer
//        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.bbClick(_:)))
////        tapGesture.delaysTouchesBegan = true
//        view.addGestureRecognizer(tapGesture)

        // Add the bounding-box tap recognizer once. updateUIView only enables/disables it.
        let boundingBoxTapGesture = context.coordinator.boundingBoxTapGesture
        boundingBoxTapGesture.isEnabled = false
        view.addGestureRecognizer(boundingBoxTapGesture)
        
        let pinchGesture = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePinch(_:)))
        view.addGestureRecognizer(pinchGesture)
        
        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        // Variable to count bounding boxes
        @ObservedObject var infoView = VariableContainer.shared
        
        // Stop rendering new bounding boxes when they appear for 30 consecutive frames and are above a certain threshold
        if infoView.bboxCounter < 30 {
            // push latest detections to overlay each update
            uiView.overlay.detections = detector.detections
            
            DispatchQueue.main.async {
                if !uiView.overlay.detections.isEmpty && uiView.overlay.detections[0].confidence > 0.10 {
                    infoView.bboxCounter += 1
                }
                else { infoView.bboxCounter = 0 }
            }
        }
        else {
            // The recognizer already exists; only enable it once the detection is stable.
            context.coordinator.boundingBoxTapGesture.isEnabled = true
            DispatchQueue.main.async {
                infoView.bboxCounter += 1
            }
        }
        
        // Stop rendering rectangles when the pop-up is open or it's been appromixately three seconds after an item has been scanned
        // Reset counter for halting bounding box rendering, remove tap gesture
        if infoView.infoView || infoView.bboxCounter >= 210 {
            context.coordinator.boundingBoxTapGesture.isEnabled = false
            uiView.overlay.detections.removeAll()
            DispatchQueue.main.async {
                infoView.bboxCounter = 0
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(zoomLevel: $zoomLevel)
    }

    final class Coordinator {
        weak var overlay: OverlayView?
        var view: Preview?
        var zoomLevel: Binding<CGFloat>
        private var zoomFactorAtGestureStart: CGFloat = 1.0
        
        @ObservedObject var infoView = VariableContainer.shared

        lazy var boundingBoxTapGesture: UITapGestureRecognizer = {
            UITapGestureRecognizer(target: self, action: #selector(bbClick(_:)))
        }()
        
        // Landmark display data now comes from the local manifest.
        // Promotions remain backend-driven.
        private let promotionService = PromotionService()
        
        init(zoomLevel: Binding<CGFloat>) {
            self.zoomLevel = zoomLevel
        }
        
        @objc func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { return }
            
            switch recognizer.state {
            case .began:
                zoomFactorAtGestureStart = device.videoZoomFactor
            case .changed:
                let newZoom = zoomFactorAtGestureStart * recognizer.scale
                let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 5.0)
                let clampedZoom = max(1.0, min(newZoom, maxZoom))
                CameraPreview.sharedSession.setZoom(factor: clampedZoom)
                
                // ← update the UI binding on main thread
                DispatchQueue.main.async {
                    self.zoomLevel.wrappedValue = clampedZoom
                }
            default:
                break
            }
        }

        @objc func bbClick(_ recognizer: UITapGestureRecognizer) {
            guard let view, let overlay else {
                print("⚠️ [Phase 3] Tap ignored because preview/overlay is unavailable")
                return
            }

            let tapLocation = recognizer.location(in: view)

            // Preserve the current selection behavior for this manifest pass:
            // the first stable detection is used. Tap-to-specific-box selection
            // can be tightened separately after the local metadata path is proven.
            guard overlay.frame.contains(tapLocation),
                  let detection = overlay.detections.first else {
                print("⚠️ [Phase 3] Tap did not have an available detection")
                return
            }

            guard let landmark = detection.landmarkEntry else {
                print("")
                print("❌ [Phase 3] Local landmark resolution failed")
                print("   clusterID: \(detection.clusterID)")
                print("   modelVersion: \(detection.modelVersion)")
                print("   classIndex: \(detection.classIndex)")
                print("   classCount: \(detection.classCount)")
                print("")

                DispatchQueue.main.async {
                    self.infoView.landmarkName = "Class \(detection.classIndex)"
                    self.infoView.landmarkDescription =
                        "The matching landmark metadata could not be loaded."
                    self.infoView.promoName = "No active promotion"
                    self.infoView.promoDescription = ""
                    self.infoView.landmarkConfidence =
                        detection.confidence * 100
                    self.infoView.infoView = true
                }
                return
            }

            print("")
            print("🧭 [Phase 3] Detection selected for local popup")
            print("   clusterID: \(detection.clusterID)")
            print("   modelVersion: \(detection.modelVersion)")
            print("   release: \(detection.releaseIdentifier)")
            print("   modelIdentifier: \(detection.modelIdentifier)")
            print("   classIndex: \(detection.classIndex)")
            print("   landmarkId: \(landmark.landmarkId)")
            print("   landmark label: \(landmark.label)")
            print("   confidence: \(String(format: "%.4f", detection.confidence))")
            print("   tapLocation: \(tapLocation)")
            print("✅ Landmark name and description resolved locally")
            print("")

            Task {
                // Promotions remain dynamic and can still be fetched by the
                // real landmark label resolved from the local manifest.
                let promotions =
                    await promotionService.fetchPromotionsByLabel(
                        label: landmark.label
                    )

                print("🎯 Promotions returned for \(landmark.label): \(promotions.count)")
                for promo in promotions {
                    print(
                        "  - name: \(promo.name), " +
                        "label: \(promo.landmarkLabel)"
                    )
                }

                await MainActor.run {
                    infoView.landmarkName = landmark.label

                    let trimmedDescription =
                        landmark.shortDescription.trimmingCharacters(
                            in: .whitespacesAndNewlines
                        )

                    infoView.landmarkDescription =
                        trimmedDescription.isEmpty
                        ? "No description available."
                        : trimmedDescription

                    if let activePromo = promotions.first {
                        infoView.promoName = activePromo.name
                        infoView.promoDescription =
                            activePromo.description
                    } else {
                        infoView.promoName = "No active promotion"
                        infoView.promoDescription = ""
                    }

                    infoView.landmarkConfidence =
                        detection.confidence * 100
                    infoView.infoView = true
                }
            }
        }
    }

    final class Preview: UIView {
        // camera
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        // overlay
        let overlay = OverlayView()

        override init(frame: CGRect) {
            super.init(frame: frame)
            overlay.backgroundColor = .clear
            overlay.isUserInteractionEnabled = false
            addSubview(overlay)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            overlay.frame = bounds
        }
    }
}
