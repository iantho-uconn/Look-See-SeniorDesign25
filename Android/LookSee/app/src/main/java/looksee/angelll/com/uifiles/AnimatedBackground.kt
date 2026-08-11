package looksee.angelll.com.uifiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnimatedBackground(showLoadingUI: Boolean = false) {
    // 1. TimelineView Equivalent (Continuous precise clock)
    val timeMillis by produceState(0L) {
        while (true) {
            withFrameMillis { value = it }
        }
    }
    val t = timeMillis / 1000.0

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0F17))) { // Base color

        AnimatedGradient(time = t)

        GlowBlob(color = Color.Blue.copy(alpha = 0.20f), size = 360f, xOffset = (sin(t / 5) * 70).toFloat(), yOffset = (-240 + cos(t / 4) * 30).toFloat())
        GlowBlob(color = Color.Cyan.copy(alpha = 0.10f), size = 250f, xOffset = (-120 + cos(t / 3) * 40).toFloat(), yOffset = (180 + sin(t / 6) * 25).toFloat())
        GlowBlob(color = Color.Blue.copy(alpha = 0.08f), size = 200f, xOffset = (150 + sin(t / 7) * 30).toFloat(), yOffset = (220 + cos(t / 5) * 20).toFloat())

        FogLayer(time = t)

        // Skyline
        Box(modifier = Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)) {
            SkylineView()
            LandmarkLights(time = t)

            // Top highlight overlay
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent)))
            )
        }
    }
}

@Composable
fun AnimatedGradient(time: Double) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * (0.5f + sin(time / 8).toFloat() * 0.08f)
        val cy = size.height * (0.25f + cos(time / 6).toFloat() * 0.05f)

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF2956C7).copy(alpha = 0.30f), Color.Transparent),
                center = Offset(cx, cy),
                radius = 520f.dp.toPx()
            )
        )
    }
}

@Composable
fun GlowBlob(color: Color, size: Float, xOffset: Float, yOffset: Float) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .offset(x = xOffset.dp, y = yOffset.dp)
                .size(size.dp)
                .blur(80.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun FogLayer(time: Double) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(1.8f)
            .offset(x = (sin(time / 14) * 35).toFloat().dp, y = (cos(time / 12) * 18).toFloat().dp)
            .blur(45.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.025f), Color.Transparent)
                )
            )
    )
}

// MARK: - Skyline Definition
enum class BuildingStyle { GlassOffice, PlainOffice, Brick, ApartmentAC, Storefront, Church, Mall, Tower, Statue }

data class BuildingSegment(
    val widthFraction: Float,
    val bodyHeightFraction: Float,
    val topFeatureHeightFraction: Float = 0f,
    val style: BuildingStyle
)

val defaultSkylineSegments = listOf(
    BuildingSegment(0.9f, 0.42f, 0f, BuildingStyle.PlainOffice),
    BuildingSegment(0.6f, 0.30f, 0f, BuildingStyle.ApartmentAC),
    BuildingSegment(0.55f, 0.62f, 0f, BuildingStyle.Brick),
    BuildingSegment(0.7f, 0.24f, 0f, BuildingStyle.Storefront),
    BuildingSegment(0.5f, 0.50f, 0.18f, BuildingStyle.Church),
    BuildingSegment(0.65f, 0.34f, 0f, BuildingStyle.ApartmentAC),
    BuildingSegment(1.1f, 0.72f, 0f, BuildingStyle.GlassOffice),
    BuildingSegment(0.55f, 0.40f, 0.14f, BuildingStyle.Mall),
    BuildingSegment(0.45f, 0.55f, 0f, BuildingStyle.Brick),
    BuildingSegment(0.35f, 0.46f, 0.22f, BuildingStyle.Statue),
    BuildingSegment(0.6f, 0.28f, 0f, BuildingStyle.Storefront),
    BuildingSegment(0.5f, 0.58f, 0f, BuildingStyle.ApartmentAC),
    BuildingSegment(0.5f, 0.35f, 0.30f, BuildingStyle.Tower),
    BuildingSegment(0.75f, 0.48f, 0f, BuildingStyle.PlainOffice),
    BuildingSegment(0.4f, 0.66f, 0f, BuildingStyle.GlassOffice)
)

object SkylineLayout {
    fun bodyFrames(segments: List<BuildingSegment>, size: Size): List<Pair<BuildingSegment, Rect>> {
        val totalWidth = segments.map { it.widthFraction }.sum()
        if (totalWidth <= 0f) return emptyList()

        var x = 0f
        val result = mutableListOf<Pair<BuildingSegment, Rect>>()

        for (segment in segments) {
            val segWidth = size.width * (segment.widthFraction / totalWidth)
            val bodyTopY = size.height * (1f - segment.bodyHeightFraction)
            val rect = Rect(left = x, top = bodyTopY, right = x + segWidth, bottom = size.height)
            result.add(Pair(segment, rect))
            x += segWidth
        }
        return result
    }
}

fun buildSkylinePath(segments: List<BuildingSegment>, size: Size): Path {
    val p = Path()
    val h = size.height
    val w = size.width
    val totalWidth = segments.map { it.widthFraction }.sum()
    if (totalWidth <= 0f) return p

    var x = 0f
    p.moveTo(0f, h)

    for (segment in segments) {
        val segWidth = w * (segment.widthFraction / totalWidth)
        val x0 = x
        val x1 = x + segWidth
        val bodyTopY = h * (1f - segment.bodyHeightFraction)
        val featureHeight = h * segment.topFeatureHeightFraction

        p.lineTo(x0, bodyTopY)

        when (segment.style) {
            BuildingStyle.Church -> {
                val midX = (x0 + x1) / 2
                val spireBaseHalf = segWidth * 0.06f
                p.lineTo(midX - spireBaseHalf, bodyTopY)
                p.lineTo(midX, bodyTopY - featureHeight)
                p.lineTo(midX + spireBaseHalf, bodyTopY)
                p.lineTo(x1, bodyTopY)
            }
            BuildingStyle.Mall -> {
                p.quadraticTo((x0 + x1) / 2, bodyTopY - featureHeight, x1, bodyTopY) // 🚀 FIXED: Deprecated quadraticBezierTo
            }
            BuildingStyle.Tower -> {
                val midX = (x0 + x1) / 2
                val capHalf = segWidth * 0.10f
                p.lineTo(midX - capHalf, bodyTopY)
                p.lineTo(midX, bodyTopY - featureHeight * 0.7f)
                p.lineTo(midX, bodyTopY - featureHeight)
                p.lineTo(midX, bodyTopY - featureHeight * 0.7f)
                p.lineTo(midX + capHalf, bodyTopY)
                p.lineTo(x1, bodyTopY)
            }
            BuildingStyle.Statue -> {
                val midX = (x0 + x1) / 2
                val armHalf = segWidth * 0.16f
                p.lineTo(midX - armHalf, bodyTopY)
                p.lineTo(midX - armHalf, bodyTopY - featureHeight * 0.55f)
                p.lineTo(midX, bodyTopY - featureHeight)
                p.lineTo(midX + armHalf, bodyTopY - featureHeight * 0.55f)
                p.lineTo(midX + armHalf, bodyTopY)
                p.lineTo(x1, bodyTopY)
            }
            else -> {
                p.lineTo(x1, bodyTopY)
            }
        }
        p.lineTo(x1, h)
        x = x1
    }
    p.lineTo(w, h)
    p.close()
    return p
}

@Composable
fun SkylineView() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val skylinePath = buildSkylinePath(defaultSkylineSegments, size)

        // 1. Draw solid silhouette
        drawPath(
            path = skylinePath,
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.45f), Color(0xFF08080F))
            )
        )

        // 2. Draw details clipped to silhouette
        val frames = SkylineLayout.bodyFrames(defaultSkylineSegments, size)
        clipPath(skylinePath) {
            for ((segment, frame) in frames) {
                when (segment.style) {
                    BuildingStyle.GlassOffice -> drawWindowGrid(frame, 16, 4, 2.5f, 0.30, Color.White.copy(alpha = 0.55f))
                    BuildingStyle.PlainOffice -> drawWindowGrid(frame, 10, 3, 4f, 0.25, Color.White.copy(alpha = 0.45f))
                    BuildingStyle.Brick -> {
                        drawBrickTexture(frame)
                        drawWindowGrid(frame, 6, 2, 6f, 0.40, Color(0xFFF2D18C))
                    }
                    BuildingStyle.ApartmentAC -> {
                        drawWindowGrid(frame, 8, 3, 4f, 0.35, Color(0xFFF2D999))
                        drawACUnits(frame)
                    }
                    BuildingStyle.Storefront -> drawStorefront(frame)
                    BuildingStyle.Mall -> drawWindowGrid(frame, 3, 8, 5f, 0.50, Color.White.copy(alpha = 0.35f))
                    else -> {}
                }
            }
        }
    }
}

// MARK: DrawScope Extensions for Canvas Detailing
fun DrawScope.drawWindowGrid(frame: Rect, rows: Int, cols: Int, inset: Float, litProbability: Double, color: Color) {
    if (frame.height < 20f || frame.width < 10f) return
    val cellW = frame.width / cols
    val cellH = frame.height / rows
    val winW = maxOf(1f, cellW - inset)
    val winH = maxOf(1f, cellH - inset)

    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val seed = frame.left.toInt() * 97 + row * 13 + col * 7
            val pseudoRandom = (abs(seed) % 100) / 100.0
            if (pseudoRandom < litProbability) {
                val x = frame.left + (col * cellW) + (inset / 2)
                val y = frame.top + (row * cellH) + (inset / 2)
                drawRect(color = color, topLeft = Offset(x, y), size = Size(winW, winH))
            }
        }
    }
}

fun DrawScope.drawBrickTexture(frame: Rect) {
    val brickH = 8f
    val brickW = 18f
    val mortar = Color.Black.copy(alpha = 0.25f)
    var row = 0
    var y = frame.top

    while (y < frame.bottom) {
        val offset = if (row % 2 == 0) 0f else -brickW / 2
        var x = frame.left + offset
        while (x < frame.right) {
            val w = minOf(brickW - 1f, frame.right - x)
            val h = minOf(brickH - 1f, frame.bottom - y)
            if (w > 0 && h > 0) {
                drawRect(color = Color(0xFF8C4533), topLeft = Offset(x, y), size = Size(w, h))
            }
            x += brickW
        }
        drawRect(color = mortar, topLeft = Offset(frame.left, y + brickH - 1), size = Size(frame.width, 1f))
        y += brickH
        row += 1
    }
}

fun DrawScope.drawACUnits(frame: Rect) {
    val unitCount = maxOf(1, (frame.width / 22f).toInt())
    val unitW = 12f
    val unitH = 7f
    val spacing = frame.width / unitCount

    for (i in 0 until unitCount) {
        val seed = frame.left.toInt() * 31 + i * 11
        if (abs(seed) % 100 >= 70) continue

        val cx = frame.left + spacing * (i + 0.5f)
        val rectTopLeft = Offset(cx - unitW / 2, frame.top - unitH)
        val rectSize = Size(unitW, unitH)

        drawRoundRect(color = Color.DarkGray, topLeft = rectTopLeft, size = rectSize, cornerRadius = CornerRadius(1.5f, 1.5f))

        for (lineIndex in 0..2) {
            val lineY = rectTopLeft.y + rectSize.height * (lineIndex + 0.5f) / 3f
            drawLine(color = Color(0xFF333333), start = Offset(rectTopLeft.x + 2f, lineY), end = Offset(rectTopLeft.x + rectSize.width - 2f, lineY), strokeWidth = 0.5f)
        }
    }
}

fun DrawScope.drawStorefront(frame: Rect) {
    val awningH = minOf(6f, frame.height * 0.15f)
    val stripeCount = maxOf(3, (frame.width / 10f).toInt())
    val stripeW = frame.width / stripeCount

    for (i in 0 until stripeCount) {
        val color = if (i % 2 == 0) Color(0xFFD94040) else Color.White.copy(alpha = 0.85f) // 🚀 FIXED: Alpha Channel Hex
        drawRect(color = color, topLeft = Offset(frame.left + i * stripeW, frame.top), size = Size(stripeW, awningH))
    }

    val glassW = maxOf(0f, frame.width - 4f)
    val glassH = maxOf(0f, frame.height - awningH - 4f)
    if (glassW > 0 && glassH > 0) {
        drawRect(color = Color(0xFFF2D980).copy(alpha = 0.55f), topLeft = Offset(frame.left + 2f, frame.top + awningH + 2f), size = Size(glassW, glassH))
    }
}

@Composable
fun LandmarkLights(time: Double) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value

        LandmarkDot(x = w * 0.05f, y = h * 0.58f, delay = 0.0, time = time)
        LandmarkDot(x = w * 0.19f, y = h * 0.37f, delay = 0.7, time = time)
        LandmarkDot(x = w * 0.32f, y = h * 0.22f, delay = 1.4, time = time)
        LandmarkDot(x = w * 0.55f, y = h * 0.56f, delay = 2.2, time = time)
        LandmarkDot(x = w * 0.74f, y = h * 0.50f, delay = 3.0, time = time)
        LandmarkDot(x = w * 0.90f, y = h * 0.25f, delay = 4.1, time = time)
    }
}

@Composable
fun LandmarkDot(x: Float, y: Float, delay: Double, time: Double) {
    val alpha = maxOf(0.0, sin((time + delay) * 1.6)).toFloat()
    Box(
        modifier = Modifier
            .offset(x = x.dp, y = y.dp)
            .size(8.dp)
            .blur(12.dp)
            .background(Color.Blue.copy(alpha = alpha), CircleShape)
    )
}