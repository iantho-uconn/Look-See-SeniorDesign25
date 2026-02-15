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
    @State private var errorMessage: String = ""
    @State private var errorAlert = false
    @Binding var loggedIn: Bool
    var body: some View {
        NavigationView {
            VStack {
                Form {
                    Section {
                        TextField(text: $username, prompt: Text("Username")) {}
                            .autocorrectionDisabled(true)
                            .textInputAutocapitalization(.never)
                        SecureField(text: $password, prompt: Text("Password")) {}
                            .autocorrectionDisabled(true)
                    }
                    footer: {NavigationLink(destination: SignIn()) {
                        Text("New? Make an account!")}
                    }
                    Section {
                        Button("Sign in") {
                            autheticateUser()
                        }.alert(errorMessage, isPresented: $errorAlert){}
                    }
                    footer: {NavigationLink(destination: Buttons(loggedIn: $loggedIn).toolbarVisibility(.hidden)) {
                        Text("Continue as guest")}
                    }
                }
                
            }
        }
    }
    
    private func autheticateUser() {
        guard username.isEmpty == false && password.isEmpty == false else {
            errorMessage = "Username or password is missing."
            self.errorAlert.toggle()
            return
        }
        let info = LoginCredentials(username:username, password:password)
        guard let jsonData = try? JSONEncoder().encode(info) else {
            errorMessage = "Error submitting credentials. Please try again."
            self.errorAlert.toggle()
            return
        }
        let url = URL(string: "https://example.com/post")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // .alert("Username or password is incorrect.", isPresented: $errorAccount){}
        loggedIn = true
    }

}

#Preview {
    @Previewable @State var loggedIn = false
    Login(loggedIn: $loggedIn)
}
