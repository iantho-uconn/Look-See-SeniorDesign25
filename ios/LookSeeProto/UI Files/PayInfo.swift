//
//  PayInfo.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/13/25.
//

import SwiftUI

struct PayInfo: View {
    // Card
    @State private var cardProvider: String = ""
    @State private var cardNum: String = ""
    @State private var expireMonth: Int = Calendar.current.component(.month, from: .now)
    @State private var expireYear: Int = Calendar.current.component(.year, from: .now)
    @State private var cvv: String = ""
    let cvvLimit = 3
    
    let cardProviders = ["Visa", "Mastercard"]
    
    //Billing
    @State private var firstName: String = ""
    @State private var lastName: String = ""
    @State private var state: String = ""
    @State private var city: String = ""
    @State private var postCode: String = ""
    @State private var address1: String = ""
    @State private var address2: String = ""
    @State private var phone: String = ""
    
    let stateArray = ["Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware"]

    var body: some View {
        VStack{
            Form {
                Section("Card information"){
                    Picker("Payment Method", selection: $cardProvider){
                        ForEach(cardProviders, id: \.self){provider in Text("\(provider)").tag(provider)}
                    }
                    TextField(text: $cardNum, prompt: Text("Card number")) {}
                        .autocorrectionDisabled(true)
                    Picker("Month", selection: $expireMonth){
                            ForEach(Array(stride(from: 1, to: 13, by: 1)), id: \.self){ index in
                                Text("\(index)").tag(index)
                            }
                        }
                    Picker("Year", selection: $expireYear){
                        ForEach(Array(stride(from: Calendar.current.component(.year, from: .now), to: Calendar.current.component(.year, from: .now) + 6, by: 1)), id: \.self){ index in
                            Text(verbatim: "\(index)").tag(index)
                        }
                    }
                    TextField("CVV", text: $cvv)
                }
                Section("Billing information"){
                    TextField(text: $firstName, prompt: Text("First name")) {}
                        .autocorrectionDisabled(true)
                    TextField(text: $lastName, prompt: Text("Last name")) {}
                        .autocorrectionDisabled(true)
                    Picker("State", selection: $state){
                        ForEach(stateArray, id: \.self){state in Text("\(state)").tag(state)}
                    }
                    TextField(text: $city, prompt: Text("City")) {}
                        .autocorrectionDisabled(true)
                    TextField(text: $address1, prompt: Text("Billing address")) {}
                        .autocorrectionDisabled(true)
                    TextField(text: $address2, prompt: Text("Billing address, line 2")) {}
                        .autocorrectionDisabled(true)
                    TextField(text: $postCode, prompt: Text("Zip/postal code")) {}
                        .autocorrectionDisabled(true)
                    TextField(text: $phone, prompt: Text("Phone number")) {}
                        .autocorrectionDisabled(true)
                }
            }
            Button("Submit"){}
                .buttonStyle(.bordered)
        }
    }
}

#Preview {
    PayInfo()
}
