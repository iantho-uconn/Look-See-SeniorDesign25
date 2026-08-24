package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

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

sealed class NegativeCameraFlowState {
    object Instruction : NegativeCameraFlowState()
    object Recording : NegativeCameraFlowState()
    object Choice : NegativeCameraFlowState()
    data class Preview(val uri: Uri) : NegativeCameraFlowState()
}

@SuppressLint("DefaultLocale")
@Composable
fun NegativeVideoCameraView(
    onDone: (CapturedNegativeVideo) -> Unit,
    onDismiss: () -> Unit
) {
    val brandBlue = Color(0xFF387DFF)

    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Expects your NegativeVideoCameraService implementation (Will be red until added!)
    val cameraService = remember { NegativeVideoCameraService() }

    var currentPhase by remember { mutableStateOf<NegativeCameraPhase>(NegativeCameraPhase.First) }
    var flowState by remember { mutableStateOf<NegativeCameraFlowState>(NegativeCameraFlowState.Instruction) }

    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var timeElapsed by remember { mutableIntStateOf(0) }
    var totalDurationElapsed by remember { mutableDoubleStateOf(0.0) }
    var collectedURIs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isCancelled by remember { mutableStateOf(false) }
    var isFinishing by remember { mutableStateOf(false) }
    var finishingErrorMessage by remember { mutableStateOf<String?>(null) }

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }

    val maxTotalTimeLimit = 30
    val totalDurationElapsedInt = totalDurationElapsed.toInt()
    val minPhaseTimeLimit = if (currentPhase is NegativeCameraPhase.First) 10 else 1
    val maxPhaseTimeLimit = maxTotalTimeLimit - totalDurationElapsedInt
    val isReviewingClip = flowState is NegativeCameraFlowState.Preview

    fun startTimer() {
        timeElapsed = 0
        recordingJob?.cancel()
        recordingJob = coroutineScope.launch {
            while (timeElapsed < maxPhaseTimeLimit) {
                delay(1000.milliseconds)
                timeElapsed += 1
            }
            totalDurationElapsed += timeElapsed.toDouble()
            cameraService.stopRecording()
        }
    }

    fun stopTimer() {
        recordingJob?.cancel()
        recordingJob = null
    }

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        showZoomIndicator = true
        zoomFadeJob = coroutineScope.launch {
            delay(1500.milliseconds)
            showZoomIndicator = false
        }
    }

    fun finishAndStitch() {
        if (isFinishing) return
        if (collectedURIs.isEmpty()) {
            finishingErrorMessage = "No recorded video was available."
            return
        }

        isFinishing = true
        finishingErrorMessage = null

        coroutineScope.launch {
            try {
                val outputUri = VideoMerger.mergeAndValidate(
                    context = context,
                    clipUris = collectedURIs,
                    minimumDuration = 10.0
                )

                if (!collectedURIs.contains(outputUri)) {
                    collectedURIs.forEach { sourceUri ->
                        try { File(sourceUri.path ?: "").delete() } catch (_: Exception) {}
                    }
                }

                val video = CapturedNegativeVideo(fileURL = outputUri)
                onDone(video)
                isFinishing = false
                onDismiss()
            } catch (e: Exception) {
                isFinishing = false
                finishingErrorMessage = e.localizedMessage
            }
        }
    }

    LaunchedEffect(Unit) {
        cameraService.onVideoRecorded = { uri: Uri ->
            if (isCancelled) {
                try { File(uri.path ?: "").delete() } catch (_: Exception) {}
                onDismiss()
            } else {
                flowState = NegativeCameraFlowState.Preview(uri)
            }
        }
        cameraService.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraService.stop()
            stopTimer()
            zoomFadeJob?.cancel()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // MARK: - Camera Preview
        if (flowState != NegativeCameraFlowState.Choice && !isReviewingClip) {
            NegativeVideoCameraPreview(
                _cameraService = cameraService,
                zoomLevel = zoomLevel,
                onZoomChanged = {
                    zoomLevel = it
                    showZoomIndicatorThenFade()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // MARK: - Video Player
        if (flowState is NegativeCameraFlowState.Preview) {
            NegativeSafeVideoPlayer(
                uri = (flowState as NegativeCameraFlowState.Preview).uri,
                modifier = Modifier.fillMaxSize()
            )
        }

        // MARK: - Zoom Indicator
        AnimatedVisibility(
            visible = showZoomIndicator,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp)
        ) {
            Text(
                text = String.format("%.1fx", zoomLevel),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // MARK: - UI Overlays
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isCancelled = true
                        stopTimer()
                        if (cameraService.isRecording) {
                            cameraService.stopRecording()
                        } else {
                            collectedURIs.forEach { try { File(it.path ?: "").delete() } catch(_: Exception){} }
                            if (flowState is NegativeCameraFlowState.Preview) {
                                try { File((flowState as NegativeCameraFlowState.Preview).uri.path ?: "").delete() } catch(_: Exception){}
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }

                Spacer(Modifier.weight(1f))

                if (flowState == NegativeCameraFlowState.Recording) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (timeElapsed % 2 == 0) Color.Red else Color.Red.copy(alpha = 0.3f))
                        )
                        Text(
                            text = String.format("%02d / %02d", timeElapsed, maxPhaseTimeLimit),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom Controls based on Flow State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(bottom = 50.dp, top = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = flowState, label = "FlowStateAnimation") { state ->
                    when (state) {
                        is NegativeCameraFlowState.Instruction -> {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                                    .padding(30.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(60.dp).background(brandBlue.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = brandBlue, modifier = Modifier.size(24.dp))
                                }
                                Text(currentPhase.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = "Pan the area. Do not include the landmark in the video.",
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Gray, textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        flowState = NegativeCameraFlowState.Recording
                                        cameraService.startRecording()
                                        startTimer()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Start Recording", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }

                        is NegativeCameraFlowState.Recording -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                if (timeElapsed < minPhaseTimeLimit) {
                                    Text(
                                        text = "Keep recording for ${minPhaseTimeLimit - timeElapsed}s...",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                } else {
                                    Text(
                                        text = "Ready to stop",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                        modifier = Modifier.background(Color.Green.copy(alpha = 0.8f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }

                                Box(contentAlignment = Alignment.Center) {
                                    IconButton(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            totalDurationElapsed += timeElapsed.toDouble()
                                            stopTimer()
                                            cameraService.stopRecording()
                                        },
                                        enabled = timeElapsed >= minPhaseTimeLimit,
                                        modifier = Modifier.size(80.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { timeElapsed.toFloat() / maxPhaseTimeLimit.toFloat() },
                                            modifier = Modifier.fillMaxSize(),
                                            color = Color.Red,
                                            trackColor = Color.White.copy(alpha = 0.3f),
                                            strokeWidth = 4.dp
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(if (timeElapsed >= minPhaseTimeLimit) 32.dp else 64.dp)
                                                .background(if (timeElapsed >= minPhaseTimeLimit) Color.Red else Color.White.copy(alpha = 0.8f), RoundedCornerShape(if (timeElapsed >= minPhaseTimeLimit) 8.dp else 40.dp))
                                        )
                                    }
                                }
                            }
                        }

                        is NegativeCameraFlowState.Choice -> {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                                    .padding(30.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Text("Add More Background?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = "Would you like to add another background pan to further improve recognition?",
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            currentPhase = if (currentPhase is NegativeCameraPhase.First) NegativeCameraPhase.Additional(2) else NegativeCameraPhase.Additional(currentPhase.indexPos + 2)
                                            flowState = NegativeCameraFlowState.Instruction
                                        },
                                        modifier = Modifier.fillMaxWidth().height(54.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                        shape = RoundedCornerShape(16.dp)
                                    ) { Text("Yes, Add Clip", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            finishAndStitch()
                                        },
                                        enabled = !isFinishing,
                                        modifier = Modifier.fillMaxWidth().height(54.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        if (isFinishing) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black)
                                        else Text("No, Finish Background", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        is NegativeCameraFlowState.Preview -> {
                            val uri = state.uri
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 20.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        totalDurationElapsed -= timeElapsed.toDouble()
                                        try { File(uri.path ?: "").delete() } catch(_: Exception){}
                                        flowState = NegativeCameraFlowState.Instruction
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val mutableUris = collectedURIs.toMutableList()
                                        if (mutableUris.size > currentPhase.indexPos) mutableUris[currentPhase.indexPos] = uri else mutableUris.add(uri)
                                        collectedURIs = mutableUris

                                        val timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt
                                        if (timeRemaining >= 3) {
                                            flowState = NegativeCameraFlowState.Choice
                                        } else {
                                            finishAndStitch()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Accept Clip", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }

        // Camera Error Overlay
        finishingErrorMessage?.let { message ->
            if (flowState != NegativeCameraFlowState.Choice) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(40.dp).background(Color.DarkGray, RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(42.dp))
                        Text("Camera Unavailable", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(message, fontSize = 15.sp, color = Color.LightGray, textAlign = TextAlign.Center)
                        Button(
                            onClick = { onDismiss() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                }
            }
        }
    }

    if (finishingErrorMessage != null && isFinishing) {
        AlertDialog(
            onDismissRequest = { finishingErrorMessage = null },
            title = { Text("Couldn't Prepare Video") },
            text = { Text(finishingErrorMessage ?: "Please try again.") },
            confirmButton = {
                TextButton(onClick = { finishingErrorMessage = null }) { Text("OK") }
            }
        )
    }
}

// MARK: - Native Wrappers

@Composable
fun NegativeVideoCameraPreview(
    _cameraService: Any?, // Prefixed to avoid unused parameter warning until implemented
    zoomLevel: Float,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = max(1.0f, min(zoomLevel * zoom, 5.0f))
                    onZoomChanged(newZoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { _ ->
                    // Map this offset to focus logic if needed
                }
            }
    ) {
        AndroidView(
            factory = { context ->
                android.view.View(context).apply { setBackgroundColor(android.graphics.Color.DKGRAY) }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun NegativeSafeVideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(uri)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}