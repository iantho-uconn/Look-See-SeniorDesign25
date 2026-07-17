//
//  LoadingAnimation.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 7/14/26.
//
import SwiftUI

struct LoadingAnimation: View {

    /// Set to true once the intro finishes.
    /// We'll use this later to stop all repeating animations.
    var animationFinished: Bool = false
    
    
        
    var onFinished: (() -> Void)? = nil

    @State private var logoScale: CGFloat = 0.82
    @State private var glowScale: CGFloat = 0.75
    @State private var glowOpacity: Double = 0
    @State private var breathing = false
    @State private var showParticles = false

    @State private var sweepRotation: Double = -90

    @State private var pulse1 = false
    @State private var pulse2 = false
    @State private var pulse3 = false
    
    

    var body: some View {

        ZStack {

            //------------------------------------------------------
            // Soft blue glow
            //------------------------------------------------------

            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            Color(red: 0.22, green: 0.49, blue: 1.00)
                                .opacity(0.45),
                            .clear
                        ],
                        center: .center,
                        startRadius: 20,
                        endRadius: 170
                    )
                )
                .frame(width: 260, height: 260)
                .scaleEffect(glowScale)
                .opacity(glowOpacity)
                .blur(radius: 30)

            //------------------------------------------------------
            // Radar Pulse #1
            //------------------------------------------------------

            RadarPulse(progress: pulse1 ? 1 : 0)


            //------------------------------------------------------
            // Radar Pulse #2
            //------------------------------------------------------

            RadarPulse(progress: pulse2 ? 1 : 0)


            //------------------------------------------------------
            // Radar Pulse #3
            //------------------------------------------------------

            RadarPulse(progress: pulse3 ? 1 : 0)


            //------------------------------------------------------
            // Rotating Sweep
            //------------------------------------------------------

            RadarSweep()

                .rotationEffect(.degrees(sweepRotation))
            //------------------------------------------------------
            // Logo
            //------------------------------------------------------

            Image("LookSee_Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 340, height: 290)
                .scaleEffect(logoScale)
                .shadow(
                    color: Color.blue.opacity(0.35),
                    radius: 30
                )
                .scaleEffect(
                    breathing && !animationFinished ? 1.02 : 1.0
                )

            //------------------------------------------------------
            // Part 3
            // Floating particles
            //------------------------------------------------------
            FloatingParticles(active: showParticles)
        }
        .frame(width: 380, height: 380)
        .onAppear {

            //--------------------------------------------------
            // Intro
            //--------------------------------------------------

            withAnimation(
                .spring(
                    response: 0.9,
                    dampingFraction: 0.72
                )
            ) {
                logoScale = 1
            }

            withAnimation(.easeOut(duration: 1.2)) {

                glowOpacity = 1

                glowScale = 1

            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) {

                withAnimation(.easeInOut(duration: 0.6)) {

                    glowOpacity = 0.8

                }

            }
            //--------------------------------------------------
            // Continuous breathing
            //--------------------------------------------------

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {

                withAnimation(
                    .easeInOut(duration: 2.0)
                    .repeatForever(autoreverses: true)
                ) {

                    breathing = true

                }

            }
            
            //--------------------------------------------------
            // Radar Sweep
            //--------------------------------------------------

            withAnimation(

                .linear(duration: 2.6)

            ) {

                sweepRotation = 270

            }


            //--------------------------------------------------
            // Radar Rings
            //--------------------------------------------------

            withAnimation(

                .easeOut(duration: 2.4)

            ) {

                pulse1 = true

            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {

                withAnimation(.easeOut(duration: 2.1)) {

                    pulse2 = true

                }

            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {

                withAnimation(.easeOut(duration: 1.8)) {

                    pulse3 = true

                }

            }
            //--------------------------------------------------
            // Particles
            //--------------------------------------------------

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {

                showParticles = true

            }


            //--------------------------------------------------
            // Finish Intro
            //--------------------------------------------------

            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                
                onFinished?()
            }

        }

    }

}



struct RadarPulse: View {

    var progress: CGFloat

    var body: some View {

        Circle()

            .stroke(

                Color(

                    red: 0.22,

                    green: 0.49,

                    blue: 1

                )

                .opacity(0.45),

                lineWidth: 2

            )

            .frame(width: 260, height: 260)

            .scaleEffect(0.2 + progress * 1.2)

            .opacity(1 - progress)

            .blur(radius: progress * 2)

    }

}

struct RadarSweep: View {

    var body: some View {

        Circle()

            .trim(

                from: 0,

                to: 0.14

            )

            .stroke(

                AngularGradient(

                    colors: [

                        .clear,

                        Color.blue.opacity(0.15),

                        Color.blue.opacity(0.95)

                    ],

                    center: .center

                ),

                style: StrokeStyle(

                    lineWidth: 5,

                    lineCap: .round

                )

            )

            .frame(

                width: 280,

                height: 280

            )

            .blur(radius: 1)

    }

}


struct FloatingParticles: View {

    var active: Bool

    var body: some View {

        ZStack {

            ForEach(0..<18, id: \.self) { i in

                Particle(index: i, active: active)

            }

        }

        .frame(width: 360, height: 360)

    }

}

struct Particle: View {

    let index: Int
    let active: Bool

    @State private var move = false

    private var angle: Double {

        Double(index) * 20

    }

    private var radius: CGFloat {

        CGFloat.random(in: 95...165)

    }

    var body: some View {

        Circle()

            .fill(Color.blue)

            .frame(width: 5, height: 5)

            .shadow(color: .blue, radius: 8)

            .offset(

                x: move ? cos(angle * .pi / 180) * radius : 0,

                y: move ? sin(angle * .pi / 180) * radius : 0

            )

            .opacity(move ? 0 : 1)

            .animation(

                .easeOut(duration: 2.5)

                .delay(Double(index) * 0.08),

                value: move

            )

            .onAppear {

                if active {

                    move = true

                }

            }

            .onChange(of: active) { _, value in

                if value {

                    move = true

                }

            }

    }

}
#Preview {

    ZStack {

        Color(red: 0.05, green: 0.06, blue: 0.09)
            .ignoresSafeArea()

        LoadingAnimation()

    }

}
