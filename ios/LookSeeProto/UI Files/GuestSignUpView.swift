//
//  GuestSignUpView.swift
//  LookSeeProto
//
//  Compatibility wrapper around the shared signup flow.
//

import SwiftUI

struct GuestSignUpView: View {
    @Environment(\.dismiss) private var dismiss

    var initialBusinessAccount: Bool = false
    var onSignupSuccess: ((String, Bool) -> Void)? = nil
    var onGoToLogin: (() -> Void)? = nil

    var body: some View {
        NavigationStack {
            Signup(
                initialBusinessAccount: initialBusinessAccount,
                onSignupSuccess: { email, wantsBusiness in
                    dismiss()
                    onSignupSuccess?(email, wantsBusiness)
                },
                onGoToLogin: {
                    dismiss()
                    onGoToLogin?()
                }
            )
        }
    }
}
