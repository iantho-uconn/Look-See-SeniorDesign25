//
//  privacypolicy.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 8/11/26.
//


import SwiftUI

struct PrivacyPolicyView: View {
    private var termsText: String {
        guard
            let url = Bundle.main.url(forResource: "PrivacyPolicy", withExtension: "txt"),
            let contents = try? String(contentsOf: url, encoding: .utf8)
        else {
            return "Privacy Policy could not be loaded."
        }
        return contents
    }

    var body: some View {
        ScrollView {
            Text(termsText)
                .font(.system(size: 15))
                .padding()
        }
        .navigationTitle("Privacy Policy from text")
        .navigationBarTitleDisplayMode(.inline)
    }
}
