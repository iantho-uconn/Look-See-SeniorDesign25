package looksee.angelll.com.uifiles

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class NegativeCameraPhase {
    object First : NegativeCameraPhase()
    data class Additional(val index: Int) : NegativeCameraPhase()

    val title: String
        get() = when (this) {
            is First -> "Background Pan"
            is Additional -> "Extra Pan ${index - 1}"
        }

    val indexPos: Int
        get() = when (this) {
            is First -> 0
            is Additional -> index - 1
        }
}

enum class NegativeCameraFlowState {
    INSTRUCTION, RECORDING, CHOICE, PREVIEW
}

@Composable
fun NegativeVideoCameraScreen(
    onDone: (CapturedNegativeVideo) -> Unit, // Unresolved reference
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Unresolved reference: NegativeVideoCameraService
    val cameraService = remember { NegativeVideoCameraService() }

    var currentPhase by remember { mutableStateOf<NegativeCameraPhase>(NegativeCameraPhase.First) }
    var flowState by remember { mutableStateOf(NegativeCameraFlowState.INSTRUCTION) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }

    var timeElapsed by remember { mutableIntStateOf(0) }
    var totalDurationElapsed by remember { mutableDoubleStateOf(0.0) }
    var collectedURIs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isCancelled by remember { mutableStateOf(false) }

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }

    val maxTotalTimeLimit = 60
    val minPhaseTimeLimit = if (currentPhase is NegativeCameraPhase.First) 10 else 1
    val maxPhaseTimeLimit = maxTotalTimeLimit - totalDurationElapsed.toInt()

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        showZoomIndicator = true
        zoomFadeJob = coroutineScope.launch {
            delay(1500.milliseconds)
            showZoomIndicator = false
        }
    }

    // Timer Equivalent using Coroutines
    LaunchedEffect(flowState) {
        if (flowState == NegativeCameraFlowState.RECORDING) {
            timeElapsed = 0
            while (timeElapsed < maxPhaseTimeLimit) {
                delay(1.seconds)
                timeElapsed++
            }
            cameraService.stopRecording()
        }
    }

    LaunchedEffect(Unit) {
        cameraService.onVideoRecorded = { uri ->
            if (isCancelled) {
                // Delete logic would go here
                onDismiss()
            } else {
                previewUri = uri
                flowState = NegativeCameraFlowState.PREVIEW
            }
        }
        cameraService.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraService.stop()
            zoomFadeJob?.cancel()
        }
    }

    fun finishAndStitch() {
        coroutineScope.launch {
            // Unresolved references to CapturedNegativeVideo and stitching logic
            val finalUri = collectedURIs.firstOrNull() // Placeholder for stitching
            if (finalUri != null) {
                onDone(CapturedNegativeVideo(finalUri))
            }
            onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Camera Preview / Video Player layer
        if (flowState == NegativeCameraFlowState.PREVIEW && previewUri != null) {
            UploadFormVideoPlayer(uri = previewUri!!) // Placeholder from LandmarkRecord
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
            }
        }

        // Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isCancelled = true
                    if (cameraService.isRecording) {
                        cameraService.stopRecording()
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            if (flowState == NegativeCameraFlowState.RECORDING) {
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (timeElapsed % 2 == 0) Color.Red else Color.Red.copy(alpha = 0.3f), CircleShape)
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%02d", timeElapsed)} / ${String.format(java.util.Locale.US, "%02d", maxPhaseTimeLimit)}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 60.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (flowState) {
                NegativeCameraFlowState.INSTRUCTION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                            .padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color(0xFF387DFF).copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(24.dp))
                        }

                        Text(currentPhase.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Pan the area. Do not include the landmark in the video.", fontSize = 15.sp, textAlign = TextAlign.Center, color = Color.LightGray)

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                flowState = NegativeCameraFlowState.RECORDING
                                cameraService.startRecording()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Start Recording", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                NegativeCameraFlowState.RECORDING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(bottom = 50.dp, top = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(
                                text = if (timeElapsed < minPhaseTimeLimit) "Keep recording for ${minPhaseTimeLimit - timeElapsed}s..." else "Ready to stop",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                modifier = Modifier
                                    .background(if (timeElapsed < minPhaseTimeLimit) Color.Black.copy(alpha = 0.6f) else Color.Green.copy(alpha = 0.8f), CircleShape)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(80.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { (timeElapsed.toFloat() / maxPhaseTimeLimit.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxSize(),
                                    color = Color.Red,
                                    strokeWidth = 4.dp,
                                    strokeCap = StrokeCap.Round,
                                    trackColor = Color.White.copy(alpha = 0.3f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(if (timeElapsed >= minPhaseTimeLimit) 32.dp else 64.dp)
                                        .clip(RoundedCornerShape(if (timeElapsed >= minPhaseTimeLimit) 8.dp else 40.dp))
                                        .background(if (timeElapsed >= minPhaseTimeLimit) Color.Red else Color.White.copy(alpha = 0.8f))
                                        .clickable(enabled = timeElapsed >= minPhaseTimeLimit) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            totalDurationElapsed += timeElapsed
                                            cameraService.stopRecording()
                                        }
                                )
                            }
                        }
                    }
                }
                NegativeCameraFlowState.CHOICE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                            .padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text("Add More Background?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Would you like to add another background pan to further improve recognition?", fontSize = 15.sp, textAlign = TextAlign.Center, color = Color.LightGray)

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPhase = if (currentPhase is NegativeCameraPhase.First) NegativeCameraPhase.Additional(2) else NegativeCameraPhase.Additional((currentPhase as NegativeCameraPhase.Additional).index + 1)
                                    flowState = NegativeCameraFlowState.INSTRUCTION
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text("Yes, Add Clip", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    finishAndStitch()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(alpha = 0.8f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text("No, Finish Background", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                        }
                    }
                }
                NegativeCameraFlowState.PREVIEW -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                totalDurationElapsed -= timeElapsed
                                flowState = NegativeCameraFlowState.INSTRUCTION
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val currentUri = previewUri
                                if (currentUri != null) {
                                    val mutableUris = collectedURIs.toMutableList()
                                    if (mutableUris.size > currentPhase.indexPos) mutableUris[currentPhase.indexPos] = currentUri else mutableUris.add(currentUri)
                                    collectedURIs = mutableUris
                                }
                                val timeRemaining = maxTotalTimeLimit - totalDurationElapsed.toInt()
                                if (timeRemaining >= 3) flowState = NegativeCameraFlowState.CHOICE else finishAndStitch()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Accept Clip", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                }
            }
        }

        // Zoom Indicator
        AnimatedVisibility(
            visible = showZoomIndicator,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp)
        ) {
            Text(
                text = String.format(java.util.Locale.US, "%.1fx", zoomLevel),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Error Overlay
        if (cameraService.errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(40.dp)) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(42.dp))
                    Text("Camera Unavailable", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(cameraService.errorMessage!!, textAlign = TextAlign.Center, color = Color.White)
                    Button(onClick = { onDismiss() }) { Text("Close") }
                }
            }
        }
    }
}