package looksee.angelll.com.uifiles

import android.content.Context
import android.location.Geocoder
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.detection.*
import looksee.angelll.com.models.*
import looksee.angelll.com.services.*

private suspend fun getCityName(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    try {
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if (!addresses.isNullOrEmpty()) {
            val city = addresses[0].locality ?: ""
            val state = addresses[0].adminArea ?: ""
            if (city.isNotEmpty() && state.isNotEmpty()) return@withContext "$city, $state"
            if (city.isNotEmpty() || state.isNotEmpty()) return@withContext city.ifEmpty { state }
        }
    } catch (e: Exception) {
        Log.e("Geocoder", "Error: ${e.message}")
    }
    return@withContext String.format(Locale.US, "%.5f, %.5f", lat, lon)
}

@Composable
fun LandmarkScan(
    vm: looksee.angelll.com.viewmodels.AuthViewModel? = null,
    onTap: () -> Unit = {},
    onPinch: () -> Unit = {},
    isDetecting: Boolean,
    onIsDetectingChange: (Boolean) -> Unit,
    isNavVisible: Boolean,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val infoView = remember { VariableContainer.shared }
    val statusSyncService = remember { NearbyLandmarkService() }
    val detector = remember {
        VariableContainer.initialize(context)
        Detector.shared(context).apply {
            confidenceThreshold = 0.10f
            trackingThresholdMultiplier = 0.10f
        }
    }

    val isPopupOpen = infoView.infoView

    var zoomLevel by remember { mutableStateOf(1f) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }

    var isCameraPaused by remember { mutableStateOf(false) }
    var isWarmingUp by remember { mutableStateOf(true) }

    var liveInfoFetchJob by remember { mutableStateOf<Job?>(null) }
    var lastStatusSyncMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        VariableContainer.initialize(context)
    }

    // Immediately drop any on-screen bounding boxes/tracks when a landmark is toggled inactive
    LaunchedEffect(infoView.inactiveLandmarkIds) {
        detector.pruneInactiveDetections()
    }

    fun updatePauseState() {
        isCameraPaused = !isActive
        if (!isActive) onIsDetectingChange(false)
    }

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        zoomIndicatorVisible = true
        zoomFadeJob = coroutineScope.launch {
            delay(1200.milliseconds)
            zoomIndicatorVisible = false
        }
    }

    fun fetchLiveLandmarkInfo(landmarkId: String, lat: Double, lon: Double, label: String) {
        liveInfoFetchJob?.cancel()
        liveInfoFetchJob = coroutineScope.launch {
            try {
                val liveInfo = LiveLandmarkInfoService(context).fetchLiveInfo(landmarkId, 2.5)
                if (infoView.landmarkId == landmarkId) {
                    val remainsActive = applyLiveInfo(liveInfo, landmarkId, infoView)
                    if (!remainsActive) {
                        detector.pruneInactiveDetections()
                        return@launch
                    }

                    val realAddress = liveInfo.merchantAddress?.takeIf { it.isNotBlank() }
                    if (realAddress != null && vm != null && vm.hasActiveSubscription) {
                        vm.logScanHistory(
                            landmarkId = landmarkId,
                            label = liveInfo.label.ifBlank { label },
                            location = realAddress,
                            latitude = lat,
                            longitude = lon,
                            imageUrl = liveInfo.merchantLogoUrl?.takeIf { it.isNotBlank() } ?: infoView.merchantLogoUrl.takeIf { it.isNotBlank() } ?: liveInfo.activePromotion?.imageUrl?.takeIf { it.isNotBlank() } ?: infoView.promoImageUrl.takeIf { it.isNotBlank() } ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("LandmarkScan", "Live info unavailable: ${e.message}")
            }
        }
    }

    fun openPopup(detection: Detection) {
        liveInfoFetchJob?.cancel()
        val entry = detection.landmarkEntry()

        if (entry == null) {
            infoView.resetLandmarkDisplay()
            infoView.landmarkName = detection.displayLabel()
            infoView.landmarkConfidence = detection.confidence * 100
            infoView.landmarkDescription = "Discover more about this location."
            infoView.infoView = true
            return
        }

        val landmarkId = entry.landmarkId.trim()

        // Respect immediate local toggles first, then cached backend status
        if (landmarkId.isNotEmpty()) {
            if (VariableContainer.isLandmarkInactive(landmarkId)) {
                Log.d("LookSee_Debug_Popup", "🚫 Blocked popup for locally inactive landmark: $landmarkId")
                detector.pruneInactiveDetections()
                return
            }

            if (!infoView.isLocallyModified(landmarkId)) {
                val currentInfo = infoView.allLandmarks.firstOrNull { it.landmarkId == landmarkId }
                if (currentInfo?.isActive == false) {
                    Log.d("LookSee_Debug_Popup", "🚫 Blocked popup for backend-inactive landmark: $landmarkId")
                    detector.pruneInactiveDetections()
                    return
                }
            }
        }

        infoView.presentLandmark(
            entry = entry,
            clusterId = detection.clusterId.toIntOrNull() ?: 0,
            trainingRunId = detection.modelVersion,
            detectionConfidence = detection.confidence
        )

        if (!infoView.infoView) return

        val lat = entry.latitude
        val lon = entry.longitude
        val displayLabel = detection.displayLabel()
        val lId = entry.landmarkId
        val cachedImg = infoView.merchantLogoUrl

        if (vm != null && vm.hasActiveSubscription) {
            coroutineScope.launch {
                val locationFallback = getCityName(context, lat, lon) ?: "Current Location"

                vm.logScanHistory(
                    landmarkId = lId,
                    label = displayLabel,
                    location = locationFallback,
                    latitude = lat,
                    longitude = lon,
                    imageUrl = cachedImg.takeIf { it.isNotBlank() } ?: infoView.promoImageUrl.takeIf { it.isNotBlank() } ?: ""
                )
            }
        }

        if (landmarkId.isNotEmpty()) {
            fetchLiveLandmarkInfo(landmarkId, lat, lon, displayLabel)
        }
    }

    LaunchedEffect(isActive) {
        updatePauseState()

        if (isActive) {
            detector.pruneInactiveDetections()

            // Sync live isActive flags from backend even if user hasn't opened Map or My Landmarks
            val now = System.currentTimeMillis()
            if (now - lastStatusSyncMillis > 15_000L) {
                lastStatusSyncMillis = now
                coroutineScope.launch {
                    runCatching {
                        statusSyncService.fetchNearby(
                            latitude = 41.1809,
                            longitude = -73.1568,
                            radiusMeters = 50_000_000.0,
                            limit = 200
                        )
                        detector.pruneInactiveDetections()
                    }
                }
            }

            isWarmingUp = true
            delay(650.milliseconds)
            isWarmingUp = false
        }
    }

    val currentLabel by detector.currentLabel.collectAsState()
    LaunchedEffect(currentLabel, isActive) {
        onIsDetectingChange(isActive && !currentLabel.isNullOrBlank())
    }

    val newlyDetectedLandmark by detector.newlyDetectedLandmark.collectAsState()
    LaunchedEffect(newlyDetectedLandmark) {
        if (newlyDetectedLandmark != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val landmarkId = newlyDetectedLandmark?.landmarkEntry()?.landmarkId?.trim().orEmpty()
            if (landmarkId.isNotEmpty() && !infoView.isLocallyModified(landmarkId)) {
                // Silently verify status in background to prune inactive landmarks immediately
                launch {
                    try {
                        val liveInfo = LiveLandmarkInfoService(context).fetchLiveInfo(landmarkId, 2.5)
                        if (liveInfo.isActive == false) {
                            infoView.setLandmarkState(landmarkId = landmarkId, isActive = false, isVisibleOnMap = false)
                            detector.pruneInactiveDetections()
                        }
                    } catch (e: Exception) {
                        Log.e("LookSee_Debug_Scan", "Background live info fetch failed", e)
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight

        val lockedSafeZone = remember(width, height) {
            DetectionBox(
                left = (width.value * 0.15f),
                top = (height.value * 0.20f),
                right = (width.value * 0.85f),
                bottom = (height.value * 0.65f)
            )
        }

        ZStack(alignment = Alignment.Center) {

            CameraPreview(
                detector = detector,
                zoomLevel = zoomLevel,
                onZoomLevelChange = {
                    zoomLevel = it
                    showZoomIndicatorThenFade()
                    onTap()
                },
                showSafeZone = false,
                safeZoneRect = lockedSafeZone,
                onTap = onTap,
                onPinch = onPinch,
                isAIPaused = isCameraPaused || isPopupOpen,
                onBoxTap = { openPopup(it) },
                hideBoundingBoxes = isPopupOpen,
                modifier = Modifier.fillMaxSize()
            )

            if (isPopupOpen) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).zIndex(1f))
            }

            if (!isActive) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(2f))
            }

            AnimatedVisibility(
                visible = isWarmingUp,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(3f))
            }

            if (isActive && !isPopupOpen && zoomIndicatorVisible) {
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = Color.Black.copy(0.6f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1fx", zoomLevel),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        detector.setHideBoundingBoxes(false)
        onDispose {
            liveInfoFetchJob?.cancel()
            zoomFadeJob?.cancel()
            isCameraPaused = true
            onIsDetectingChange(false)
        }
    }
}

private fun applyLiveInfo(
    liveInfo: LiveLandmarkInfoResponse,
    landmarkId: String,
    infoView: VariableContainer
): Boolean {
    if (liveInfo.label.isNotBlank()) infoView.landmarkName = liveInfo.label
    if (liveInfo.shortDescription.isNotBlank()) infoView.landmarkDescription = liveInfo.shortDescription
    infoView.landmarkWebsiteUrl = liveInfo.websiteUrl ?: ""

    if (liveInfo.isActive == false) {
        if (!infoView.isLocallyModified(landmarkId) || VariableContainer.isLandmarkInactive(landmarkId)) {
            infoView.setLandmarkState(landmarkId = landmarkId, isActive = false, isVisibleOnMap = false)
            infoView.dismissLandmark()
            return false
        }
    }

    liveInfo.activePromotion?.let { promo ->
        if (promo.name.isNotBlank()) {
            infoView.promoName = promo.name
            infoView.promoDescription = promo.description
            infoView.promoImageUrl = promo.imageUrl ?: ""
        } else {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        }
    } ?: run {
        infoView.promoName = "No active promotion"
        infoView.promoDescription = ""
        infoView.promoImageUrl = ""
    }

    return true
}

@Composable
fun ZStack(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    content: @Composable (BoxScope.() -> Unit)
) {
    Box(modifier = modifier, contentAlignment = alignment, content = content)
}

// --- Merged from DetectionUiState.kt ---
data class DetectionHudState(
    val title: String,
    val detail: String,
    val detectionCount: Int,
    val inferenceMilliseconds: Double?,
    val isModelReady: Boolean,
    val isSyntheticPreview: Boolean,
)

fun detectorHudState(
    loadState: DetectorLoadState,
    detectionCount: Int,
    lastInferenceMilliseconds: Double,
    isPaused: Boolean,
    isSyntheticPreviewEnabled: Boolean,
): DetectionHudState {
    require(detectionCount >= 0) { "detectionCount cannot be negative." }

    if (isSyntheticPreviewEnabled) {
        return DetectionHudState(
            title = if (isPaused) "Overlay test paused" else "Overlay test active",
            detail = "Synthetic box only — no landmark model is running.",
            detectionCount = if (isPaused) 0 else detectionCount,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = true,
        )
    }

    val inferenceTime = lastInferenceMilliseconds
        .takeIf { it.isFinite() && it > 0.0 }

    return when (loadState) {
        DetectorLoadState.WaitingForRelease -> DetectionHudState(
            title = "Model unavailable",
            detail = "Camera preview is ready; waiting for a downloaded model.",
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Loading -> DetectionHudState(
            title = "Loading model",
            detail = "Preparing ${loadState.releaseIdentifier}.",
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Ready -> DetectionHudState(
            title = if (isPaused) "Detection paused" else "Model ready",
            detail = if (isPaused) {
                "Camera analysis is paused."
            } else {
                "Running ${loadState.releaseIdentifier}."
            },
            detectionCount = if (isPaused) 0 else detectionCount,
            inferenceMilliseconds = if (isPaused) null else inferenceTime,
            isModelReady = true,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Failed -> DetectionHudState(
            title = "Model could not load",
            detail = loadState.message,
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )
    }
}

// --- Merged from ViewfinderCircle.kt ---
@Composable
fun ViewfinderCircle(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Icon(
        imageVector = Icons.Default.CenterFocusStrong,
        contentDescription = "Viewfinder",
        tint = tint,
        modifier = modifier.size(70.dp)
    )
}