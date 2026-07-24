//
//  LandmarkInfo.swift
//  LookSeeProto
//
//  Temporary compatibility wrapper.
//  New code should present PopUp directly.
//

import SwiftUI

@available(
    *,
    deprecated,
    message: "Use PopUp() instead of LandmarkInfo()."
)
struct LandmarkInfo: View {
    var body: some View {
        PopUp()
    }
}
