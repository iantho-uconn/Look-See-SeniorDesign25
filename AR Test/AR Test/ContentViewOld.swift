//
//  ContentView.swift
//  AR Test
//
//  Created by Christian Barbara on 3/25/26.
//

import SwiftUI
import ARKit
import RealityKit

struct ContentViewOld : View {
    var body: some View {
        
        RealityView { content in
            var landmarkName: AttributedString {
                
                var landmarkName = AttributedString("Lorem Ispum")
                var container = AttributeContainer()
                container.font = .largeTitle
                landmarkName.mergeAttributes(container)
                return landmarkName
            }
            
            // Text component
            var textPlane = TextComponent()
            textPlane.text = "The Action Command"
//            textPlane.text = landmarkName
            textPlane.backgroundColor = CGColor(red: 255, green: 0, blue: 0, alpha: 1)
            textPlane.size = CGSize(width: 1000, height: 1000)

            // Create a cube model
            let model = Entity()
            let mesh = MeshResource.generatePlane(width: 0.5, depth: 0.5, cornerRadius: 0.05)
            let material = SimpleMaterial(color: .green, roughness: 0.05, isMetallic: false)
            model.components.set(ModelComponent(mesh: mesh, materials: [material]))
            model.position = [0, 0.5, 0]
            
            let model2 = Entity()
            let mesh2 = MeshResource.generateCone(height: 1, radius: 2)
            let material2 = SimpleMaterial(color: .blue, roughness: 0.55, isMetallic: true)
            model2.components.set(ModelComponent(mesh: mesh2, materials: [material2]))
            model2.position = [0.3, 0.4, 0]
            
            let model3 = Entity()
//            model3.components.set(ModelComponent(mesh: mesh2, materials: [material2]))
            model3.position = [0.5, 0.5, 0.5]
            model3.components.set(textPlane) // Will it work?

            // Create horizontal plane anchor for the content
            let anchor = AnchorEntity(.plane(.vertical, classification: .any, minimumBounds: SIMD2<Float>(0.2, 0.2)))
            anchor.addChild(model)
//            anchor.addChild(model2)
            anchor.addChild(model3)
            

            // Add the horizontal plane anchor to the scene
            content.add(anchor)

            content.camera = .spatialTracking

        }
        .edgesIgnoringSafeArea(.all)
    }
}

//#Preview {
//    ContentView()
//}
