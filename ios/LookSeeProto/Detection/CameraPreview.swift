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

        // REMOVED: The red debug boxes that were drawing over the screen

        let activeSafeZone = safeZoneRect == .zero ? bounds : safeZoneRect

        // Safe zone dimming overlay
        if showSafeZone {
            let backdropPath = UIBezierPath(rect: rect)
            let clearCutout = UIBezierPath(rect: activeSafeZone).reversing()
            backdropPath.append(clearCutout)
            UIColor.black.withAlphaComponent(0.40).setFill()
            backdropPath.fill()
        }

        // Primary detection box + label
        if let bestTarget = detections.first {
            let targetBox = showSafeZone ? activeSafeZone : bestTarget.bbox
            let maxScreenBounds = rect.insetBy(dx: 16, dy: 80)
            let clampedBox = targetBox.intersection(maxScreenBounds)

            if !clampedBox.isNull && clampedBox.width > 10 && clampedBox.height > 10 {
                // LookSee Brand Green
                UIColor.systemGreen.setStroke()
                let perimeter = UIBezierPath(roundedRect: clampedBox, cornerRadius: 8)
                perimeter.lineWidth = 4.0
                perimeter.stroke()

                let labelText = "\(bestTarget.displayLabel) \(Int(bestTarget.confidence * 100))%"
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

        } else if showSafeZone {
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
    let onTap: () -> Void    // ← was onInteraction
    let onPinch: () -> Void  // ← new, separate
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

        // Tap gesture for opening landmark popup.
        // Starts disabled — enabled as soon as a detection appears,
        // disabled again when the popup is open.
        let tapGesture = context.coordinator.boundingBoxTapGesture
        tapGesture.isEnabled = false
        view.addGestureRecognizer(tapGesture)

        // Pinch gesture for zoom
        let pinch = UIPinchGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handlePinch(_:))
        )
        view.addGestureRecognizer(pinch)
        
        // Always-on tap just for revealing chrome — separate from the detection tap
        let chromeTap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleChromeTap(_:))
        )
        chromeTap.cancelsTouchesInView = false  // lets detection tap also fire
        view.addGestureRecognizer(chromeTap)

        return view
    }

    func updateUIView(_ uiView: Preview, context: Context) {
        let infoView = VariableContainer.shared

        // Always sync overlay state
        uiView.overlay.showSafeZone = showSafeZone
        uiView.overlay.safeZoneRect = safeZoneRect

        if infoView.infoView {
            // Popup is open — clear boxes and disable tap so tapping
            // inside the popup doesn't re-trigger detection handling
            uiView.overlay.detections.removeAll()
            context.coordinator.boundingBoxTapGesture.isEnabled = false
        } else {
            // Popup is closed — push latest detections to the overlay
            uiView.overlay.detections = detector.detections

            // Enable tap whenever there's at least one visible detection,
            // disable when there's nothing to tap on
            context.coordinator.boundingBoxTapGesture.isEnabled = !detector.detections.isEmpty
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(zoomLevel: $zoomLevel, onTap: onTap, onPinch: onPinch)
    }

    // MARK: - Coordinator

    final class Coordinator {
        weak var overlay: OverlayView?
        var view: Preview?
        var zoomLevel: Binding<CGFloat>
        
        let onTap: () -> Void
        let onPinch: () -> Void

        private var zoomFactorAtGestureStart: CGFloat = 1.0

        private let promotionService = PromotionService()

        lazy var boundingBoxTapGesture: UITapGestureRecognizer = {
            UITapGestureRecognizer(target: self, action: #selector(bbClick(_:)))
        }()

        init(
            zoomLevel: Binding<CGFloat>,
            onTap: @escaping () -> Void,
            onPinch: @escaping () -> Void
        ) {
            self.zoomLevel = zoomLevel
            self.onTap = onTap
            self.onPinch = onPinch
        }
        
        @objc func handleChromeTap(_ recognizer: UITapGestureRecognizer) {
            print("chromeTap registered")
            onTap()  // always reveals chrome, regardless of whether a detection was hit
        }
        
        @objc func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
            // Tell the parent view something happened (resets chrome fade timer)
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

        @objc func bbClick(_ recognizer: UITapGestureRecognizer) {
            // Tell the parent view something happened (resets chrome fade timer)
            onTap()

            guard let view, let overlay else { return }

            let tapLocation = recognizer.location(in: view)

            // Confirm tap landed inside the overlay and there's a detection to act on
            guard overlay.frame.contains(tapLocation),
                  let detection = overlay.detections.first else {
                return
            }

            let infoView = VariableContainer.shared

            // If the manifest couldn't resolve this class, show a fallback popup
            guard let landmark = detection.landmarkEntry else {
                print("❌ [bbClick] Local landmark resolution failed for classIndex \(detection.classIndex)")
                DispatchQueue.main.async {
                    infoView.landmarkName = "Class \(detection.classIndex)"
                    infoView.landmarkDescription = "The matching landmark metadata could not be loaded."
                    infoView.promoName = "No active promotion"
                    infoView.promoDescription = ""
                    infoView.landmarkConfidence = detection.confidence * 100
                    infoView.infoView = true
                }
                return
            }

            print("🧭 [bbClick] Tapped: \(landmark.label) (\(String(format: "%.2f", detection.confidence * 100))%)")

            // Fetch promotions from backend (landmark metadata comes from local manifest)
            Task {
                let promotions = await promotionService.fetchPromotionsByLabel(label: landmark.label)

                await MainActor.run {
                    infoView.landmarkName = landmark.label

                    let trimmed = landmark.shortDescription.trimmingCharacters(in: .whitespacesAndNewlines)
                    infoView.landmarkDescription = trimmed.isEmpty ? "No description available." : trimmed

                    if let promo = promotions.first {
                        infoView.promoName = promo.name
                        infoView.promoDescription = promo.description
                    } else {
                        infoView.promoName = "No active promotion"
                        infoView.promoDescription = ""
                    }

                    infoView.landmarkConfidence = detection.confidence * 100
                    infoView.infoView = true
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
