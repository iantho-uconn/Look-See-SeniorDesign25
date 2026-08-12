package looksee.angelll.com.uifiles

import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.services.NegativeVideoCameraService
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// MARK: - Data Models
sealed class CameraPhase {
    data class Mandatory(val idx: Int) : CameraPhase()
    data class Optional(val idx: Int) : CameraPhase()

    val isMandatory: Boolean get() = this is Mandatory

    val title: String get() = when (this) {
        is Mandatory -> {
            when (idx) {
                1 -> "Step 1: Front"
                2 -> "Step 2: Second Angle"
                3 -> "Step 3: Third Angle"
                else -> "Step $idx: Fourth Angle"
            }
        }
        is Optional -> "Extra Coverage"
    }

    val instruction: String get() = when (this) {
        is Mandatory -> if (idx == 1) "Pan video across the front of the landmark." else "Move to a different side or angle and pan across the landmark."
        is Optional -> "Pan across to capture missing details.\n\nTip: Have you tried standing farther back to get the whole object?"
    }

    val indexPos: Int get() = when (this) {
        is Mandatory -> idx - 1
        is Optional -> idx - 1
    }
}

data class RecordedClip(
    val phase: CameraPhase,
    val uri: Uri,
    val duration: Int
) {
    val id: String get() = uri.toString()
}

sealed class CameraFlowState {
    object AngleSelection : CameraFlowState()
    object Instruction : CameraFlowState()
    object Recording : CameraFlowState()
    data class ReviewingRecent(val uri: Uri, val duration: Int) : CameraFlowState()
    object Gallery : CameraFlowState()
}

@Composable
fun PositiveVideoCameraView(onDone: (List<Uri>) -> Unit, onDismiss: () -> Unit) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Services
    val cameraService = remember { NegativeVideoCameraService() }

    // State
    var currentPhase by remember { mutableStateOf<CameraPhase>(CameraPhase.Mandatory(1)) }
    var flowState by remember { mutableStateOf<CameraFlowState>(CameraFlowState.AngleSelection) }
    var expectedAngles by remember { mutableIntStateOf(1) }
    var timeElapsed by remember { mutableIntStateOf(0) }
    var recordedClips by remember { mutableStateOf<List<RecordedClip>>(emptyList()) }
    var gallerySelection by remember { mutableStateOf("") }
    var isCancelled by remember { mutableStateOf(false) }

    // Zoom State
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var showZoomInstruction by remember { mutableStateOf(false) }

    // Constants
    val maxTotalTimeLimit = 60
    val minTotalTimeLimit = 15

    // Computed Properties
    val totalDurationElapsedInt = recordedClips.sumOf { it.duration }

    val minPhaseTimeLimit = if (currentPhase.isMandatory) 6 else max(1, minTotalTimeLimit - totalDurationElapsedInt)
    val maxPhaseTimeLimit = if (currentPhase.isMandatory) 60 / expectedAngles else maxTotalTimeLimit - totalDurationElapsedInt

    val isReviewingRecent = flowState is CameraFlowState.ReviewingRecent

    val currentLiveDuration = when (val state = flowState) {
        is CameraFlowState.Recording -> timeElapsed
        is CameraFlowState.ReviewingRecent -> state.duration
        else -> 0
    }

    val isCurrentClipValidMandatory = currentPhase.isMandatory && currentLiveDuration >= minPhaseTimeLimit
    val total = totalDurationElapsedInt + currentLiveDuration
    val capturedMandatoryCount = recordedClips.count { it.phase.isMandatory }
    val effectiveMandatoryCount = capturedMandatoryCount + (if (isCurrentClipValidMandatory) 1 else 0)
    val isReady = (total >= minTotalTimeLimit) && (effectiveMandatoryCount >= expectedAngles)

    // Timer Logic
    LaunchedEffect(flowState) {
        if (flowState is CameraFlowState.Recording) {
            timeElapsed = 0
            showZoomInstruction = true
            launch {
                delay(4000)
                showZoomInstruction = false
            }

            while (true) {
                delay(1000)
                timeElapsed++
                if (timeElapsed >= maxPhaseTimeLimit) {
                    cameraService.stopRecording()
                    break
                }
            }
        }
    }

    // Callbacks
    DisposableEffect(Unit) {
        cameraService.onVideoRecorded = { uri ->
            if (isCancelled) {
                File(uri.path ?: "").delete()
                onDismiss()
            } else {
                val recordedDuration = timeElapsed
                flowState = CameraFlowState.ReviewingRecent(uri, recordedDuration)
            }
        }
        cameraService.start()
        onDispose { cameraService.stop() }
    }

    fun deleteClip(clip: RecordedClip) {
        recordedClips = recordedClips.filterNot { it.id == clip.id }
        File(clip.uri.path ?: "").delete()
        if (recordedClips.isEmpty()) {
            currentPhase = CameraPhase.Mandatory(1)
            flowState = CameraFlowState.Instruction
        }
    }

    fun nextRequiredPhase(): CameraPhase? {
        for (i in 0 until expectedAngles) {
            if (recordedClips.none { it.phase.indexPos == i && it.phase.isMandatory }) {
                return CameraPhase.Mandatory(i + 1)
            }
        }
        return null
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (flowState !is CameraFlowState.Gallery && !isReviewingRecent) {
            PositiveVideoCameraPreview(
                zoomLevel = zoomLevel,
                onZoomChanged = {
                    zoomLevel = it
                    showZoomIndicator = true
                    coroutineScope.launch { delay(1500); showZoomIndicator = false }
                }
            )
        }

        if (flowState is CameraFlowState.ReviewingRecent) {
            val uri = (flowState as CameraFlowState.ReviewingRecent).uri
            PositiveSafeVideoPlayer(uri = uri)
        }

        if (flowState is CameraFlowState.Gallery) {
            val pagerState = rememberPagerState(pageCount = { recordedClips.size })
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val clip = recordedClips[page]
                Box(modifier = Modifier.fillMaxSize()) {
                    PositiveSafeVideoPlayer(uri = clip.uri)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                deleteClip(clip)
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White) }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = flowState is CameraFlowState.Recording && showZoomInstruction,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
        ) {
            Text(
                "Slowly pan around and pinch to zoom in/out",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.background(Color(0.22f, 0.49f, 1.0f).copy(alpha = 0.85f), CircleShape).padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        AnimatedVisibility(
            visible = showZoomIndicator,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp)
        ) {
            Text(
                String.format(Locale.US, "%.1fx", zoomLevel),
                fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (flowState !is CameraFlowState.Gallery) {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            isCancelled = true
                            if (cameraService.isRecording) {
                                cameraService.stopRecording()
                            } else {
                                recordedClips.forEach { File(it.uri.path ?: "").delete() }
                                if (flowState is CameraFlowState.ReviewingRecent) File((flowState as CameraFlowState.ReviewingRecent).uri.path ?: "").delete()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                } else { Spacer(Modifier.width(44.dp)) }

                if (flowState !is CameraFlowState.AngleSelection && flowState !is CameraFlowState.Gallery) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null, tint = if (isReady) Color.Green else Color(1.0f, 0.6f, 0.0f), modifier = Modifier.size(18.dp)
                        )
                        Text("${total}s / 60s", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            when (val state = flowState) {
                is CameraFlowState.AngleSelection -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 60.dp)
                            .background(Color.DarkGray.copy(alpha = 0.8f), RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Text("How many angles?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("How many distinct sides or perspectives does this landmark have?", fontSize = 15.sp, color = Color.LightGray, textAlign = TextAlign.Center)

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf(1, 2, 3, 4).forEach { count ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(60.dp).background(Color(0.22f, 0.49f, 1.0f), CircleShape)
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            expectedAngles = count
                                            currentPhase = CameraPhase.Mandatory(1)
                                            flowState = CameraFlowState.Instruction
                                        }
                                ) { Text(if (count == 4) "4+" else "$count", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            }
                        }
                    }
                }
                is CameraFlowState.Instruction -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 60.dp).background(Color.DarkGray.copy(alpha = 0.8f), RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(currentPhase.title.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0.22f, 0.49f, 1.0f), letterSpacing = 1.2.sp)
                        Text(currentPhase.instruction, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)

                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                flowState = CameraFlowState.Recording
                                cameraService.startRecording()
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0.22f, 0.49f, 1.0f))
                        ) { Text("Start Recording", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }

                        if (recordedClips.isNotEmpty()) {
                            Text(
                                "Cancel & View Captured Clips", color = Color.LightGray, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp).clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    gallerySelection = recordedClips.last().id
                                    flowState = CameraFlowState.Gallery
                                }
                            )
                        }
                    }
                }
                is CameraFlowState.Recording -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(bottom = 50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (timeElapsed < minPhaseTimeLimit) {
                            Text("Keep recording for ${minPhaseTimeLimit - timeElapsed}s...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp))
                        } else {
                            Text("Ready to stop", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.background(Color.Green.copy(alpha = 0.8f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp))
                        }

                        val isReadyToStop = timeElapsed >= minPhaseTimeLimit
                        val animatedSize by animateFloatAsState(if (isReadyToStop) 32f else 64f, tween(300))
                        val animatedRadius by animateFloatAsState(if (isReadyToStop) 8f else 40f, tween(300))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(80.dp).clickable(enabled = isReadyToStop) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                cameraService.stopRecording()
                            }
                        ) {
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(color = Color.White.copy(alpha = 0.3f), style = Stroke(4.dp.toPx()))
                                drawArc(color = Color.Red, startAngle = -90f, sweepAngle = 360f * (timeElapsed.toFloat() / maxPhaseTimeLimit.toFloat()), useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                            }
                            Box(modifier = Modifier.size(animatedSize.dp).background(if (isReadyToStop) Color.Red else Color.White.copy(alpha = 0.8f), RoundedCornerShape(animatedRadius.dp)))
                        }
                    }
                }
                is CameraFlowState.ReviewingRecent -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp).background(Color.DarkGray.copy(alpha = 0.8f), RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                File(state.uri.path ?: "").delete()
                                flowState = CameraFlowState.Instruction
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                        ) { Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }

                        Button(
                            onClick = {
                                val newClip = RecordedClip(currentPhase, state.uri, state.duration)
                                recordedClips = recordedClips + newClip
                                gallerySelection = newClip.id
                                flowState = CameraFlowState.Gallery
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0.22f, 0.49f, 1.0f))
                        ) { Text("Accept", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                }
                is CameraFlowState.Gallery -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp).background(Color.DarkGray.copy(alpha = 0.8f), RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val nextMandatory = nextRequiredPhase()
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            Text("Total: ${totalDurationElapsedInt}s / 60s", fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Spacer(Modifier.weight(1f))
                            if (nextMandatory == null) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (totalDurationElapsedInt >= minTotalTimeLimit) Color.Green else Color.Red, modifier = Modifier.size(18.dp))
                            } else {
                                Text("${expectedAngles - recordedClips.count { it.phase.isMandatory }} angles left", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(1.0f, 0.6f, 0.0f))
                            }
                        }

                        if (nextMandatory != null) {
                            Button(
                                onClick = { currentPhase = nextMandatory; flowState = CameraFlowState.Instruction },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0.22f, 0.49f, 1.0f))
                            ) { Text("Record Next Angle", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        } else {
                            if (totalDurationElapsedInt >= minTotalTimeLimit) {
                                Button(
                                    onClick = { onDone(recordedClips.map { it.uri }); onDismiss() },
                                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                                ) { Text("Finish Submission", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                            } else {
                                Text("Must reach 15s total minimum", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)).padding(vertical = 16.dp))
                            }

                            if ((maxTotalTimeLimit - totalDurationElapsedInt) > 0) {
                                Button(
                                    onClick = { currentPhase = CameraPhase.Optional(recordedClips.size + 1); flowState = CameraFlowState.Instruction },
                                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0.22f, 0.49f, 1.0f).copy(alpha = 0.15f))
                                ) { Text("Add Extra Clip", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0.22f, 0.49f, 1.0f), modifier = Modifier.padding(vertical = 8.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 🚀 REMOVED "private" SO QUICK UPLOAD VIEW CAN USE IT
@Composable
fun PositiveSafeVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PositiveVideoCameraPreview(zoomLevel: Float, onZoomChanged: (Float) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)

                    previewView.setOnTouchListener { view, event ->
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                        camera.cameraControl.startFocusAndMetering(action)
                        view.performClick()
                        true
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = max(1.0f, min(zoomLevel * zoom, 5.0f))
                    onZoomChanged(newZoom)
                }
            }
    )
}