package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

enum class RecordingPhase(val message: String) {
    FRONT("Step 1: Pan video across the front of the landmark"),
    LEFT("Step 2: Move to left side of landmark and pan video across the left side of the landmark"),
    RIGHT("Step 3: Move to right side of landmark and pan video across the right side of the landmark"),
    LAST("Step 4: Move to back side of landmark, if unavailable, move to another location around the landmark and pan video across the landmark"),
    NEGATIVE("Pan the area. Do not include the landmark in the video.")
}

@Composable
fun GuidedCaptureOverlay(
    isNegative: Boolean,
    isRecording: Boolean
) {
    var showPopup by remember { mutableStateOf(true) }
    var currentPhase by remember {
        mutableStateOf(if (isNegative) RecordingPhase.NEGATIVE else RecordingPhase.FRONT)
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            if (!isNegative) {
                val phases = listOf(RecordingPhase.FRONT, RecordingPhase.LEFT, RecordingPhase.RIGHT, RecordingPhase.LAST)
                var count = 0
                while (count < phases.size - 1) {
                    delay(5.seconds)
                    count++
                    currentPhase = phases[count]
                    showPopup = true
                }
            }
        } else {
            if (!isNegative) {
                currentPhase = RecordingPhase.FRONT
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Pulsing Reticle in the center
        if (isRecording) {
            PulsingReticle(modifier = Modifier.align(Alignment.Center))
        }

        // Invisible catch-all for taps to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.0001f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showPopup = false
                }
        )

        // Popup Container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 100.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = showPopup,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .height(220.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1C1C29).copy(alpha = 0.95f))
                        .border(2.dp, Color(0xFF387DFF).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color(0xFF387DFF),
                        modifier = Modifier.size(40.dp)
                    )

                    Text(
                        text = currentPhase.message,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Text(
                        text = "Tap anywhere to dismiss",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun PulsingReticle(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ReticlePulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Scale"
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Opacity"
    )

    Canvas(modifier = modifier.size(80.dp)) {
        val strokeWidth = 3.dp.toPx()
        val radius = (size.minDimension / 2) * scale
        
        drawCircle(
            color = Color(0xFF387DFF).copy(alpha = opacity),
            radius = radius,
            style = Stroke(width = strokeWidth)
        )
        
        // Reticle crosshairs
        val lineLength = 15.dp.toPx()
        drawLine(
            color = Color(0xFF387DFF).copy(alpha = opacity),
            start = center.copy(x = center.x - lineLength),
            end = center.copy(x = center.x + lineLength),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = Color(0xFF387DFF).copy(alpha = opacity),
            start = center.copy(y = center.y - lineLength),
            end = center.copy(y = center.y + lineLength),
            strokeWidth = strokeWidth
        )
    }
}


@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuidedCaptureOverlay() {
    GuidedCaptureOverlay(isNegative = false, isRecording = false)
}