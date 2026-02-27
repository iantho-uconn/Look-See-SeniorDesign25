//
//  ConfirmSignup.swift
//  LookSeeProto
//
//  Created by Sheenan Ahsan on 2/27/26.
//
import SwiftUI

struct ConfirmSignup: View {
    
    var email: String
    var onConfirmed: () -> Void

    @State private var username = ""
    @State private var code = ""

    @State private var message = ""
    @State private var isLoading = false
    @State private var confirmed = false

    var body: some View {

        VStack {

            Form {

                Section(header: Text("Confirm Account")) {

                    TextField("Username", text: $username)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)

                    TextField("Verification Code", text: $code)
                        .keyboardType(.numberPad)
                }

                Button {
                    confirmAccount()
                } label: {
                    if isLoading {
                        ProgressView()
                    } else {
                        Text("Confirm")
                    }
                }
                .disabled(isLoading || username.isEmpty || code.isEmpty)
                
                .onAppear {
                    username = email
                }

                if !message.isEmpty {
                    Text(message)
                        .foregroundColor(confirmed ? .green : .red)
                }

                if confirmed {
                    Text("You can now sign in.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    func confirmAccount() {
        isLoading = true
        message = ""

        Task {
            do {
                _ = try await AuthService.shared.confirm(
                    username: username,
                    code: code
                )

                confirmed = true
                message = "Account confirmed successfully!"
                onConfirmed()

            } catch {
                confirmed = false
                message = error.localizedDescription
            }

            isLoading = false
        }
    }
}

