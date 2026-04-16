//
//  VariableContainer.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 4/12/26.
//

import SwiftUI
import Combine

class VariableContainer: ObservableObject {
    static let shared = VariableContainer()
    @Published var infoView : Bool = false
    @Published var landmarkName : String = "Not available"
    @Published var landmarkConfidence : Float = 0.00
    @Published var landmarkCategory : String = "Not available"
    @Published var landmarkDescription : String = "No description is available for this landmark."
    @Published var landmarkURL : String = ""
    
    init() {
        self.infoView = false
        self.landmarkName = ""
        self.landmarkConfidence = 0.00
    }
    
    func getlandmarkName() -> String {
        return landmarkName
    }
}
