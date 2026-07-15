//
//  AnimatedBackground.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 7/14/26.
//
//

import SwiftUI

struct AnimatedBackground: View {

    /// Set to true once the intro animation has completed.
    /// Later we'll use this to stop radar effects while
    /// keeping the skyline alive.
    var showLoadingUI: Bool

    @State private var animate = false

    var body: some View {

        TimelineView(.animation) { timeline in

            let t = timeline.date.timeIntervalSinceReferenceDate

            ZStack {

                //---------------------------------------------------------
                // Base Color
                //---------------------------------------------------------

                Color(
                    red: 0.05,
                    green: 0.06,
                    blue: 0.09
                )
                .ignoresSafeArea()

                //---------------------------------------------------------
                // Animated Gradient
                //---------------------------------------------------------

                AnimatedGradient(
                    time: t
                )

                //---------------------------------------------------------
                // Moving Glow Blobs
                //---------------------------------------------------------

                GlowBlob(
                    color: Color.blue.opacity(0.20),
                    size: 360,
                    xOffset: sin(t / 5) * 70,
                    yOffset: -240 + cos(t / 4) * 30
                )

                GlowBlob(
                    color: Color.cyan.opacity(0.10),
                    size: 250,
                    xOffset: -120 + cos(t / 3) * 40,
                    yOffset: 180 + sin(t / 6) * 25
                )

                GlowBlob(
                    color: Color.blue.opacity(0.08),
                    size: 200,
                    xOffset: 150 + sin(t / 7) * 30,
                    yOffset: 220 + cos(t / 5) * 20
                )

                //---------------------------------------------------------
                // Fog
                //---------------------------------------------------------

                FogLayer(time: t)

                //---------------------------------------------------------
                // Skyline
                //---------------------------------------------------------

                VStack {

                    Spacer()

                    Skyline()
                            .fill(
                                LinearGradient(
                                    colors: [
                                        Color.white.opacity(0.08),
                                        Color.white.opacity(0.02)
                                    ],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )

                        LandmarkLights(time: t)

                    }
                    .frame(height: 220)
                
                        .overlay(alignment: .top) {

                            Rectangle()
                                .fill(
                                    LinearGradient(
                                        colors: [
                                            Color.white.opacity(0.12),
                                            .clear
                                        ],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    )
                                )
                                .frame(height: 1)
                        }

                }

            }

        }

    }


struct AnimatedGradient: View {

    let time: Double

    var body: some View {

        RadialGradient(

            colors: [

                Color(
                    red: 0.16,
                    green: 0.34,
                    blue: 0.78
                ).opacity(0.30),

                Color.clear

            ],

            center: UnitPoint(

                x: 0.5 + sin(time / 8) * 0.08,

                y: 0.25 + cos(time / 6) * 0.05

            ),

            startRadius: 50,

            endRadius: 520

        )
        .ignoresSafeArea()

    }

}

struct GlowBlob: View {

    let color: Color
    let size: CGFloat

    let xOffset: Double
    let yOffset: Double

    var body: some View {

        Circle()

            .fill(color)

            .frame(
                width: size,
                height: size
            )

            .blur(radius: 80)

            .offset(
                x: xOffset,
                y: yOffset
            )

    }

}

struct FogLayer: View {

    let time: Double

    var body: some View {

        LinearGradient(

            colors: [

                .clear,

                Color.white.opacity(0.025),

                .clear

            ],

            startPoint: .leading,

            endPoint: .trailing

        )

        .blur(radius: 45)

        .scaleEffect(1.8)

        .offset(

            x: sin(time / 14) * 35,

            y: cos(time / 12) * 18

        )

        .ignoresSafeArea()

    }

}

// MARK: - Skyline

struct Skyline: Shape {

    func path(in rect: CGRect) -> Path {

        var p = Path()

        let h = rect.height
        let w = rect.width

        p.move(to: CGPoint(x: 0, y: h))

        //-------------------------------------------------
        // Left Office
        //-------------------------------------------------

        p.addLine(to: CGPoint(x: 0, y: h * 0.55))
        p.addLine(to: CGPoint(x: w * 0.07, y: h * 0.55))
        p.addLine(to: CGPoint(x: w * 0.07, y: h))

        //-------------------------------------------------
        // Apartment
        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w * 0.15, y: h))
        p.addLine(to: CGPoint(x: w * 0.15, y: h * 0.35))
        p.addLine(to: CGPoint(x: w * 0.22, y: h * 0.35))
        p.addLine(to: CGPoint(x: w * 0.22, y: h))

        //-------------------------------------------------
        // Church

        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w * 0.29, y: h))
        p.addLine(to: CGPoint(x: w * 0.29, y: h * 0.45))

        p.addLine(to: CGPoint(x: w * 0.315, y: h * 0.18))
        p.addLine(to: CGPoint(x: w * 0.34, y: h * 0.45))

        p.addLine(to: CGPoint(x: w * 0.36, y: h * 0.45))
        p.addLine(to: CGPoint(x: w * 0.36, y: h))

        //-------------------------------------------------
        // Mall

        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w * 0.48, y: h))
        p.addLine(to: CGPoint(x: w * 0.48, y: h * 0.62))
        p.addQuadCurve(
            to: CGPoint(x: w * 0.64, y: h * 0.62),
            control: CGPoint(x: w * 0.56, y: h * 0.50)
        )
        p.addLine(to: CGPoint(x: w * 0.64, y: h))

        //-------------------------------------------------
        // Statue

        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w * 0.72, y: h))
        p.addLine(to: CGPoint(x: w * 0.72, y: h * 0.68))

        p.addLine(to: CGPoint(x: w * 0.735, y: h * 0.68))
        p.addLine(to: CGPoint(x: w * 0.735, y: h * 0.50))

        p.addLine(to: CGPoint(x: w * 0.75, y: h * 0.48))

        p.addLine(to: CGPoint(x: w * 0.765, y: h * 0.50))

        p.addLine(to: CGPoint(x: w * 0.765, y: h * 0.68))
        p.addLine(to: CGPoint(x: w * 0.79, y: h * 0.68))
        p.addLine(to: CGPoint(x: w * 0.79, y: h))

        //-------------------------------------------------
        // Tower

        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w * 0.87, y: h))
        p.addLine(to: CGPoint(x: w * 0.87, y: h * 0.28))

        p.addLine(to: CGPoint(x: w * 0.90, y: h * 0.22))

        p.addLine(to: CGPoint(x: w * 0.93, y: h * 0.28))

        p.addLine(to: CGPoint(x: w * 0.93, y: h))

        //-------------------------------------------------

        p.addLine(to: CGPoint(x: w, y: h))
        p.closeSubpath()

        return p
    }
}

struct LandmarkLights: View {

    let time: Double

    var body: some View {

        GeometryReader { geo in

            ZStack {

                landmark(
                    x: geo.size.width * 0.05,
                    y: geo.size.height * 0.58,
                    delay: 0
                )

                landmark(
                    x: geo.size.width * 0.19,
                    y: geo.size.height * 0.37,
                    delay: 0.7
                )

                landmark(
                    x: geo.size.width * 0.32,
                    y: geo.size.height * 0.22,
                    delay: 1.4
                )

                landmark(
                    x: geo.size.width * 0.55,
                    y: geo.size.height * 0.56,
                    delay: 2.2
                )

                landmark(
                    x: geo.size.width * 0.74,
                    y: geo.size.height * 0.50,
                    delay: 3.0
                )

                landmark(
                    x: geo.size.width * 0.90,
                    y: geo.size.height * 0.25,
                    delay: 4.1
                )

            }

        }

    }

    @ViewBuilder
    private func landmark(
        x: CGFloat,
        y: CGFloat,
        delay: Double
    ) -> some View {

        TimelineView(.animation) { timeline in

            let t = timeline.date.timeIntervalSinceReferenceDate

            let alpha = max(
                0,
                sin((t + delay) * 1.6)
            )

            Circle()

                .fill(Color.blue)

                .frame(width: 8, height: 8)

                .shadow(color: .blue, radius: 12)

                .opacity(alpha)

                .position(x: x, y: y)

        }

    }

}
