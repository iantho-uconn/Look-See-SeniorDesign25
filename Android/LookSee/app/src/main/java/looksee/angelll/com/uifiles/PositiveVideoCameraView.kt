package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

sealed class CameraPhase {
    data class Mandatory(val idx: Int) : CameraPhase()
    data class Optional(val idx: Int) : CameraPhase()

    val isMandatory: Boolean
        get() = this is Mandatory

    val title: String
        get() = when (this) {
            is Mandatory -> when (idx) {
                1 -> "Capture Video of The Landmark"
                2 -> "Step 2: Second Angle"
                3 -> "Step 3: Third Angle"
                else -> "Step $idx: Fourth Angle"
            }
            is Optional -> "Additional Coverage"
        }

    val instruction: String
        get() = when (this) {
            is Mandatory -> if (idx == 1) "These videos should be taken from ALL typical places where users may see the landmark" else "Move to a different side or angle and pan across the landmark."
            is Optional -> "Pan across to capture missing details.\n\nTip: Have you tried standing farther back to get the whole object?"
        }

    val indexPos: Int
        get() = when (this) {
            is Mandatory -> idx - 1
            is Optional -> idx - 1
        }
}

data class RecordedClip(val phase: CameraPhase, val url: Uri, val duration: Int) {
    val id: String get() = url.toString()
}

sealed class CameraFlowState {
    object Instruction : CameraFlowState()
    object Recording : CameraFlowState()
    data class ReviewingRecent(val url: Uri, val duration: Int) : CameraFlowState()
    object Gallery : CameraFlowState()
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PositiveVideoCameraView(
    isActive: Boolean,
    isNavVisible: MutableState<Boolean>,
    completionButtonTitle: String = "Finish Submission",
    onDone: (List<Uri>) -> Unit,
    onCancel: () -> Unit
) {
    val brandBlue = Color(0xFF387DFF)
    val brandOrange = Color(0xFFFFA500)

    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Expects your NegativeVideoCameraService implementation (Will be red until added!)
    val cameraService = remember { NegativeVideoCameraService() }

    var wasRecordingBeforeBackground by remember { mutableStateOf(false) }
    var suppressNextError by remember { mutableStateOf(false) }

    var currentPhase by remember { mutableStateOf<CameraPhase>(CameraPhase.Mandatory(1)) }
    var flowState by remember { mutableStateOf<CameraFlowState>(CameraFlowState.Instruction) }

    val expectedAngles = 1
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var timeElapsed by remember { mutableIntStateOf(0) }

    var recordedClips by remember { mutableStateOf<List<RecordedClip>>(emptyList()) }
    var gallerySelection by remember { mutableStateOf("") }
    var isCancelled by remember { mutableStateOf(false) }

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var showZoomIndicator by remember { mutableStateOf(false) }
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }

    var showZoomInstruction by remember { mutableStateOf(false) }
    var zoomInstructionJob by remember { mutableStateOf<Job?>(null) }

    var isCameraWarmedUp by remember { mutableStateOf(false) }

    val maxTotalTimeLimit = 90
    val minTotalTimeLimit = 30

    val totalDurationElapsedInt = recordedClips.sumOf { it.duration }

    val minPhaseTimeLimit = if (currentPhase.isMandatory) {
        4
    } else {
        min(4, minTotalTimeLimit - totalDurationElapsedInt)
    }

    val maxPhaseTimeLimit = if (currentPhase.isMandatory) {
        maxTotalTimeLimit / expectedAngles
    } else {
        maxTotalTimeLimit - totalDurationElapsedInt
    }

    val isReviewingRecent = flowState is CameraFlowState.ReviewingRecent

    val currentLiveProgress = remember(flowState, timeElapsed, recordedClips, currentPhase) {
        val currentLiveDuration: Int
        val isCurrentClipValidMandatory: Boolean

        if (flowState == CameraFlowState.Recording) {
            currentLiveDuration = timeElapsed
            isCurrentClipValidMandatory = currentPhase.isMandatory && timeElapsed >= minPhaseTimeLimit
        } else if (flowState is CameraFlowState.ReviewingRecent) {
            val dur = (flowState as CameraFlowState.ReviewingRecent).duration
            currentLiveDuration = dur
            isCurrentClipValidMandatory = currentPhase.isMandatory && dur >= minPhaseTimeLimit
        } else {
            currentLiveDuration = 0
            isCurrentClipValidMandatory = false
        }

        val total = totalDurationElapsedInt + currentLiveDuration
        val capturedMandatoryCount = recordedClips.count { it.phase.isMandatory }
        val effectiveMandatoryCount = capturedMandatoryCount + (if (isCurrentClipValidMandatory) 1 else 0)

        val isReady = (total >= minTotalTimeLimit) && (effectiveMandatoryCount >= expectedAngles)
        Pair(total, isReady)
    }

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        showZoomIndicator = true
        zoomFadeJob = coroutineScope.launch {
            delay(1500.milliseconds)
            showZoomIndicator = false
        }
    }

    fun triggerRecordingInstruction() {
        zoomInstructionJob?.cancel()
        showZoomInstruction = true
        zoomInstructionJob = coroutineScope.launch {
            delay(4000.milliseconds)
            showZoomInstruction = false
        }
    }

    fun startTimer() {
        timeElapsed = 0
        triggerRecordingInstruction()
        recordingJob?.cancel()
        recordingJob = coroutineScope.launch {
            while (timeElapsed < maxPhaseTimeLimit) {
                delay(1000.milliseconds)
                timeElapsed += 1
            }
            recordingJob?.cancel()
            recordingJob = null
            cameraService.stopRecording()
        }
    }

    fun stopTimer() {
        recordingJob?.cancel()
        recordingJob = null
    }

    fun nextRequiredPhase(): CameraPhase? {
        for (i in 0 until expectedAngles) {
            if (!recordedClips.any { it.phase.indexPos == i && it.phase.isMandatory }) {
                return CameraPhase.Mandatory(i + 1)
            }
        }
        return null
    }

    fun deleteClip(clip: RecordedClip) {
        val mutableClips = recordedClips.toMutableList()
        val idx = mutableClips.indexOf(clip)
        if (idx != -1) {
            mutableClips.removeAt(idx)
            try { File(clip.url.path ?: "").delete() } catch (_: Exception) {}
            recordedClips = mutableClips
        }

        if (recordedClips.isEmpty()) {
            currentPhase = nextRequiredPhase() ?: CameraPhase.Mandatory(1)
            flowState = CameraFlowState.Instruction
        }
    }

    fun handleAppBackgrounding() {
        if (flowState != CameraFlowState.Recording) return
        wasRecordingBeforeBackground = true
        suppressNextError = true
        stopTimer()
        cameraService.stopRecording()
    }

    fun handleAppForegrounding() {
        if (!wasRecordingBeforeBackground) return
        wasRecordingBeforeBackground = false
        cameraService.errorMessage = null
        cameraService.start()
    }

    // Lifecycle Observer for Background/Foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> handleAppBackgrounding()
                Lifecycle.Event.ON_RESUME -> handleAppForegrounding()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        isNavVisible.value = (flowState == CameraFlowState.Instruction)
        cameraService.onVideoRecorded = { uri: Uri ->
            if (isCancelled) {
                try { File(uri.path ?: "").delete() } catch (_: Exception) {}
                onCancel()
            } else if (suppressNextError) {
                suppressNextError = false
                try { File(uri.path ?: "").delete() } catch (_: Exception) {}
                flowState = CameraFlowState.Instruction
            } else {
                val recordedDuration = timeElapsed
                flowState = CameraFlowState.ReviewingRecent(uri, recordedDuration)
            }
        }
        if (isActive) {
            coroutineScope.launch {
                cameraService.start()
                delay(400.milliseconds)
                isCameraWarmedUp = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isNavVisible.value = true
            isCameraWarmedUp = false
            coroutineScope.launch {
                delay(500.milliseconds)
                cameraService.stop()
            }
            stopTimer()
            zoomFadeJob?.cancel()
            zoomInstructionJob?.cancel()
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            if (!wasRecordingBeforeBackground) {
                coroutineScope.launch {
                    cameraService.start()
                    delay(400.milliseconds)
                    isCameraWarmedUp = true
                }
            }
        } else {
            coroutineScope.launch { cameraService.stop() }
            isCameraWarmedUp = false
        }
    }

    LaunchedEffect(flowState) {
        isNavVisible.value = (flowState == CameraFlowState.Instruction)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {

        // MARK: - Camera Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (flowState == CameraFlowState.Gallery || isReviewingRecent) 0f else if (isCameraWarmedUp) 1f else 0.001f)
        ) {
            PositiveVideoCameraPreview(
                cameraService = cameraService,
                zoomLevel = zoomLevel,
                onZoomChanged = {
                    zoomLevel = it
                    showZoomIndicatorThenFade()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // MARK: - Video Player
        if (flowState is CameraFlowState.ReviewingRecent) {
            PositiveSafeVideoPlayer(
                uri = (flowState as CameraFlowState.ReviewingRecent).url,
                modifier = Modifier.fillMaxSize()
            )
        }

        // MARK: - Zoom Instruction
        AnimatedVisibility(
            visible = flowState == CameraFlowState.Recording && showZoomInstruction,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp)
        ) {
            Text(
                text = "Slowly pan across the landmark while pinching to zoom in and out",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .background(brandBlue.copy(alpha = 0.9f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 60.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (flowState != CameraFlowState.Gallery) {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            isCancelled = true
                            stopTimer()
                            if (cameraService.isRecording) {
                                cameraService.stopRecording()
                            } else {
                                recordedClips.forEach { try { File(it.url.path ?: "").delete() } catch(_: Exception){} }
                                if (flowState is CameraFlowState.ReviewingRecent) {
                                    try { File((flowState as CameraFlowState.ReviewingRecent).url.path ?: "").delete() } catch(_: Exception){}
                                }
                                recordedClips = emptyList()
                                timeElapsed = 0
                                currentPhase = CameraPhase.Mandatory(1)
                                flowState = CameraFlowState.Instruction
                                onCancel()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.weight(1f))

                if (flowState != CameraFlowState.Gallery) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (currentLiveProgress.second) Icons.Default.CheckCircle else Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (currentLiveProgress.second) Color.Green else brandOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${currentLiveProgress.first}s / $maxTotalTimeLimit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = flowState == CameraFlowState.Instruction,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.CameraEnhance, contentDescription = null, tint = brandBlue, modifier = Modifier.size(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Capture Positive Media", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Follow the on-screen steps to capture the different angles of the landmark. This video should be from a typical place where a user may see the landmark.",
                            fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f), lineHeight = 20.sp
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Bottom Controls based on Flow State
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 100.dp, top = 20.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent<CameraFlowState>(targetState = flowState, label = "FlowStateAnimation") { state ->
                    when (state) {
                        is CameraFlowState.Instruction -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(currentPhase.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    text = currentPhase.instruction,
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        flowState = CameraFlowState.Recording
                                        cameraService.startRecording()
                                        startTimer()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Start Recording", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                if (recordedClips.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            gallerySelection = recordedClips.lastOrNull()?.id ?: ""
                                            flowState = CameraFlowState.Gallery
                                        },
                                        modifier = Modifier.fillMaxWidth().height(54.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Cancel & View Captured Clips", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        is CameraFlowState.Recording -> {
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

                        is CameraFlowState.ReviewingRecent -> {
                            val uri = state.url
                            val recordedDuration = state.duration
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        try { File(uri.path ?: "").delete() } catch(_: Exception){}
                                        flowState = CameraFlowState.Instruction
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val newClip = RecordedClip(currentPhase, uri, recordedDuration)
                                        recordedClips = recordedClips + newClip
                                        gallerySelection = newClip.id
                                        flowState = CameraFlowState.Gallery
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Accept", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                            }
                        }

                        is CameraFlowState.Gallery -> {
                            // Handled entirely by the main ZStack Overlay
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }

        // MARK: - Gallery View
        if (flowState == CameraFlowState.Gallery) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

                val pagerState = rememberPagerState(pageCount = { recordedClips.size })

                // Keep pager in sync with selection
                LaunchedEffect(gallerySelection) {
                    val idx = recordedClips.indexOfFirst { it.id == gallerySelection }
                    if (idx != -1 && pagerState.currentPage != idx) {
                        pagerState.scrollToPage(idx)
                    }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    if (page < recordedClips.size) {
                        val clip = recordedClips[page]
                        Box(modifier = Modifier.fillMaxSize()) {
                            PositiveSafeVideoPlayer(uri = clip.url, modifier = Modifier.fillMaxSize())

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 60.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        deleteClip(clip)
                                    },
                                    modifier = Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Top Cancel Button
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 60.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            isCancelled = true
                            recordedClips.forEach { try { File(it.url.path ?: "").delete() } catch(_: Exception){} }
                            recordedClips = emptyList()
                            timeElapsed = 0
                            currentPhase = CameraPhase.Mandatory(1)
                            flowState = CameraFlowState.Instruction
                            onCancel()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Bottom Controls
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp).padding(bottom = 40.dp)
                ) {
                    val nextMandatory = nextRequiredPhase()
                    val timeRemaining = maxTotalTimeLimit - totalDurationElapsedInt

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total: ${totalDurationElapsedInt}s / ${maxTotalTimeLimit}s",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.Gray
                        )
                        if (nextMandatory == null) {
                            Icon(
                                imageVector = if (totalDurationElapsedInt >= minTotalTimeLimit) Icons.Default.Verified else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (totalDurationElapsedInt >= minTotalTimeLimit) Color.Green else brandOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (nextMandatory != null) {
                            Button(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    currentPhase = nextMandatory
                                    flowState = CameraFlowState.Instruction
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("Record Next Angle", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        } else {
                            if (totalDurationElapsedInt >= minTotalTimeLimit) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onDone(recordedClips.map { it.url })
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text(completionButtonTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                            } else {
                                Text(
                                    text = "Total video from all angles must be between $minTotalTimeLimit to $maxTotalTimeLimit seconds",
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }

                            if (timeRemaining > 0) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        currentPhase = CameraPhase.Optional(recordedClips.size + 1)
                                        flowState = CameraFlowState.Instruction
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Add Extra Clip", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
        }

        // Camera Error Overlay
        cameraService.errorMessage?.let { message ->
            if (!suppressNextError) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(40.dp).background(Color.DarkGray, RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = brandOrange, modifier = Modifier.size(42.dp))
                        Text("Camera Unavailable", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(message, fontSize = 15.sp, color = Color.LightGray, textAlign = TextAlign.Center)
                        Button(
                            onClick = { onCancel() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                }
            }
        }
    }
}

// MARK: - Native Wrappers

@Composable
fun PositiveVideoCameraPreview(
    @Suppress("UNUSED_PARAMETER") cameraService: Any?, // Prefixed to avoid unused parameter warning until implemented
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
fun PositiveSafeVideoPlayer(
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