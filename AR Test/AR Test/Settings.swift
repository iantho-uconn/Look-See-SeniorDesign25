//
//  Settings.swift
//  AR Test
//
//  Created by Christian Barbara on 4/7/26.
//

import SwiftUI
import Combine

class Settings: ObservableObject {
    static let shared = Settings()

    @Published var infoView = false
}
