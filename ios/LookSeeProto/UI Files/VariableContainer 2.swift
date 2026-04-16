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
    @Published var infoView = false
}
