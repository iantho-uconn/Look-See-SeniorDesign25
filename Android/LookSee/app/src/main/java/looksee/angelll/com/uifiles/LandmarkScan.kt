package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive as coroutineIsActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("DefaultLocale")
@Composable
fun LandmarkScan(
    onTap: () -> Unit = {},
    onPinch: () -> Unit = {},
    isDetecting: Boolean,
    onIsDetectingChange: (Boolean) -> Unit,
    isNavVisible: Boolean,
    isScannerActive: Boolean = true
) {
    val context = LocalContext.current
    val detector = remember { Detector() }

    // Using your app's real VariableContainer that already exists in this package
    val infoView = VariableContainer

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }
    var isCameraPaused by remember { mutableStateOf(false) }
    var showThresholdControls by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }
    var liveInfoFetchJob by remember { mutableStateOf<Job?>(null) }

    // Convert StateFlow to Compose State for recomposition observation
    val isInfoViewVisible by infoView.infoView.collectAsState(initial = false)

    fun applyLiveInfo(liveInfo: LiveLandmarkInfoResponse, landmarkId: String) {
        val liveLabel = liveInfo.label.trim()
        val liveDescription = liveInfo.shortDescription.trim()
        val liveWebsiteUrl = liveInfo.websiteUrl?.trim() ?: ""

        if (liveLabel.isNotEmpty()) infoView.landmarkName.value = liveLabel
        if (liveDescription.isNotEmpty()) infoView.landmarkDescription.value = liveDescription
        infoView.landmarkWebsiteUrl.value = liveWebsiteUrl

        if (liveInfo.isActive == false) {
            infoView.promoName.value = "No active promotion"
            infoView.promoDescription.value = ""
            infoView.promoImageUrl.value = ""
            return
        }

        val promotion = liveInfo.activePromotion
        if (promotion != null) {
            val promoName = promotion.name.trim()
            val promoDescription = promotion.description.trim()
            val promoImageUrl = promotion.imageUrl?.trim() ?: ""

            if (promoName.isNotEmpty()) {
                infoView.promoName.value = promoName
                infoView.promoDescription.value = promoDescription
                infoView.promoImageUrl.value = promoImageUrl
            } else {
                infoView.promoName.value = "No active promotion"
                infoView.promoDescription.value = ""
                infoView.promoImageUrl.value = ""
            }
        } else {
            infoView.promoName.value = "No active promotion"
            infoView.promoDescription.value = ""
            infoView.promoImageUrl.value = ""
        }
    }

    fun fetchLiveLandmarkInfo(landmarkId: String) {
        liveInfoFetchJob?.cancel()

        liveInfoFetchJob = coroutineScope.launch {
            try {
                val liveInfo = LiveLandmarkInfoService().fetchLiveInfo(
                    landmarkId = landmarkId,
                    timeoutSeconds = 2.5
                )

                if (coroutineIsActive) {
                    if (infoView.landmarkId.value != landmarkId) return@launch
                    applyLiveInfo(liveInfo, landmarkId)
                }
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    fun openPopup(detection: Detection) {
        liveInfoFetchJob?.cancel()

        val entry = detection.landmarkEntry
        if (entry == null) {
            infoView.landmarkId.value = ""
            infoView.landmarkName.value = detection.displayLabel
            infoView.landmarkConfidence.value = detection.confidence * 100f
            infoView.landmarkDescription.value = "Discover more about this location."
            infoView.landmarkURL.value = ""
            infoView.landmarkWebsiteUrl.value = ""
            infoView.promoName.value = "No active promotion"
            infoView.promoDescription.value = ""
            infoView.promoImageUrl.value = ""
            infoView.infoView.value = true
            return
        }

        infoView.presentLandmark(
            context = context,
            entry = entry,
            clusterId = detection.clusterID.toIntOrNull() ?: 0,
            trainingRunId = detection.modelVersion,
            detectionConfidence = detection.confidence
        )

        val landmarkId = entry.landmarkId.trim()

        if (landmarkId.isEmpty()) {
            infoView.landmarkWebsiteUrl.value = ""
            infoView.promoName.value = "No active promotion"
            infoView.promoDescription.value = ""
            infoView.promoImageUrl.value = ""
        } else {
            fetchLiveLandmarkInfo(landmarkId)
        }
    }

    fun updatePauseState() {
        isCameraPaused = !isScannerActive
        if (!isScannerActive) {
            onIsDetectingChange(false)
        }
    }

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        zoomIndicatorVisible = true

        zoomFadeJob = coroutineScope.launch {
            delay(1200.milliseconds)
            if (coroutineIsActive) {
                zoomIndicatorVisible = false
            }
        }
    }

    LaunchedEffect(Unit) {
        detector.hideBoundingBoxes = false
        updatePauseState()
    }

    LaunchedEffect(isScannerActive) { updatePauseState() }

    LaunchedEffect(isInfoViewVisible) { updatePauseState() }

    DisposableEffect(Unit) {
        onDispose {
            liveInfoFetchJob?.cancel()
            zoomFadeJob?.cancel()
            isCameraPaused = true
            onIsDetectingChange(false)
        }
    }

    LaunchedEffect(zoomLevel) {
        if (zoomLevel != 1.0f) {
            showZoomIndicatorThenFade()
            onTap()
        }
    }

    LaunchedEffect(detector.currentLabel) {
        val newLabel = detector.currentLabel
        val detecting = isScannerActive && newLabel != null && newLabel.trim().isNotEmpty()
        onIsDetectingChange(detecting)
    }

    // UI Layout
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val lockedSafeZone = Rect(
            left = maxWidth.value * 0.15f,
            top = maxHeight.value * 0.20f,
            right = maxWidth.value * 0.85f,
            bottom = maxHeight.value * 0.65f
        )

        LaunchedEffect(maxWidth, maxHeight) {
            detector.dynamicSafeZone = lockedSafeZone
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val blurAmount = if (isInfoViewVisible) 10.dp else 0.dp

            CameraPreview(
                detector = detector,
                zoomLevel = zoomLevel,
                onZoomLevelChange = { zoomLevel = it },
                showSafeZone = false,
                safeZoneRect = lockedSafeZone,
                onTap = onTap,
                onPinch = onPinch,
                isAIPaused = isCameraPaused,
                onBoxTap = { detection ->
                    openPopup(detection)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurAmount)
            )

            if (!isScannerActive) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }

            // Confidence Slider Button
            if (isScannerActive && !isInfoViewVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    IconButton(
                        onClick = { showThresholdControls = !showThresholdControls },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (showThresholdControls) Icons.Default.Close else Icons.Default.Tune,
                            contentDescription = "Threshold Controls",
                            tint = Color.White
                        )
                    }
                }
            }

            // Slider Panel
            AnimatedVisibility(
                visible = isScannerActive && !isInfoViewVisible && showThresholdControls,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 120.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(vertical = 16.dp, horizontal = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(
                                onClick = { showThresholdControls = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                            }
                        }

                        Text(
                            text = "${(detector.confidenceThreshold * 100).toInt()}% - ${(detector.confidenceThreshold * detector.thresholdRangeMultiplier * 100).toInt()}%",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        // Vertical Slider 1 (Rotated)
                        Box(modifier = Modifier.size(width = 20.dp, height = 120.dp), contentAlignment = Alignment.Center) {
                            Slider(
                                value = detector.confidenceThreshold,
                                onValueChange = { detector.confidenceThreshold = it },
                                valueRange = 0.1f..0.95f,
                                steps = 16,
                                colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green),
                                modifier = Modifier
                                    .requiredWidth(120.dp)
                                    .rotate(-90f)
                            )
                        }

                        Text(
                            text = "threshold (0.1-0.95) ${(detector.confidenceThreshold * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 10.sp
                        )

                        // Vertical Slider 2 (Rotated)
                        Box(modifier = Modifier.size(width = 20.dp, height = 120.dp), contentAlignment = Alignment.Center) {
                            Slider(
                                value = detector.thresholdRangeMultiplier,
                                onValueChange = { detector.thresholdRangeMultiplier = it },
                                valueRange = 0.1f..1.0f,
                                steps = 17,
                                colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green),
                                modifier = Modifier
                                    .requiredWidth(120.dp)
                                    .rotate(-90f)
                            )
                        }

                        Text(
                            text = "range (0.1-1.0) ${((detector.thresholdRangeMultiplier * 100.0).roundToInt() / 100.0)}",
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Zoom Indicator
            AnimatedVisibility(
                visible = isScannerActive && !isInfoViewVisible && zoomIndicatorVisible,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 110.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1fx", zoomLevel),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// =========================================================================
// MOCKS FOR MISSING FILES
// (Leaving these here so your file compiles until you add the real ones!)
// =========================================================================

class Detector {
    var dynamicSafeZone: Rect = Rect.Zero
    var hideBoundingBoxes: Boolean = false
    var currentLabel: String? by mutableStateOf(null)

    var confidenceThreshold by mutableFloatStateOf(0.5f)
    var thresholdRangeMultiplier by mutableFloatStateOf(0.8f)
}

data class LandmarkEntry(val landmarkId: String)

data class Detection(
    val landmarkEntry: LandmarkEntry?,
    val displayLabel: String,
    val confidence: Float,
    val clusterID: String,
    val modelVersion: String
)

class LiveLandmarkInfoService {
    suspend fun fetchLiveInfo(landmarkId: String, timeoutSeconds: Double): LiveLandmarkInfoResponse {
        delay(500)
        return LiveLandmarkInfoResponse("Mock Label", "Mock Desc", "https://mock.com", true, null)
    }
}

data class LiveLandmarkInfoResponse(
    val label: String,
    val shortDescription: String,
    val websiteUrl: String?,
    val isActive: Boolean?,
    val activePromotion: Promotion?
)

data class Promotion(
    val name: String,
    val description: String,
    val imageUrl: String?
)

@Composable
fun CameraPreview(
    detector: Detector,
    zoomLevel: Float,
    onZoomLevelChange: (Float) -> Unit,
    showSafeZone: Boolean,
    safeZoneRect: Rect,
    onTap: () -> Unit,
    onPinch: () -> Unit,
    isAIPaused: Boolean,
    onBoxTap: (Detection) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.DarkGray))
}