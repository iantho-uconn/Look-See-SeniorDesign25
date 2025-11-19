//
//  LoginCredentials.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/17/25.
//

import Foundation
import SwiftUI

struct LoginCredentials: Hashable, Codable, Identifiable {
    var id: Int
    var username: String
    var email: String
    var password: String
}
