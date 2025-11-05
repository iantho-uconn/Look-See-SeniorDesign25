//
//  Library.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/21/25.
//
import Combine
import SwiftUI

struct Library: View {
    @State private var locations = Location.sample
    @State private var searchText = ""
    @State private var searchIsActive = false
    var body: some View {
        VStack{
            HStack{
                Button("Library & History", systemImage:"arrow.backward"){
                }
                .padding()
                Spacer()
            }
            NavigationStack {
                List{
                    ForEach(filter) { item in
                        VStack(alignment: .leading){
                            HStack{
                                Text(item.img)
                                Text(item.name)
                                Spacer()
                                Text("\(item.date), \(item.time)")
                            }
                            Text("\(item.confidence)% confidence")
                        }
                    }
                }
                .searchable(text: $searchText)
                .navigationTitle("Previous locations")
            }
        }
        
    }
    var filter: [Location]{
        if searchText.isEmpty{
            return Location.sample
        }
        else{
            return Location.sample.filter{$0.name.localizedCaseInsensitiveContains(searchText)}
        }
    }
}

struct Location: Identifiable{
    let id = UUID()
    var name: String
    var img: String
    var date: String
    var time: String
    var confidence: String
    
    init(_ name: String, img: String, date: String, time: String, confidence: String){
        self.name = name
        self.img = img
        self.date = date
        self.time = time
        self.confidence = confidence
    }
    
    static let sample = [
        Location("Empire State Building", img: "🏢", date: "Today", time: "2:30 PM", confidence: "94"),
        Location("Brooklyn Bridge", img: "🌉", date: "Today", time: "1:15 PM", confidence: "98")
    ]
}

#Preview {
    Library()
}
