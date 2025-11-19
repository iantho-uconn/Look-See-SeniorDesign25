//
//  Landmark.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/6/25.
//

import Foundation
import SwiftUI

struct Landmark: Hashable, Codable, Identifiable {
    var id: Int
    var name: String
    var img: String
    var date: String
    var time: String
    var confidence: String
    
//    static let sample = [
//        Landmark(id: 0, name: "NULL", img: "🏢", date: "January 1, 1970", time: "12:00 AM", confidence: "0")
//    ]
}
