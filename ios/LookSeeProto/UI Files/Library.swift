//
//  Library.swift
//  LookSeeProto
//
//  Created by Christian Barbara on 10/21/25.
//
import Combine
import SwiftUI

struct Library: View {
    
    @State private var searchText = ""
    var locations: [Landmark]
    var body: some View {
        VStack{
//            HStack{
//                Button("Library & History", systemImage:"arrow.backward"){
//                }
//                .padding()
//                Spacer()
//            }
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
    var filter: [Landmark]{
        if searchText.isEmpty{
            return locations
        }
        else{
            return locations.filter{$0.name.localizedCaseInsensitiveContains(searchText)}
        }
    }
}

#Preview {
    Library(locations: landmarks)
}
