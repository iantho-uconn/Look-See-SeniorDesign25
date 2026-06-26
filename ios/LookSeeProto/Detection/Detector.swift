import Foundation
import AVFoundation
import CoreML
import SwiftUI
import Combine
import CoreImage
import UIKit

struct Detection: Identifiable {
    let id = UUID()
    let label: String
    let confidence: Float
    let bbox: CGRect
}

class BoundingBoxSmoother {
    private var history: [CGRect] = []
    private let maxFrames = 4
    
    func smooth(newBox: CGRect) -> CGRect {
        history.append(newBox)
        if history.count > maxFrames { history.removeFirst() }
        
        let count = CGFloat(history.count)
        let avgX = history.map { $0.minX }.reduce(0, +) / count
        let avgY = history.map { $0.minY }.reduce(0, +) / count
        let avgW = history.map { $0.width }.reduce(0, +) / count
        let avgH = history.map { $0.height }.reduce(0, +) / count
        
        return CGRect(x: avgX, y: avgY, width: avgW, height: avgH)
    }
    func reset() { history.removeAll() }
}

final class Detector: NSObject, ObservableObject {
    @Published var detections: [Detection] = []
    @Published var lastInferenceMS: Double = 0
    @Published var bufferSize: CGSize = .zero
    
    @Published var isPaused: Bool = false
    @Published var classLabels: [String] = [] // Dynamic model labels
    var dynamicSafeZone: CGRect = .zero

    private var model: MLModel?
    private let queue = DispatchQueue(label: "yolo.queue")
    private let ciContext = CIContext()
    
    private var isAttached = false
    private var throttling = false

    private let inputSize = CGSize(width: 640, height: 640)
    private let confidenceThreshold: Float = 0.35 // Sensitive enough to catch the ceiling light
    private let iouThreshold: Float = 0.45
    
    private var smoothers: [String: BoundingBoxSmoother] = [:]

    override init() {
        super.init()
        observeActiveCluster()
    }

    func resetEngine() {
        DispatchQueue.main.async {
            self.detections.removeAll()
            for smoother in self.smoothers.values { smoother.reset() }
        }
    }

    private func observeActiveCluster() {
        Task { @MainActor in
            for await clusterID in ModelSelector.shared.$activeClusterID.values {
                guard let clusterID, case .loaded(let models) = ModelService.shared.state,
                      let match = models.first(where: { $0.clusterID == clusterID }),
                      let url = match.compiledModelURL else { continue }
                loadModel(from: url, clusterID: clusterID)
            }
        }
    }

    private func loadModel(from url: URL, clusterID: String) {
        queue.async {
            do {
                let loaded = try MLModel(contentsOf: url)
                let labels = loaded.modelDescription.classLabels as? [String] ?? []
                
                DispatchQueue.main.async {
                    self.model = loaded
                    self.classLabels = labels
                    self.smoothers.removeAll()
                    print("✅ Detector switched to cluster \(clusterID) with \(labels.count) labels.")
                }
            } catch { print("❌ Model load error: \(error)") }
        }
    }

    func attach(to output: AVCaptureVideoDataOutput) {
        guard !isAttached else { return }
        isAttached = true
        if let connection = output.connection(with: .video), connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        output.setSampleBufferDelegate(self, queue: queue)
    }

    private func process(pixelBuffer: CVPixelBuffer) {
        guard let model = model, !throttling else { return }
        if isPaused { return }
        
        throttling = true
        let start = CFAbsoluteTimeGetCurrent()

        let originalWidth = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let originalHeight = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        DispatchQueue.main.async { self.bufferSize = CGSize(width: originalWidth, height: originalHeight) }

        let (inputBuffer, scale, padX, padY) = letterbox(pixelBuffer: pixelBuffer)

        guard let input = try? MLDictionaryFeatureProvider(dictionary: [
            "image": MLFeatureValue(pixelBuffer: inputBuffer),
            "confidenceThreshold": NSNumber(value: confidenceThreshold),
            "iouThreshold": NSNumber(value: iouThreshold)
        ]) else { throttling = false; return }

        do {
            let result = try model.prediction(from: input)
            guard let confArray = result.featureValue(for: "confidence")?.multiArrayValue,
                  let coordArray = result.featureValue(for: "coordinates")?.multiArrayValue else {
                throttling = false
                return
            }

            let newDetections = parseDetections(
                confArray: confArray,
                coordArray: coordArray,
                scale: scale,
                padX: padX,
                padY: padY,
                originalSize: CGSize(width: originalWidth, height: originalHeight)
            )

            let end = CFAbsoluteTimeGetCurrent()
            DispatchQueue.main.async {
                self.detections = newDetections
                self.lastInferenceMS = (end - start) * 1000
            }
        } catch { print("Prediction error: \(error)") }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.03) { self.throttling = false }
    }

    private func letterbox(pixelBuffer: CVPixelBuffer) -> (CVPixelBuffer, CGFloat, CGFloat, CGFloat) {
        let width = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let height = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let scale = min(inputSize.width / width, inputSize.height / height)
        let newW = width * scale
        let newH = height * scale
        let padX = (inputSize.width - newW) / 2
        let padY = (inputSize.height - newH) / 2

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let resized = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let black = CIImage(color: .black).cropped(to: CGRect(origin: .zero, size: inputSize))
        let composed = resized.transformed(by: CGAffineTransform(translationX: padX, y: padY)).composited(over: black)

        var output: CVPixelBuffer?
        CVPixelBufferCreate(nil, Int(inputSize.width), Int(inputSize.height), kCVPixelFormatType_32BGRA, nil, &output)
        ciContext.render(composed, to: output!)
        return (output!, scale, padX, padY)
    }

    private func parseDetections(confArray: MLMultiArray, coordArray: MLMultiArray, scale: CGFloat, padX: CGFloat, padY: CGFloat, originalSize: CGSize) -> [Detection] {
        var rawDetections: [Detection] = []
        let confPtr = confArray.dataPointer.bindMemory(to: Float.self, capacity: confArray.count)
        let coordPtr = coordArray.dataPointer.bindMemory(to: Float.self, capacity: coordArray.count)

        let numDetections = coordArray.shape[0].intValue
        let numClasses = confArray.shape[1].intValue

        let screenWidth = UIScreen.main.bounds.width
        let screenHeight = UIScreen.main.bounds.height
        let screenScale = max(screenWidth / originalSize.width, screenHeight / originalSize.height)
        let offsetX = (originalSize.width * screenScale - screenWidth) / 2
        let offsetY = (originalSize.height * screenScale - screenHeight) / 2

        let activeSafeZone = dynamicSafeZone == .zero ? UIScreen.main.bounds : dynamicSafeZone

        for i in 0..<numDetections {
            var bestScore: Float = 0
            var bestClass = 0
            for c in 0..<numClasses {
                let score = confPtr[i * numClasses + c]
                if score > bestScore { bestScore = score; bestClass = c }
            }
            if bestScore < confidenceThreshold { continue }

            let rawCx = CGFloat(coordPtr[i * 4 + 0])
            let rawCy = CGFloat(coordPtr[i * 4 + 1])
            let rawW = CGFloat(coordPtr[i * 4 + 2])
            let rawH = CGFloat(coordPtr[i * 4 + 3])
            
            let cx640 = rawCx <= 1.0 ? rawCx * inputSize.width : rawCx
            let cy640 = rawCy <= 1.0 ? rawCy * inputSize.height : rawCy
            let w640 = rawW <= 1.0 ? rawW * inputSize.width : rawW
            let h640 = rawH <= 1.0 ? rawH * inputSize.height : rawH

            let finalX = (((cx640 - padX) / scale) * screenScale) - offsetX
            let finalY = (((cy640 - padY) / scale) * screenScale) - offsetY
            let finalW = (w640 / scale) * screenScale
            let finalH = (h640 / scale) * screenScale
            if finalW <= 0 || finalH <= 0 { continue }
            
            let rect = CGRect(x: finalX - finalW/2, y: finalY - finalH/2, width: finalW, height: finalH)
            
            // Re-enabled intersection check so ceiling light detects properly
            if rect.intersects(activeSafeZone) {
                let className = bestClass < classLabels.count ? classLabels[bestClass] : "Class \(bestClass)"
                rawDetections.append(Detection(label: className, confidence: bestScore, bbox: rect))
            }
        }

        let nmsDetections = nonMaxSuppression(detections: rawDetections, iouThreshold: iouThreshold)
        
        var currentLabels = Set<String>()
        for det in nmsDetections { currentLabels.insert(det.label) }
        for key in smoothers.keys { if !currentLabels.contains(key) { smoothers.removeValue(forKey: key) } }
        
        var finalResults: [Detection] = []
        for det in nmsDetections {
            if smoothers[det.label] == nil { smoothers[det.label] = BoundingBoxSmoother() }
            finalResults.append(Detection(label: det.label, confidence: det.confidence, bbox: smoothers[det.label]!.smooth(newBox: det.bbox)))
        }
        return finalResults
    }

    private func nonMaxSuppression(detections: [Detection], iouThreshold: Float) -> [Detection] {
        var results: [Detection] = []
        var sorted = detections.sorted { $0.confidence > $1.confidence }
        while !sorted.isEmpty {
            let best = sorted.removeFirst()
            results.append(best)
            sorted.removeAll {
                let inter = best.bbox.intersection($0.bbox)
                let iou = inter.isNull ? 0 : (inter.width * inter.height) / ((best.bbox.width * best.bbox.height) + ($0.bbox.width * $0.bbox.height) - (inter.width * inter.height))
                return iou > CGFloat(iouThreshold)
            }
        }
        return results
    }
}

extension Detector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if let pb = CMSampleBufferGetImageBuffer(sampleBuffer) { process(pixelBuffer: pb) }
    }
}
