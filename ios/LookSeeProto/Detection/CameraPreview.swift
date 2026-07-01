//
//  CameraPreview.swift
//  LookSeeProto
//

import SwiftUI
import AVFoundation
import Vision
import Combine
import UIKit

final class CameraSessionCoordinator {
    let session = AVCaptureSession()
    let videoOutput = AVCaptureVideoDataOutput()
    private var videoDevice: AVCaptureDevice?

    init() {
        session.beginConfiguration()
        session.sessionPreset = .high

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

        videoOutput.alwaysDiscardsLateVideoFrames = true
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }

        if let conn = videoOutput.connection(with: .video) {
            if conn.isVideoRotationAngleSupported(90) { conn.videoRotationAngle = 90 }
        }
        session.commitConfiguration()
    }
    
    func setZoom(factor: CGFloat) {
        guard let device = videoDevice else { return }
        let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 5.0)
        let clampedZoom = max(1.0, min(factor, maxZoom))
        
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = clampedZoom
            device.unlockForConfiguration()
        } catch { print("❌ Zoom error: \(error)") }
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

final class OverlayView: UIView {
    weak var previewLayer: AVCaptureVideoPreviewLayer?
    
    var showSafeZone: Bool = true { didSet { setNeedsDisplay() } }
    var safeZoneRect: CGRect = .zero { didSet { setNeedsDisplay() } }
    var detections: [Detection] = [] { didSet { setNeedsDisplay() } }

    override func draw(_ rect: CGRect) {
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
        
        let activeSafeZone = safeZoneRect == .zero ? bounds : safeZoneRect
        
        if showSafeZone {
            let backdropPath = UIBezierPath(rect: rect)
            let clearCutout = UIBezierPath(rect: activeSafeZone).reversing()
            backdropPath.append(clearCutout)
            UIColor.black.withAlphaComponent(0.40).setFill()
            backdropPath.fill()
        }

        if let bestTarget = detections.first {
            // Viewfinder ON = Use the static bounding box. Viewfinder OFF = Wrap the object.
            let targetBox = showSafeZone ? activeSafeZone : bestTarget.bbox
            
            // THE FIX: Clamp the box strictly to the safe visual area so it NEVER bleeds off the phone screen
            let maxScreenBounds = rect.insetBy(dx: 16, dy: 80)
            let clampedBox = targetBox.intersection(maxScreenBounds)
            
            if !clampedBox.isNull && clampedBox.width > 10 && clampedBox.height > 10 {
                UIColor.systemGreen.setStroke()
                let perimeter = UIBezierPath(roundedRect: clampedBox, cornerRadius: 8)
                perimeter.lineWidth = 4.0
                perimeter.stroke()
                
                let labelText = "\(bestTarget.label) \(Int(bestTarget.confidence * 100))%"
                let font = UIFont.systemFont(ofSize: 16, weight: .bold)
                let textStyle: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: UIColor.white
                ]

                let labelSize = labelText.size(withAttributes: textStyle)
                
                let adjustedY = max(clampedBox.minY - labelSize.height - 8, 44)
                let adjustedX = max(clampedBox.minX, 16)
                
                let backgroundPlate = CGRect(
                    x: adjustedX,
                    y: adjustedY,
                    width: labelSize.width + 12,
                    height: labelSize.height + 6
                )

                UIColor.systemGreen.setFill()
                let badge = UIBezierPath(roundedRect: backgroundPlate, cornerRadius: 6)
                badge.fill()
                (labelText as NSString).draw(in: backgroundPlate.insetBy(dx: 6, dy: 3), withAttributes: textStyle)
            }
            
        } else if showSafeZone {
            UIColor(red: 0.0, green: 0.8, blue: 1.0, alpha: 0.8).setStroke()
            let perimeter = UIBezierPath(rect: activeSafeZone)
            perimeter.lineWidth = 2.0
            perimeter.setLineDash([8, 6], count: 2, phase: 0)
            perimeter.stroke()
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    @ObservedObject var detector: Detector
    @Binding var zoomLevel: CGFloat
    @Binding var showSafeZone: Bool
    @Binding var safeZoneRect: CGRect
    @Binding var isAIPaused: Bool

    static let sharedSession = CameraSessionCoordinator()

    func makeUIView(context: Context) -> Preview {
        let view = Preview()
        view.backgroundColor = .black

        view.videoLayer.session = CameraPreview.sharedSession.session
        view.videoLayer.videoGravity = .resizeAspectFill
        view.overlay.previewLayer = view.videoLayer
        view.overlay.showSafeZone = showSafeZone
        view.overlay.safeZoneRect = safeZoneRect

        context.coordinator.overlay = view.overlay
        context.coordinator.view = view

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
      
        let pinch = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePinch(_:)))
        view.addGestureRecognizer(pinch)
        
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.bbClick(_:)))
        view.addGestureRecognizer(tap)
        
        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        // Variable to count bounding boxes
        @ObservedObject var infoView = VariableContainer.shared
        
        // Stop rendering new bounding boxes when they appear for 30 consecutive frames and are above a certain threshold
        if infoView.bboxCounter < 30 {
            // push latest detections to overlay each update
        uiView.overlay.showSafeZone = showSafeZone
        uiView.overlay.safeZoneRect = safeZoneRect
        
        if !VariableContainer.shared.infoView {
            uiView.overlay.detections = detector.detections
            
            // Auto-lock feature in Free Mode
            if !showSafeZone && !detector.detections.isEmpty && !isAIPaused {
                DispatchQueue.main.async {
                    self.isAIPaused = true
                    self.detector.isPaused = true
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
            }
        } else {
            uiView.overlay.detections.removeAll()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(zoomLevel: $zoomLevel) }

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
        private let landmarkService = LandmarkService()
        private let promotionService = PromotionService()
        
        init(zoomLevel: Binding<CGFloat>) { self.zoomLevel = zoomLevel }
        
        @objc func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { return }
            switch recognizer.state {
            case .began: zoomFactorAtGestureStart = device.videoZoomFactor
            case .changed:
                let newZoom = zoomFactorAtGestureStart * recognizer.scale
                let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 5.0)
                let clampedZoom = max(1.0, min(newZoom, maxZoom))
                CameraPreview.sharedSession.setZoom(factor: clampedZoom)
                DispatchQueue.main.async { self.zoomLevel.wrappedValue = clampedZoom }
            default: break
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
            guard !VariableContainer.shared.infoView else { return }
            guard let overlay = overlay, let firstDetection = overlay.detections.first else { return }

            let detectionLabel = firstDetection.label
            
            Task {
                async let landmarkFetch = landmarkService.fetchLandmarkByLabel(label: detectionLabel)
                async let promotionsFetch = promotionService.fetchPromotionsByLabel(label: detectionLabel)
                let (landmark, promotions) = await (landmarkFetch, promotionsFetch)
                
                await MainActor.run {
                    if let landmark = landmark {
                        VariableContainer.shared.landmarkName = landmark.label
                        VariableContainer.shared.landmarkDescription = landmark.shortDescription ?? "No description available."
                    } else {
                        VariableContainer.shared.landmarkName = detectionLabel
                        VariableContainer.shared.landmarkDescription = "No details found in database."
                    }
                    if let activePromo = promotions.first {
                        VariableContainer.shared.promoName = activePromo.name
                        VariableContainer.shared.promoDescription = activePromo.description
                    } else {
                        VariableContainer.shared.promoName = "No active promotion"
                        VariableContainer.shared.promoDescription = ""
                    }

                    infoView.landmarkConfidence =
                        detection.confidence * 100
                    infoView.infoView = true
                    VariableContainer.shared.landmarkConfidence = (firstDetection.confidence * 100)
                    VariableContainer.shared.infoView = true
                }
            }
        }
    }

    final class Preview: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        let overlay = OverlayView()
        override init(frame: CGRect) {
            super.init(frame: frame)
            overlay.backgroundColor = .clear
            overlay.isUserInteractionEnabled = false
            addSubview(overlay)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func layoutSubviews() {
            super.layoutSubviews()
            overlay.frame = bounds
        }
    }
}
