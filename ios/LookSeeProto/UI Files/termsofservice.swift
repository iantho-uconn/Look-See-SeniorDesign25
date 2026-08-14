//
//  termsofservice.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 8/11/26.
//


import SwiftUI

struct TermsOfServiceView: View {
    private var termsText: String {
        guard
            let url = Bundle.main.url(forResource: "TermsOfService", withExtension: "txt"),
            let contents = try? String(contentsOf: url, encoding: .utf8)
        else {
            return "Terms of Service could not be loaded."
        }
        return contents
    }

    var body: some View {
        ScrollView {
            Text(termsText)
                .font(.system(size: 15))
                .padding()
        }
        .navigationTitle("Terms of Service from text")
        .navigationBarTitleDisplayMode(.inline)
    }
}
