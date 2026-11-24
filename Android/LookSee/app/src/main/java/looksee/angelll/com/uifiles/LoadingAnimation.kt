package looksee.angelll.com.uifiles

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun LoadingAnimationScreen(
    animationFinished: Boolean = false,
    onFinished: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

    val logoScale = remember { Animatable(0.82f) }
    val glowScale = remember { Animatable(0.75f) }
    val glowOpacity = remember { Animatable(0f) }
    var breathing by remember { mutableStateOf(false) }
    var showParticles by remember { mutableStateOf(false) }

    val sweepRotation = remember { Animatable(-90f) }

    val pulse1 = remember { Animatable(0f) }
    val pulse2 = remember { Animatable(0f) }
    val pulse3 = remember { Animatable(0f) }

    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            logoScale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
        }

        coroutineScope.launch {
            glowOpacity.animateTo(1f, tween(1200, easing = LinearOutSlowInEasing))
        }

        coroutineScope.launch {
            glowScale.animateTo(1f, tween(1200, easing = LinearOutSlowInEasing))
        }

        coroutineScope.launch {
            delay(2800.milliseconds)
            glowOpacity.animateTo(0.8f, tween(600, easing = LinearEasing))
        }

        coroutineScope.launch {
            delay(800.milliseconds)
            breathing = true
        }

        coroutineScope.launch {
            while (true) {
                sweepRotation.snapTo(-90f)
                sweepRotation.animateTo(270f, tween(2600, easing = LinearEasing))
            }
        }

        coroutineScope.launch {
            while (true) {
                pulse1.snapTo(0f)
                pulse1.animateTo(1f, tween(2400, easing = LinearOutSlowInEasing))
            }
        }

        coroutineScope.launch {
            delay(450.milliseconds)
            while (true) {
                pulse2.snapTo(0f)
                pulse2.animateTo(1f, tween(2100, easing = LinearOutSlowInEasing))
            }
        }

        coroutineScope.launch {
            delay(900.milliseconds)
            while (true) {
                pulse3.snapTo(0f)
                pulse3.animateTo(1f, tween(1800, easing = LinearOutSlowInEasing))
            }
        }

        coroutineScope.launch {
            delay(350.milliseconds)
            showParticles = true
        }

        // 🚀 THE FIX: Dynamically wait for the intro to finish, then exit.
        // No longer strictly bound to a 3-second delay if auth finishes earlier.
        coroutineScope.launch {
            delay(1200.milliseconds)
            // Spin wait until the parent `RootView` says the auth payload is ready
            while (!animationFinished) {
                delay(100.milliseconds)
            }
            onFinished?.invoke()
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .scale(glowScale.value)
                .alpha(glowOpacity.value)
                .background(
                    brush = Brush.radialGradient(
                        0.0f to Color(0xFF387DFF).copy(alpha = 0.45f),
                        0.5f to Color(0xFF387DFF).copy(alpha = 0.20f),
                        1.0f to Color.Transparent
                    ),
                    shape = CircleShape
                )
        )

        RadarPulse(progress = pulse1.value)
        RadarPulse(progress = pulse2.value)
        RadarPulse(progress = pulse3.value)

        RadarSweep(rotation = sweepRotation.value)

        Image(
            painter = painterResource(id = looksee.angelll.com.R.drawable.looksee_logo),
            contentDescription = "LookSee Logo",
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1f)
                .scale(if (breathing) breathingScale else 1f)
                .scale(logoScale.value)
        )

        FloatingParticles(active = showParticles)
    }
}

@Composable
fun RadarPulse(progress: Float) {
    Box(
        modifier = Modifier
            .size(320.dp)
            .scale(0.2f + (progress * 1.2f))
            .alpha((1f - progress).coerceIn(0f, 1f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2.5f
            drawCircle(
                color = Color(0xFF387DFF).copy(alpha = 0.45f),
                radius = radius,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun RadarSweep(rotation: Float) {
    Canvas(
        modifier = Modifier
            .size(320.dp)
            .rotate(rotation)
    ) {
        val inset = 20.dp.toPx()
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Blue.copy(alpha = 0.15f),
                    Color.Blue.copy(alpha = 0.95f)
                )
            ),
            startAngle = 0f,
            sweepAngle = 50.4f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun FloatingParticles(active: Boolean) {
    Box(modifier = Modifier.size(360.dp), contentAlignment = Alignment.Center) {
        for (i in 0 until 18) {
            Particle(index = i, active = active)
        }
    }
}

@Composable
fun Particle(index: Int, active: Boolean) {
    var move by remember { mutableStateOf(false) }

    val angle = (index * 20).toDouble()
    val radius = remember { (95..165).random().toFloat() }

    val offsetX by animateFloatAsState(
        targetValue = if (move) (cos(Math.toRadians(angle)) * radius).toFloat() else 0f,
        animationSpec = tween(2500, delayMillis = index * 80, easing = LinearOutSlowInEasing),
        label = "offsetX"
    )

    val offsetY by animateFloatAsState(
        targetValue = if (move) (sin(Math.toRadians(angle)) * radius).toFloat() else 0f,
        animationSpec = tween(2500, delayMillis = index * 80, easing = LinearOutSlowInEasing),
        label = "offsetY"
    )

    val opacity by animateFloatAsState(
        targetValue = if (move) 0f else 1f,
        animationSpec = tween(2500, delayMillis = index * 80, easing = LinearOutSlowInEasing),
        label = "opacity"
    )

    LaunchedEffect(active) {
        if (active) move = true
    }

    Box(
        modifier = Modifier
            .offset(x = offsetX.dp, y = offsetY.dp)
            .alpha(opacity.coerceIn(0f, 1f))
            .size(5.dp)
            .background(Color.Blue, CircleShape)
    )
}