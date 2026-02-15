//
//  SignIn.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/9/26.
//

import SwiftUI

struct SignIn : View {
    @State private var username: String = ""
    @State private var password: String = ""
    @State private var passwordVerify: String = ""
    @State private var email: String = ""
    var body: some View {
        VStack {
            Form {
                Section {
                    TextField(text: $email, prompt: Text("Email")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    TextField(text: $username, prompt: Text("Username")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    SecureField(text: $password, prompt: Text("Password")) {}
                        .autocorrectionDisabled(true)
                    SecureField(text: $passwordVerify, prompt: Text("Re-enter Password")) {}
                        .autocorrectionDisabled(true)
                }
                Button("Sign up") {
                    
                }
            }
        }
    }
}

#Preview {
    SignIn()
}
