//
//  ScannedLandmark.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/18/26.
//

import Foundation
import SwiftUI

struct ScannedLandmark: Hashable, Codable, Identifiable {
    var id: Int
    var name: String
    var description: String?
    var url: String?
    var category: String
    var confidence: String
    var detectionTime: Double
}
