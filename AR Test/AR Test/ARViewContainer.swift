//
//  ARViewContainer.swift
//  AR Test
//
//  Created by Christian Barbara on 4/6/26.
//

import SwiftUI
import RealityKit
import ARKit

struct ARViewContainer : UIViewRepresentable {
//    @ObservedObject var infoView = Settings.shared
    
    // Instantiate and return a Coordinator object
    func makeCoordinator() -> Coordinator {
        return Coordinator()
    }
    
    func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.debugOptions = [.showPhysics]
        //, .showAnchorOrigins, .showWorldOrigin, .showAnchorGeometry, .showSceneUnderstanding
        
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.vertical, .horizontal]
        config.environmentTexturing = .automatic
        arView.session.run(config)
        
        arView.addCoaching()
        
        return arView
    }
    
    func updateUIView(_ uiView: ARView, context: Context) {
        let anchorEntity = AnchorEntity(plane: .any)
        
        // Text for TextComponent
        var landmarkName: AttributedString {
            var landmarkName = AttributedString("Metropolitan Life Insurance Company Tower")
//            var landmarkName = AttributedString("The Pitt")
//            var landmarkName = AttributedString("Jonathan Statue")
            var container = AttributeContainer()
            container.font = .systemFont(ofSize: 100)
            landmarkName.mergeAttributes(container)
            return landmarkName
        }
        
        // Convert to allow for size parameter
        let NSString = NSAttributedString(landmarkName)
        
        // Text component
        var textPlane = TextComponent()
        textPlane.text = landmarkName
        textPlane.cornerRadius = 100
        textPlane.backgroundColor = CGColor(red: 0, green: 155, blue: 255, alpha: 0.75)
        
        // Dynamic plane size
        if(NSString.length >= 27){textPlane.size = CGSize(width: NSString.size().width/2, height: NSString.size().height * 4)}
        else{textPlane.size = CGSize(width: NSString.size().width + 100, height: NSString.size().height + 40)}
        
        // Padding
        textPlane.edgeInsets = UIEdgeInsets(top: 20, left: 50, bottom: 20, right: 50)
        
        context.coordinator.view = uiView
        
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapGestureAction(_:)))
        
        // Add text component
        let model = Entity()
        
        anchorEntity.addChild(model)
        
        model.position = [0, 0, 0]
        model.components.set(BillboardComponent())
        model.components.set(textPlane)
        model.components.set(InputTargetComponent())
        
        model.generateCollisionShapes(recursive: true)
        
        uiView.addGestureRecognizer(tap)
        model.components.set(InputTargetComponent())
        model.components.set(CollisionComponent(shapes: [ShapeResource.generateBox(size: SIMD3<Float>(0.001, 0.001, 0.001))], mode: CollisionComponent.Mode.trigger))
        model.components.set(HoverEffectComponent())
        
        
         uiView.scene.addAnchor(anchorEntity)
    }
    
//     Class to handle tap gesture
    class Coordinator: NSObject {
        var view: ARView?
        @ObservedObject var infoView = Settings.shared
        
        @objc
        func tapGestureAction(_ recognizer: UITapGestureRecognizer? = nil){
            // Ensure there is a view
            guard let view = self.view else {return}
            
            // Tap location
            let tapLocation = recognizer!.location(in: view)
            
            // Model at tap location
            if view.entity(at: tapLocation) != nil{
                print("Tupped!")
                infoView.infoView.toggle()
            }
            else{print("Nah!")}
        }
    }
}


