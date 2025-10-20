//
//  ContentView.swift
//  Playaround
//
//  Created by Christian Barbara on 10/15/25.
//

import SwiftUI

struct Settings: View {
    var body: some View {
        VStack{
            HStack{
                Button("Settings", systemImage:"arrow.backward"){
                    //person.crop.circle
                }
                .padding()
                Spacer()
            }
            ScrollView {
                Button("Get Started"){
                            //
                }
                .padding(.horizontal, 140)
                .padding(.vertical, 20)
                .background(.white)
                .foregroundStyle(Color(red: 0.60, green: 0.06, blue: 0.98))
                .cornerRadius(8)
                .padding()
                
                Divider()
                
                HStack{
                    Text("Recognition Mode")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                
                Divider()
                
                HStack{
                    Text("App Permissions")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                
                Divider()
                
                HStack{
                    Text("Data Management")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
                
                Divider()
                
                HStack{
                    Text("Support & Info")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                        .padding()
                    Spacer()
                }
            }
        }
    }
}

#Preview {
    Settings()
}

