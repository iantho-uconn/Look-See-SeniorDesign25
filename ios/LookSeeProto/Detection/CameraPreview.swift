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
        
        let pinch = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePinch(_:)))
        view.addGestureRecognizer(pinch)
        
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.bbClick(_:)))
        view.addGestureRecognizer(tap)
        
        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
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
