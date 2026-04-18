//
//  Coaching.swift
//  AR Test
//
//  Created by Christian Barbara on 4/3/26.
//

import SwiftUI
import RealityKit
import ARKit


extension ARView: ARCoachingOverlayViewDelegate {
    func addCoaching() {
        let coachingOverlay = ARCoachingOverlayView()
        coachingOverlay.goal = .anyPlane
        coachingOverlay.session = self.session
        coachingOverlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.addSubview(coachingOverlay)
    }
}
