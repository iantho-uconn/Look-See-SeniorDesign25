//
//  CameraPreview.swift
//  LookSeeProto
//

import SwiftUI
import AVFoundation
import Combine
import UIKit

final class CameraSessionCoordinator {
    let session = AVCaptureSession()
    let videoOutput = AVCaptureVideoDataOutput()
    private var videoDevice: AVCaptureDevice?

    init() {
        session.beginConfiguration()
        
        // 🚀 THE FIX: Cap resolution to 1080p to stop thermal overheating during ML execution
        if session.canSetSessionPreset(.hd1920x1080) {
            session.sessionPreset = .hd1920x1080
        } else {
            session.sessionPreset = .high
        }

        guard
            let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        
        videoDevice = device
        
        // 🚀 THE FIX: Cap framerate to 30fps so CoreML isn't running 60 times a second
        do {
            try device.lockForConfiguration()
            device.activeVideoMinFrameDuration = CMTimeMake(value: 1, timescale: 30)
            device.activeVideoMaxFrameDuration = CMTimeMake(value: 1, timescale: 30)
            device.unlockForConfiguration()
        } catch {
            print("Could not lock device to cap framerate: \(error)")
        }
        
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
        DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() }
    }
}

// MARK: - Overlay View

final class OverlayView: UIView {
    weak var previewLayer: AVCaptureVideoPreviewLayer?

    var showSafeZone: Bool = true { didSet { setNeedsDisplay() } }
    var safeZoneRect: CGRect = .zero  { didSet { setNeedsDisplay() } }
    var detections: [Detection] = []  { didSet { setNeedsDisplay() } }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.clear(rect)
        ctx.setLineWidth(2.0)

        let activeSafeZone = safeZoneRect == .zero ? bounds : safeZoneRect

        // Safe zone dimming overlay
        if showSafeZone {
            let backdropPath = UIBezierPath(rect: rect)
            let clearCutout = UIBezierPath(rect: activeSafeZone).reversing()
            backdropPath.append(clearCutout)
            UIColor.black.withAlphaComponent(0.40).setFill()
            backdropPath.fill()
        }

        // Draw ALL detections
        for target in detections {
            let targetBox = showSafeZone ? activeSafeZone : target.bbox
            let maxScreenBounds = rect.insetBy(dx: 16, dy: 80)
            let clampedBox = targetBox.intersection(maxScreenBounds)

            if !clampedBox.isNull && clampedBox.width > 10 && clampedBox.height > 10 {
                // LookSee Brand Green
                UIColor.systemGreen.setStroke()
                let perimeter = UIBezierPath(roundedRect: clampedBox, cornerRadius: 8)
                perimeter.lineWidth = 4.0
                perimeter.stroke()

                //let labelText = "\(target.displayLabel)"
                // label with confi % 
                let confidencePercent = Int(target.confidence * 100)
                let labelText = "\(target.displayLabel) \(confidencePercent)%"
                
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
                (labelText as NSString).draw(
                    in: backgroundPlate.insetBy(dx: 6, dy: 3),
                    withAttributes: textStyle
                )
            }
        }

        if showSafeZone && detections.isEmpty {
            // No detection — show dashed viewfinder outline
            UIColor(red: 0.0, green: 0.8, blue: 1.0, alpha: 0.8).setStroke()
            let perimeter = UIBezierPath(rect: activeSafeZone)
            perimeter.lineWidth = 2.0
            perimeter.setLineDash([8, 6], count: 2, phase: 0)
            perimeter.stroke()
        }
    }
}

// MARK: - CameraPreview

struct CameraPreview: UIViewRepresentable {
    @ObservedObject var detector: Detector
    @Binding var zoomLevel: CGFloat
    @Binding var showSafeZone: Bool
    @Binding var safeZoneRect: CGRect
    let onTap: () -> Void
    let onPinch: () -> Void
    @Binding var isAIPaused: Bool
    
    // Action triggered when a bounding box is tapped
    let onBoxTap: (Detection) -> Void

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
        
        if !isAIPaused {
            CameraPreview.sharedSession.start()
        }

        // We now use ONE unified tap gesture to prevent conflicts
        let tapGesture = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleUnifiedTap(_:))
        )
        tapGesture.cancelsTouchesInView = false
        view.addGestureRecognizer(tapGesture)

        let pinch = UIPinchGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handlePinch(_:))
        )
        view.addGestureRecognizer(pinch)

        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        let infoView = VariableContainer.shared

        uiView.overlay.showSafeZone = showSafeZone
        uiView.overlay.safeZoneRect = safeZoneRect
        
        if isAIPaused || infoView.infoView {
            if isAIPaused { CameraPreview.sharedSession.stop() }
            uiView.overlay.detections.removeAll()
        } else {
            CameraPreview.sharedSession.start()
            uiView.overlay.detections = detector.detections
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(zoomLevel: $zoomLevel, onTap: onTap, onPinch: onPinch, onBoxTap: onBoxTap)
    }

    // MARK: - Coordinator

    final class Coordinator {
        weak var overlay: OverlayView?
        var view: Preview?
        var zoomLevel: Binding<CGFloat>
        
        let onTap: () -> Void
        let onPinch: () -> Void
        let onBoxTap: (Detection) -> Void

        private var zoomFactorAtGestureStart: CGFloat = 1.0

        init(
            zoomLevel: Binding<CGFloat>,
            onTap: @escaping () -> Void,
            onPinch: @escaping () -> Void,
            onBoxTap: @escaping (Detection) -> Void
        ) {
            self.zoomLevel = zoomLevel
            self.onTap = onTap
            self.onPinch = onPinch
            self.onBoxTap = onBoxTap
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
                DispatchQueue.main.async { self.zoomLevel.wrappedValue = clampedZoom }
            default:
                break
            }
        }

        // UNIFIED TAP LOGIC: Checks boxes first, then fires background tap
        @objc func handleUnifiedTap(_ recognizer: UITapGestureRecognizer) {
            onTap() // Always triggers the original background tap (e.g., hiding UI)

            guard let view = view, let overlay = overlay else { return }
            let tapLocation = recognizer.location(in: view)

            // Loop through all active detections on the screen
            for detection in overlay.detections {
                let activeSafeZone = overlay.safeZoneRect == .zero ? overlay.bounds : overlay.safeZoneRect
                let targetBox = overlay.showSafeZone ? activeSafeZone : detection.bbox
                let maxScreenBounds = overlay.bounds.insetBy(dx: 16, dy: 80)
                let clampedBox = targetBox.intersection(maxScreenBounds)

                // Expand hit area so it's super easy to tap while walking (+40 pts on all sides)
                let tapTarget = clampedBox.insetBy(dx: -40, dy: -40)

                // If the tap was inside this box, open the sheet and stop checking
                if tapTarget.contains(tapLocation) {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    self.onBoxTap(detection)
                    break
                }
            }
        }
    }
    
    // MARK: - Preview UIView

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
