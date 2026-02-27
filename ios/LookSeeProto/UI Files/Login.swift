//
//  Login.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/17/25.
//
//import SwiftUI
//
//struct Login: View {
//    @StateObject var vm = AuthViewModel()
//    @State private var username: String = ""
//    @State private var password: String = ""
//    var body: some View{
//        VStack{
//            Form {
//                Section {
//                    TextField(text: $username, prompt: Text("Username")) {}
//                        .autocorrectionDisabled(true)
//                        .textInputAutocapitalization(.never)
//                    SecureField(text: $password, prompt: Text("Password")) {}
//                        .autocorrectionDisabled(true)
//                }
//                Button("Sign in") {
//                    Task {
//                        do {
//                            let result = try await AuthService.shared.signIn(
//                                username: username,
//                                password: password
//                            )
//                            print(result)
//                        } catch {
//                            print(error)
//                        }
//                    }
//                }
//                    .buttonStyle(BorderlessButtonStyle())
//            }
//            
//        }
//    }
//}
//
//#Preview {
//    Login()
//}
import SwiftUI

struct Login: View {
    @ObservedObject var vm: AuthViewModel
    @State private var username = ""
    @State private var password = ""
    
    var onSignedIn: () -> Void
    var onGoToSignup: () -> Void

    var body: some View {
        VStack {
            Form {

                Section {
                    TextField("Email", text: $username)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)

                    SecureField("Password", text: $password)
                        .autocorrectionDisabled(true)
                }

                Button("Sign in") {
                    vm.signIn(username: username, password: password)
                }

                if !vm.errorMessage.isEmpty {
                    Text(vm.errorMessage)
                        .foregroundColor(.red)
                }

                if vm.isSignedIn {
                    Text("Signed in successfully!")
                        .foregroundColor(.green)
                }
                Button {
                    onGoToSignup()
                } label: {
                    Text("Don't have an account? Sign up")
                        .font(.footnote)
                        .foregroundColor(.blue)
                }
            }
        }
        .onChange(of: vm.isSignedIn) { oldValue, newValue in
            if newValue {
                onSignedIn()
            }
        }
    }
}
