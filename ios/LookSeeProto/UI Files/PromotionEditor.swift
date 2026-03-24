//
//  PromotionEditor.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 2/25/26.
//

import SwiftUI
import PhotosUI

struct PromotionEditor: View {
    @State private var businesses = ["Dick's Automotive", "Jerry's Bait Shop", "Hardware Store"] // Set up API to retrieve respective businesses of owner
    @State private var selectedBusiness = String()
    @State private var promoName = String()
    @State private var promoDescription = String()
    @State private var startDate = Date()
    @State private var endDate = Date()
    @State var selectedItems: [PhotosPickerItem] = []
    @State private var media: [Image] = []
    @State private var submit = false
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
                        .onChange(of: selectedItems){_, newValue in
                            media.removeAll()
                            
                            newValue.forEach({ selectedItem in
                                Task {
                                    if let imageData = try? await selectedItem.loadTransferable(type: Data.self),
                                       let uiImage = UIImage(data: imageData){
                                        media.append(Image(uiImage: uiImage))
                                    } else {
                                        print("Image Error")
                                    }
                                }
                            })
                        }
                } header: {Text("Promotion Media")}
                MediaList(selectedItems: $selectedItems, media: $media)
            }
            Button("Submit", role: .cancel) {submit = true}
                .buttonStyle(.bordered)
        }
    }
}

struct MediaList: View {
    @Binding var selectedItems: [PhotosPickerItem]
    @Binding var media: [Image]
    var body: some View {
        if selectedItems.isEmpty {
            Text("No media selected")
        } else {
            ScrollView(.horizontal) {
                LazyHStack {
                    ForEach(0..<media.count, id:\.self){
                        item in media[item]
                            .resizable()
                            .scaledToFit()
                            .frame(width: 300, height: 300)
                    }
                }
            }
        }
    }
}

#Preview {
    PromotionEditor()
}
