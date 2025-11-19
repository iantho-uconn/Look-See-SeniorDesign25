//
//  Payment.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/13/25.
//
import Foundation
import SwiftUI

struct Payment: Hashable, Codable, Identifiable {
    var id: Int
    var cardProvider: String
    var cardNum: String
    var expireMonth: Int
    var expireYear: Int
    var cvv: Int
    
    var firstName: String
    var lastName: String
    var state: String
    var city: String
    var postCode: String
    var address1: String
    var address2: String
    var phone: String
    
}
