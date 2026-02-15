//
//  Main.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 1/28/26.
//

import SwiftUI

struct Main: View {
    @State private var loggedIn = false
    var body: some View {
        if loggedIn{Buttons(loggedIn: $loggedIn)}
        else{Login(loggedIn: $loggedIn)}
    }
}

#Preview {
    Main()
}
