package looksee.angelll.com.uifiles

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// MARK: - Models & Enums

sealed class BusinessNegativeCameraPhase {
    data class Mandatory(val idx: Int) : BusinessNegativeCameraPhase()
    data class Optional(val idx: Int) : BusinessNegativeCameraPhase()

    val isMandatory: Boolean
        get() = this is Mandatory

    val title: String
        get() = when (this) {
            is Mandatory -> if (idx == 1) "Capture Negative Background" else "Step $idx: Additional Pan"
            is Optional -> "Additional Coverage"
        }

    val instruction: String
        get() = when (this) {
            is Mandatory -> "Pan the area. Do NOT include the landmark in the video."
            is Optional -> "Record extra background footage around the area."
        }

    val indexPos: Int
        get() = when (this) {
            is Mandatory -> idx - 1
            is Optional -> idx - 1
        }
}

data class BusinessNegativeRecordedClip(
    val phase: BusinessNegativeCameraPhase,
    val uri: Uri,
    val duration: Int
) {
    val id: String get() = uri.toString()
}

enum class BusinessNegativeCameraFlowState {
    INSTRUCTION, RECORDING, REVIEWING_RECENT, GALLERY
}

// MARK: - Main Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BusinessNegativeVideoCameraView(
    onDone: (CapturedNegativeVideo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val cameraService = remember { NegativeVideoCameraService(context) }

    var currentPhase by remember { mutableStateOf<BusinessNegativeCameraPhase>(BusinessNegativeCameraPhase.Mandatory(1)) }
    var flowState by remember { mutableStateOf(BusinessNegativeCameraFlowState.INSTRUCTION) }

    var reviewingUri by remember { mutableStateOf<Uri?>(null) }
    var reviewingDuration by remember { mutableStateOf(0) }

    val expectedAngles = 1
    val maxTotalTimeLimit = 30
    val minTotalTimeLimit = 1

    var timeElapsed by remember { mutableStateOf(0) }
    var recordedClips by remember { mutableStateOf<List<BusinessNegativeRecordedClip>>(emptyList()) }

    var isFinishing by remember { mutableStateOf(false) }
    var finishingErrorMessage by remember { mutableStateOf<String?>(null) }

    var zoomLevel by remember { mutableStateOf(1f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var showZoomInstruction by remember { mutableStateOf(false) }

    val totalDurationElapsedInt = recordedClips.sumOf { it.duration }

    val minPhaseTimeLimit = if (currentPhase.isMandatory) minTotalTimeLimit else 1
    val maxPhaseTimeLimit = if (currentPhase.isMandatory) maxTotalTimeLimit / expectedAngles else maxTotalTimeLimit - totalDurationElapsedInt

    val isReviewingRecent = flowState == BusinessNegativeCameraFlowState.REVIEWING_RECENT

    val currentLiveDuration = when (flowState) {
        BusinessNegativeCameraFlowState.RECORDING -> timeElapsed
        BusinessNegativeCameraFlowState.REVIEWING_RECENT -> reviewingDuration
        else -> 0
    }

    val isCurrentClipValidMandatory = currentPhase.isMandatory && currentLiveDuration >= minPhaseTimeLimit
    val totalTime = totalDurationElapsedInt + currentLiveDuration
    val capturedMandatoryCount = recordedClips.count { it.phase.isMandatory }
    val effectiveMandatoryCount = capturedMandatoryCount + if (isCurrentClipValidMandatory) 1 else 0
    val isReady = totalTime >= minTotalTimeLimit && effectiveMandatoryCount >= expectedAngles

    val pagerState = rememberPagerState(pageCount = { recordedClips.size })

    // Lifecycle Handling for Backgrounding
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                cameraService.handleInterruptionBegan()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                cameraService.handleInterruptionEnded()
                if (flowState == BusinessNegativeCameraFlowState.RECORDING) {
                    timeElapsed = 0
                    flowState = BusinessNegativeCameraFlowState.INSTRUCTION
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle incoming video
    DisposableEffect(Unit) {
        cameraService.onVideoRecorded = { uri ->
            reviewingUri = uri
            reviewingDuration = timeElapsed
            flowState = BusinessNegativeCameraFlowState.REVIEWING_RECENT
        }
        onDispose {
            cameraService.stop()
        }
    }

    // Timer
    LaunchedEffect(flowState, cameraService.isRecording) {
        if (flowState == BusinessNegativeCameraFlowState.RECORDING && cameraService.isRecording) {
            timeElapsed = 0
            showZoomInstruction = true
            coroutineScope.launch { delay(4000); showZoomInstruction = false }

            while (timeElapsed < maxPhaseTimeLimit) {
                delay(1000)
                timeElapsed++
            }
            cameraService.stopRecording()
        }
    }

    fun deleteClip(clip: BusinessNegativeRecordedClip) {
        recordedClips = recordedClips.filter { it.id != clip.id }
        try { clip.uri.path?.let { File(it).delete() } } catch (e: Exception) {}

        if (recordedClips.isEmpty()) {
            currentPhase = BusinessNegativeCameraPhase.Mandatory(1)
            flowState = BusinessNegativeCameraFlowState.INSTRUCTION
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F1A))) {

        // 1. Camera Preview
        if (flowState == BusinessNegativeCameraFlowState.INSTRUCTION || flowState == BusinessNegativeCameraFlowState.RECORDING) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    cameraService.start(lifecycleOwner, previewView.surfaceProvider)
                    previewView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            zoomLevel = (zoomLevel * zoom).coerceIn(1f, 5f)
                            showZoomIndicator = true
                            cameraService.videoCapture?.camera?.cameraControl?.setZoomRatio(zoomLevel)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val factory = (LocalContext as? PreviewView)?.meteringPointFactory ?: return@detectTapGestures
                            val point = factory.createPoint(offset.x, offset.y)
                            val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
                            cameraService.videoCapture?.camera?.cameraControl?.startFocusAndMetering(action)
                        }
                    }
            )

            // Hide Zoom Indicator after delay
            LaunchedEffect(zoomLevel) {
                delay(1500)
                showZoomIndicator = false
            }
        }

        // 2. Video Player for Reviewing
        if (isReviewingRecent && reviewingUri != null) {
            BusinessNegativeSafeVideoPlayer(uri = reviewingUri!!, modifier = Modifier.fillMaxSize())
        }

        // 3. Zoom Overlay Text
        AnimatedVisibility(visible = showZoomInstruction, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp)) {
            Text(
                "Slowly pan the area and background while pinching to zoom in and out",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.background(Color(0xFF387DFF).copy(alpha = 0.9f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(visible = showZoomIndicator, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp)) {
            Text(
                String.format("%.1fx", zoomLevel),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 4. Main UI Overlay
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Bar
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (flowState != BusinessNegativeCameraFlowState.GALLERY) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.2f)).border(0.5.dp, Color.White.copy(0.2f), CircleShape).clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (cameraService.isRecording) cameraService.stopRecording()
                        recordedClips.forEach { try { it.uri.path?.let { p -> File(p).delete() } } catch (e: Exception) {} }
                        reviewingUri?.path?.let { File(it).delete() }
                        onDismiss()
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Row(modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isReady) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (isReady) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${totalTime}s / ${maxTotalTimeLimit}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (flowState == BusinessNegativeCameraFlowState.INSTRUCTION) {
                Row(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().background(Color.Black.copy(0.4f), RoundedCornerShape(16.dp)).border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(16.dp)).padding(20.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Capture Negative Media", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Pan the area and background environment. Do not include the main landmark in this video.", color = Color.White.copy(0.8f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom Controls
            AnimatedVisibility(visible = true, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
                Box(modifier = Modifier.padding(horizontal = 20.dp, bottom = 100.dp).fillMaxWidth().background(Color.Black.copy(0.6f), RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(32.dp)).padding(24.dp)) {

                    when (flowState) {
                        BusinessNegativeCameraFlowState.INSTRUCTION -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(currentPhase.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(16.dp))
                                Text(currentPhase.instruction, color = Color.White.copy(0.9f), fontSize = 15.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        flowState = BusinessNegativeCameraFlowState.RECORDING
                                        cameraService.startRecording()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Start Recording", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

                                if (recordedClips.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            flowState = BusinessNegativeCameraFlowState.GALLERY
                                        },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(16.dp)
                                    ) { Text("Cancel & View Captured Clips", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }

                        BusinessNegativeCameraFlowState.RECORDING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                if (timeElapsed < minPhaseTimeLimit) {
                                    Text("Keep recording for ${minPhaseTimeLimit - timeElapsed}s...", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.Black.copy(0.6f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp))
                                } else {
                                    Text("Ready to stop", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFF4CAF50).copy(0.8f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp))
                                }

                                Spacer(Modifier.height(20.dp))

                                Box(modifier = Modifier.size(80.dp).clip(CircleShape).clickable(enabled = timeElapsed >= minPhaseTimeLimit) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cameraService.stopRecording()
                                }, contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(progress = { timeElapsed.toFloat() / maxPhaseTimeLimit.toFloat() }, modifier = Modifier.fillMaxSize(), color = Color.Red, trackColor = Color.White.copy(0.3f), strokeWidth = 4.dp)
                                    Box(modifier = Modifier.size(if(timeElapsed >= minPhaseTimeLimit) 32.dp else 64.dp).clip(RoundedCornerShape(if(timeElapsed >= minPhaseTimeLimit) 8.dp else 40.dp)).background(if(timeElapsed >= minPhaseTimeLimit) Color.Red else Color.White.copy(0.8f)))
                                }
                            }
                        }

                        BusinessNegativeCameraFlowState.REVIEWING_RECENT -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        reviewingUri?.path?.let { File(it).delete() }
                                        flowState = BusinessNegativeCameraFlowState.INSTRUCTION
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.8f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val newClip = BusinessNegativeRecordedClip(currentPhase, reviewingUri!!, reviewingDuration)
                                        recordedClips = recordedClips + newClip

                                        coroutineScope.launch {
                                            flowState = BusinessNegativeCameraFlowState.GALLERY
                                            delay(100)
                                            pagerState.animateScrollToPage(recordedClips.size - 1)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Accept", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                            }
                        }

                        BusinessNegativeCameraFlowState.GALLERY -> EmptyView()
                    }
                }
            }
        }

        // 5. Full Screen Gallery Overlay
        if (flowState == BusinessNegativeCameraFlowState.GALLERY) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {

                // Pager
                if (recordedClips.isNotEmpty()) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        val clip = recordedClips[page]
                        Box {
                            BusinessNegativeSafeVideoPlayer(uri = clip.uri, modifier = Modifier.fillMaxSize())

                            // Delete Button
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 58.dp), contentAlignment = Alignment.TopEnd) {
                                Box(modifier = Modifier.size(32.dp).background(Color.Black.copy(0.6f), CircleShape).clickable { deleteClip(clip) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Gallery Controls
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(0.6f)).padding(24.dp).padding(bottom = 20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                            val nextMandatory = (1..expectedAngles).find { idx -> recordedClips.none { it.phase is BusinessNegativeCameraPhase.Mandatory && (it.phase as BusinessNegativeCameraPhase.Mandatory).idx == idx } }?.let { BusinessNegativeCameraPhase.Mandatory(it) }
                            val timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total: ${totalDurationElapsedInt}s / ${maxTotalTimeLimit}s", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                if (nextMandatory == null) Icon(if(totalDurationElapsedInt >= minTotalTimeLimit) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = if(totalDurationElapsedInt >= minTotalTimeLimit) Color.Green else Color(0xFFFFA500))
                            }

                            if (nextMandatory != null) {
                                Button(
                                    onClick = { currentPhase = nextMandatory; flowState = BusinessNegativeCameraFlowState.INSTRUCTION },
                                    modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)), shape = RoundedCornerShape(16.dp)
                                ) { Text("Record Next Angle", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                            } else {
                                if (timeRemaining > 0) {
                                    Button(
                                        onClick = { currentPhase = BusinessNegativeCameraPhase.Optional(recordedClips.size + 1); flowState = BusinessNegativeCameraFlowState.INSTRUCTION },
                                        modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)), shape = RoundedCornerShape(16.dp)
                                    ) { Text("Add Extra Clip", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                                }

                                if (totalDurationElapsedInt >= minTotalTimeLimit) {
                                    Button(
                                        onClick = {
                                            isFinishing = true
                                            val urisToStitch = recordedClips.map { it.uri }
                                            if (urisToStitch.size == 1) {
                                                onDone(CapturedNegativeVideo(fileURL = urisToStitch.first()))
                                                onDismiss()
                                            } else {
                                                coroutineScope.launch {
                                                    try {
                                                        val mergedUri = VideoMerger.mergeAndValidate(context, urisToStitch, 1.0)
                                                        onDone(CapturedNegativeVideo(fileURL = mergedUri))
                                                        onDismiss()
                                                    } catch (e: Exception) {
                                                        finishingErrorMessage = "Failed to stitch video clips."
                                                        isFinishing = false
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), enabled = !isFinishing
                                    ) {
                                        if (isFinishing) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                                        else Text("Finish Submission", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Total video must be between $minTotalTimeLimit and $maxTotalTimeLimit seconds", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
                                }
                            }
                        }
                    }
                }

                // Gallery Top Close
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 58.dp)) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.2f)).border(0.5.dp, Color.White.copy(0.2f), CircleShape).clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        recordedClips.forEach { try { it.uri.path?.let { p -> File(p).delete() } } catch (e: Exception) {} }
                        recordedClips = emptyList()
                        timeElapsed = 0
                        currentPhase = BusinessNegativeCameraPhase.Mandatory(1)
                        flowState = BusinessNegativeCameraFlowState.INSTRUCTION
                        onDismiss()
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }

        // Error Overlay
        if (cameraService.errorMessage != null || finishingErrorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)).zIndex(20f), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.background(Color.DarkGray, RoundedCornerShape(32.dp)).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(42.dp))
                    Text("Camera Unavailable", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(cameraService.errorMessage ?: finishingErrorMessage ?: "", color = Color.LightGray, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) { Text("Close", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// MARK: - Native Video Player (ExoPlayer)
@Composable
fun BusinessNegativeSafeVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = modifier
    )
}