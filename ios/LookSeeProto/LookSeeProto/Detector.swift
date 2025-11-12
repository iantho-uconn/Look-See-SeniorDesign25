//
//  Detector.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 11/11/25.
//

import Foundation
import CoreML
import Vision

final class Detector {
    private let request: VNCoreMLRequest

    init?() {
        guard let url = Bundle.main.url(forResource: "CokeCanDetect", withExtension: "mlmodelc"),
              let mlmodel = try? MLModel(contentsOf: url),
              let vnModel = try? VNCoreMLModel(for: mlmodel) else {
            return nil
        }
        request = VNCoreMLRequest(model: vnModel)
        request.imageCropAndScaleOption = .scaleFill
    }

    func predict(pixelBuffer: CVPixelBuffer,
                 completion: @escaping ([VNRecognizedObjectObservation]) -> Void) {
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try handler.perform([self.request])
                let obs = (self.request.results as? [VNRecognizedObjectObservation]) ?? []
                completion(obs)
            } catch {
                completion([])
            }
        }
    }
}
