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
                                        Color.black.opacity(0.45),
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

import SwiftUI

// MARK: - Building Definition

enum BuildingStyle {
    case glassOffice
    case plainOffice
    case brick
    case apartmentAC
    case storefront
    case church
    case mall
    case tower
    case statue
}

struct BuildingSegment {
    /// Relative width — segments are auto-normalized to fill the full screen width.
    let widthFraction: CGFloat
    /// Height of the rectangular "body" as a fraction of total height (0...1).
    let bodyHeightFraction: CGFloat
    /// Extra height for a silhouette topper (spire/dome/antenna/statue), as a
    /// fraction of total height. Use 0 for flat-roofed buildings.
    let topFeatureHeightFraction: CGFloat
    let style: BuildingStyle

    init(
        widthFraction: CGFloat,
        bodyHeightFraction: CGFloat,
        topFeatureHeightFraction: CGFloat = 0,
        style: BuildingStyle
    ) {
        self.widthFraction = widthFraction
        self.bodyHeightFraction = bodyHeightFraction
        self.topFeatureHeightFraction = topFeatureHeightFraction
        self.style = style
    }
}

/// A default preset with varied widths/heights — tweak freely, add more
/// entries, or generate your own array. Nothing here needs to sum to 1;
/// widths are normalized automatically to fill the screen.
let defaultSkylineSegments: [BuildingSegment] = [
    BuildingSegment(widthFraction: 0.9, bodyHeightFraction: 0.42, style: .plainOffice),
    BuildingSegment(widthFraction: 0.6, bodyHeightFraction: 0.30, style: .apartmentAC),
    BuildingSegment(widthFraction: 0.55, bodyHeightFraction: 0.62, style: .brick),
    BuildingSegment(widthFraction: 0.7, bodyHeightFraction: 0.24, style: .storefront),
    BuildingSegment(widthFraction: 0.5, bodyHeightFraction: 0.50, topFeatureHeightFraction: 0.18, style: .church),
    BuildingSegment(widthFraction: 0.65, bodyHeightFraction: 0.34, style: .apartmentAC),
    BuildingSegment(widthFraction: 1.1, bodyHeightFraction: 0.72, style: .glassOffice),
    BuildingSegment(widthFraction: 0.55, bodyHeightFraction: 0.40, topFeatureHeightFraction: 0.14, style: .mall),
    BuildingSegment(widthFraction: 0.45, bodyHeightFraction: 0.55, style: .brick),
    BuildingSegment(widthFraction: 0.35, bodyHeightFraction: 0.46, topFeatureHeightFraction: 0.22, style: .statue),
    BuildingSegment(widthFraction: 0.6, bodyHeightFraction: 0.28, style: .storefront),
    BuildingSegment(widthFraction: 0.5, bodyHeightFraction: 0.58, style: .apartmentAC),
    BuildingSegment(widthFraction: 0.5, bodyHeightFraction: 0.35, topFeatureHeightFraction: 0.30, style: .tower),
    BuildingSegment(widthFraction: 0.75, bodyHeightFraction: 0.48, style: .plainOffice),
    BuildingSegment(widthFraction: 0.4, bodyHeightFraction: 0.66, style: .glassOffice),
]

// MARK: - Layout Helper (shared by silhouette + decoration so they always align)

enum SkylineLayout {
    static func bodyFrames(
        for segments: [BuildingSegment],
        in size: CGSize
    ) -> [(segment: BuildingSegment, frame: CGRect)] {
        let totalWidth = segments.reduce(0) { $0 + $1.widthFraction }
        guard totalWidth > 0 else { return [] }

        var x: CGFloat = 0
        var result: [(BuildingSegment, CGRect)] = []

        for segment in segments {
            let segWidth = size.width * (segment.widthFraction / totalWidth)
            let bodyTopY = size.height * (1 - segment.bodyHeightFraction)
            let frame = CGRect(
                x: x,
                y: bodyTopY,
                width: segWidth,
                height: size.height - bodyTopY
            )
            result.append((segment, frame))
            x += segWidth
        }

        return result
    }
}

// MARK: - Skyline Silhouette

struct Skyline: Shape {
    var segments: [BuildingSegment] = defaultSkylineSegments

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let h = rect.height
        let w = rect.width
        let totalWidth = segments.reduce(0) { $0 + $1.widthFraction }
        guard totalWidth > 0 else { return p }

        var x: CGFloat = 0
        p.move(to: CGPoint(x: 0, y: h))

        for segment in segments {
            let segWidth = w * (segment.widthFraction / totalWidth)
            let x0 = x
            let x1 = x + segWidth
            let bodyTopY = h * (1 - segment.bodyHeightFraction)
            let featureHeight = h * segment.topFeatureHeightFraction

            // Rise to the roofline.
            p.addLine(to: CGPoint(x: x0, y: bodyTopY))

            switch segment.style {
            case .church:
                let midX = (x0 + x1) / 2
                let spireBaseHalf = segWidth * 0.06
                p.addLine(to: CGPoint(x: midX - spireBaseHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: midX, y: bodyTopY - featureHeight))
                p.addLine(to: CGPoint(x: midX + spireBaseHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: x1, y: bodyTopY))

            case .mall:
                p.addQuadCurve(
                    to: CGPoint(x: x1, y: bodyTopY),
                    control: CGPoint(x: (x0 + x1) / 2, y: bodyTopY - featureHeight)
                )

            case .tower:
                let midX = (x0 + x1) / 2
                let capHalf = segWidth * 0.10
                p.addLine(to: CGPoint(x: midX - capHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: midX, y: bodyTopY - featureHeight * 0.7))
                // thin antenna
                p.addLine(to: CGPoint(x: midX, y: bodyTopY - featureHeight))
                p.addLine(to: CGPoint(x: midX, y: bodyTopY - featureHeight * 0.7))
                p.addLine(to: CGPoint(x: midX + capHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: x1, y: bodyTopY))

            case .statue:
                let midX = (x0 + x1) / 2
                let armHalf = segWidth * 0.16
                p.addLine(to: CGPoint(x: midX - armHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: midX - armHalf, y: bodyTopY - featureHeight * 0.55))
                p.addLine(to: CGPoint(x: midX, y: bodyTopY - featureHeight))
                p.addLine(to: CGPoint(x: midX + armHalf, y: bodyTopY - featureHeight * 0.55))
                p.addLine(to: CGPoint(x: midX + armHalf, y: bodyTopY))
                p.addLine(to: CGPoint(x: x1, y: bodyTopY))

            default:
                // Flat-roofed buildings (office, brick, apartment, storefront).
                p.addLine(to: CGPoint(x: x1, y: bodyTopY))
            }

            // Drop back to street level, ready for the next building.
            p.addLine(to: CGPoint(x: x1, y: h))
            x = x1
        }

        p.addLine(to: CGPoint(x: w, y: h))
        p.closeSubpath()
        return p
    }
}

// MARK: - Decoration (windows, brick, AC units)

struct SkylineDetails: View {
    var segments: [BuildingSegment] = defaultSkylineSegments

    var body: some View {
        Canvas { context, size in
            let frames = SkylineLayout.bodyFrames(for: segments, in: size)

            for (segment, frame) in frames {
                switch segment.style {
                case .glassOffice:
                    drawWindowGrid(
                        in: context, frame: frame,
                        rows: 16, cols: 4, inset: 2.5,
                        litProbability: 0.30,
                        color: Color.white.opacity(0.55)
                    )

                case .plainOffice:
                    drawWindowGrid(
                        in: context, frame: frame,
                        rows: 10, cols: 3, inset: 4,
                        litProbability: 0.25,
                        color: Color.white.opacity(0.45)
                    )

                case .brick:
                    drawBrickTexture(in: context, frame: frame)
                    drawWindowGrid(
                        in: context, frame: frame,
                        rows: 6, cols: 2, inset: 6,
                        litProbability: 0.4,
                        color: Color(red: 0.95, green: 0.82, blue: 0.55)
                    )

                case .apartmentAC:
                    drawWindowGrid(
                        in: context, frame: frame,
                        rows: 8, cols: 3, inset: 4,
                        litProbability: 0.35,
                        color: Color(red: 0.95, green: 0.85, blue: 0.6)
                    )
                    drawACUnits(in: context, frame: frame)

                case .storefront:
                    drawStorefront(in: context, frame: frame)

                case .mall:
                    drawWindowGrid(
                        in: context, frame: frame,
                        rows: 3, cols: 8, inset: 5,
                        litProbability: 0.5,
                        color: Color.white.opacity(0.35)
                    )

                case .church, .statue, .tower:
                    break // keep these silhouettes clean
                }
            }
        }
        .allowsHitTesting(false)
    }

    // MARK: Window grid

    private func drawWindowGrid(
        in context: GraphicsContext,
        frame: CGRect,
        rows: Int,
        cols: Int,
        inset: CGFloat,
        litProbability: Double,
        color: Color
    ) {
        guard frame.height > 20, frame.width > 10, rows > 0, cols > 0 else { return }

        let cellW = frame.width / CGFloat(cols)
        let cellH = frame.height / CGFloat(rows)
        let winW = max(1, cellW - inset)
        let winH = max(1, cellH - inset)

        for row in 0..<rows {
            for col in 0..<cols {
                // Deterministic "randomness" so windows don't flicker between redraws.
                let seed = Int(frame.origin.x) &* 97 &+ row &* 13 &+ col &* 7
                let pseudoRandom = Double(abs(seed) % 100) / 100.0
                guard pseudoRandom < litProbability else { continue }

                let x = frame.minX + CGFloat(col) * cellW + inset / 2
                let y = frame.minY + CGFloat(row) * cellH + inset / 2
                let rect = CGRect(x: x, y: y, width: winW, height: winH)

                context.fill(Path(rect), with: .color(color))
            }
        }
    }

    // MARK: Brick texture

    private func drawBrickTexture(in context: GraphicsContext, frame: CGRect) {
        let brickH: CGFloat = 8
        let brickW: CGFloat = 18
        let mortar = Color.black.opacity(0.25)

        var row = 0
        var y = frame.minY

        while y < frame.maxY {
            let offset: CGFloat = (row % 2 == 0) ? 0 : -brickW / 2
            var x = frame.minX + offset

            while x < frame.maxX {
                let brickRect = CGRect(
                    x: x, y: y,
                    width: min(brickW - 1, frame.maxX - x),
                    height: min(brickH - 1, frame.maxY - y)
                )
                if brickRect.width > 0, brickRect.height > 0 {
                    context.fill(
                        Path(brickRect),
                        with: .color(Color(red: 0.55, green: 0.27, blue: 0.20))
                    )
                }
                x += brickW
            }

            // mortar line under each row
            context.fill(
                Path(CGRect(x: frame.minX, y: y + brickH - 1, width: frame.width, height: 1)),
                with: .color(mortar)
            )

            y += brickH
            row += 1
        }
    }

    // MARK: AC units (rooftop boxes for older apartment buildings)

    private func drawACUnits(in context: GraphicsContext, frame: CGRect) {
        let unitCount = max(1, Int(frame.width / 22))
        let unitW: CGFloat = 12
        let unitH: CGFloat = 7
        let spacing = frame.width / CGFloat(unitCount)

        for i in 0..<unitCount {
            let seed = Int(frame.origin.x) &* 31 &+ i &* 11
            guard abs(seed) % 100 < 70 else { continue } // not every slot gets a unit

            let cx = frame.minX + spacing * (CGFloat(i) + 0.5)
            let rect = CGRect(
                x: cx - unitW / 2,
                y: frame.minY - unitH,
                width: unitW,
                height: unitH
            )

            context.fill(
                Path(roundedRect: rect, cornerRadius: 1.5),
                with: .color(Color(white: 0.35))
            )
            // small vent lines
            for lineIndex in 0..<3 {
                let lineY = rect.minY + rect.height * (CGFloat(lineIndex) + 0.5) / 3
                context.stroke(
                    Path { path in
                        path.move(to: CGPoint(x: rect.minX + 2, y: lineY))
                        path.addLine(to: CGPoint(x: rect.maxX - 2, y: lineY))
                    },
                    with: .color(Color(white: 0.2)),
                    lineWidth: 0.5
                )
            }
        }
    }

    // MARK: Storefront (ground-floor glass + awning)

    private func drawStorefront(in context: GraphicsContext, frame: CGRect) {
        let awningH: CGFloat = min(6, frame.height * 0.15)
        let awningRect = CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: awningH)

        // striped awning
        let stripeCount = max(3, Int(frame.width / 10))
        let stripeW = frame.width / CGFloat(stripeCount)
        for i in 0..<stripeCount {
            let stripeRect = CGRect(
                x: frame.minX + CGFloat(i) * stripeW,
                y: awningRect.minY,
                width: stripeW,
                height: awningH
            )
            let color = (i % 2 == 0)
                ? Color(red: 0.85, green: 0.25, blue: 0.25)
                : Color.white.opacity(0.85)
            context.fill(Path(stripeRect), with: .color(color))
        }

        // glass display window below the awning
        let glassRect = CGRect(
            x: frame.minX + 2,
            y: awningRect.maxY + 2,
            width: max(0, frame.width - 4),
            height: max(0, frame.height - awningH - 4)
        )
        if glassRect.width > 0, glassRect.height > 0 {
            context.fill(
                Path(glassRect),
                with: .color(Color(red: 0.95, green: 0.85, blue: 0.5).opacity(0.55))
            )
        }
    }
}

// MARK: - Composed convenience view

struct SkylineView: View {
    var segments: [BuildingSegment] = defaultSkylineSegments
    var silhouetteColor: Color = Color(red: 0.03, green: 0.03, blue: 0.06)

    var body: some View {
        ZStack {
            Skyline(segments: segments)
                .fill(silhouetteColor)

            SkylineDetails(segments: segments)
                .clipShape(Skyline(segments: segments))
        }
    }
}

#Preview {
    ZStack {
        Color(red: 0.06, green: 0.06, blue: 0.10).ignoresSafeArea()
        VStack {
            Spacer()
            SkylineView()
                .frame(height: 220)
        }
        .ignoresSafeArea(edges: .bottom)
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
