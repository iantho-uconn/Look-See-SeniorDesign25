//
//  Help.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct Help: View {
    @ObservedObject var infoView = Settings.shared
    @State private var num : Int
    var body: some View {
        self.num = 6
        VStack{
            Text("Info goes here.")
            Button("Go back") {infoView.infoView.toggle()}
        }
    }
}

#Preview {
    Help()
}
