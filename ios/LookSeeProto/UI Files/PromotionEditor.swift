//
//  PromotionEditor.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/25/26.
//

import SwiftUI
import PhotosUI

struct PromotionEditor: View {
    @State private var businesses = ["Test", "Jerry's Bait Shop", "Hardware Store"]
    @State private var selectedBusiness = String()
    @State private var promoName = String()
    @State private var promoDescription = String()
    @State private var startDate = Date()
    @State private var endDate = Date()
    @State var selectedItems: [PhotosPickerItem] = []
    var body: some View {
        VStack {
            Form {
                Section {
                    Picker("Location", selection: $selectedBusiness){
                        ForEach(businesses, id: \.self){business in Text(business)}
                    }
                    TextField(text: $promoName, prompt: Text("Promotion name")) {}
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    TextField(text: $promoDescription, prompt: Text("Promotion description"), axis: .vertical) {}
                        .controlSize(.large)
                        .lineLimit(5, reservesSpace: true)
                } header: {Text("Promotion Details")}
                Section{
                    DatePicker(
                        "Start Date",
                        selection: $startDate,
                        displayedComponents: [.date]
                    )
                    DatePicker(
                        "End Date",
                        selection: $endDate,
                        displayedComponents: [.date]
                    )
                } header: {Text("Promotion Dates")}
                
                Section {
                    PhotosPicker(selection: $selectedItems) {
                        Text("Add media")
                    }
                        .onChange(of: selectedItems){}
                } header: {Text("Promotion Media")}
                MediaList(selectedItems: $selectedItems)
            }
        }
    }
}

struct MediaList: View {
    @Binding var selectedItems: [PhotosPickerItem]
    var body: some View {
        if selectedItems.isEmpty {
            Text("No media selected")
        } else {
        }
    }
}

#Preview {
    PromotionEditor()
}
