//
//  Login.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/17/25.
//
import SwiftUI

struct Login: View {
    @State private var username: String = ""
    @State private var password: String = ""
    var body: some View{
        VStack{
            Form {
                Section {
                    TextField(text: $username, prompt: Text("Username")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    SecureField(text: $password, prompt: Text("Password")) {}
                        .autocorrectionDisabled(true)
                }
                Button("Sign in") {}
                    .buttonStyle(BorderlessButtonStyle())
            }
            
        }
    }
}

#Preview {
    Login()
}
