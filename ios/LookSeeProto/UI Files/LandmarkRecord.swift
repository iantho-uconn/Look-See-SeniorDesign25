//
//  LandmarkRecord.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 11/5/25.
//

import SwiftUI

struct LandmarkRecord: View {
    @State private var landmark: String = ""
    var body: some View {
        VStack{
            RoundedRectangle(cornerRadius: 25)
                .stroke(Color(red: 0.75, green: 0.85, blue: 1.00))
                .fill(Color(red: 0.94, green: 0.96, blue: 1.00))
                .frame(width: 350, height: 125)
                .overlay(Text("Record a short video of the landmark or building you'd like to add. Make sure to capture it from multiple angles for better recognition.")
                    .padding()
                    .foregroundStyle(Color(red: 0.11, green: 0.22, blue: 0.55))
                )
            Spacer()
                .frame(height: 50)
            HStack{
                Text("Landmark name")
                    .padding([.leading, .trailing])
                Spacer()
            }
            TextField("e.g., Brooklyn Bridge, City Museum...", text: $landmark)
                .textFieldStyle(.roundedBorder)
                .padding([.bottom, .leading, .trailing])
            Divider()
            Button("Start Recording", systemImage:"camera"){
                //
            }
                .foregroundStyle(Color.white)
                .padding(.horizontal, 100)
                .padding(.vertical, 15)
                .background(.gray)
                .cornerRadius(15)
                
        }
    }
}

#Preview {
    LandmarkRecord()
}
