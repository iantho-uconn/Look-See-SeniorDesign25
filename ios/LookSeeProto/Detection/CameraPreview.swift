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
            if conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
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
            for det in detections {
                //print("🔹 \(det.label) \(Int(det.confidence*100))% → \(det.bbox)")
            }
            setNeedsDisplay()
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(),
            let previewLayer = previewLayer else { return }

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
            let labelText = "\(det.label) \(Int(det.confidence * 100))%"
            let font = UIFont.systemFont(ofSize: 14, weight: .semibold)
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white
            ]

            let textSize = labelText.size(withAttributes: attributes)

            // Put label inside top-left of box (clamped to view)
            let textX = max(rect.minX, 0)
            let textY = max(rect.minY, 0)

            let bgRect = CGRect(
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
        
        let pinchGesture = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePinch(_:)))
        view.addGestureRecognizer(pinchGesture)
        
        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        // Variable to count bounding boxes
        @ObservedObject var infoView = VariableContainer.shared
        
        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.bbClick(_:)))
        
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
            // Add tap gesture
            uiView.addGestureRecognizer(tapGesture)
            DispatchQueue.main.async {
                infoView.bboxCounter += 1
            }
            
        }
        
        // Stop rendering rectangles when the pop-up is open or it's been appromixately three seconds after an item has been scanned
        // Reset counter for halting bounding box rendering, remove tap gesture
        if infoView.infoView || infoView.bboxCounter == 210{
            uiView.removeGestureRecognizer(tapGesture)
            uiView.overlay.detections.removeAll()
            DispatchQueue.main.async {
                infoView.bboxCounter = 0
            }
        }
        print(infoView.bboxCounter)
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
        
        // Initialize services
        private let landmarkService = LandmarkService()
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

        @objc func bbClick(_ recognizer: UITapGestureRecognizer? = nil) {
            let tapLocation = recognizer!.location(in: view)
            
            // Ensure there is a detection and the tap is within the overlay
            guard let overlay = overlay,
                  overlay.frame.contains(tapLocation),
                  let detection = overlay.detections.first else {
                return
            }

            let detectionLabel = detection.label // This is "det.label"
            
            print("🔍 detectionLabel sent to API: '\(detectionLabel)'")

            Task {
                // 1. Fetch from both tables in parallel using the detection label
                async let landmarkFetch = landmarkService.fetchLandmarkByLabel(label: detectionLabel)
                async let promotionsFetch = promotionService.fetchPromotionsByLabel(label: detectionLabel)
                
                let (landmark, promotions) = await (landmarkFetch, promotionsFetch)
                
                print("🏛 Landmark returned: \(String(describing: landmark))")
                print("🎯 Promotions returned: \(promotions)")
                print("🎯 Promotions count: \(promotions.count)")
                for promo in promotions {
                    print("  - name: \(promo.name), label: \(promo.landmarkLabel)")
                }

                // 2. Update the UI on the Main Thread
                await MainActor.run {
                    if let landmark = landmark {
                        infoView.landmarkName = landmark.label
                        infoView.landmarkDescription = landmark.shortDescription ?? "No description available."
                    } else {
                        infoView.landmarkName = detectionLabel
                        infoView.landmarkDescription = "No details found in database."
                    }

                    if let activePromo = promotions.first {
                        infoView.promoName = activePromo.name
                        infoView.promoDescription = activePromo.description
                    } else {
                        infoView.promoName = "No active promotion"
                        infoView.promoDescription = ""
                    }

                    infoView.landmarkConfidence = (detection.confidence * 100)
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
