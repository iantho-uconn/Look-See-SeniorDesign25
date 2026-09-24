package looksee.angelll.com.uifiles

import android.content.Context
import android.location.Geocoder
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.detection.*
import looksee.angelll.com.models.*
import looksee.angelll.com.services.*
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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
    val detector = remember { Detector.shared(context) }

    var isPopupOpen by remember { mutableStateOf(infoView.infoView) }
    LaunchedEffect(Unit) {
        while (true) {
            if (isPopupOpen != infoView.infoView) {
                isPopupOpen = infoView.infoView
            }
            delay(100)
        }
    }

    var zoomLevel by remember { mutableStateOf(1f) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }
    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }

    var isCameraPaused by remember { mutableStateOf(false) }
    var showThresholdControls by remember { mutableStateOf(false) }

    var liveInfoFetchJob by remember { mutableStateOf<Job?>(null) }

    var confidenceThreshold by remember { mutableStateOf(detector.confidenceThreshold) }
    var thresholdMultiplier by remember { mutableStateOf(detector.trackingThresholdMultiplier) }

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
                    applyLiveInfo(liveInfo, infoView)

                    val realAddress = liveInfo.merchantAddress?.takeIf { it.isNotBlank() }
                    if (realAddress != null && vm != null && vm.hasActiveSubscription) {
                        vm.logScanHistory(
                            landmarkId = landmarkId,
                            label = liveInfo.label.ifBlank { label },
                            location = realAddress,
                            latitude = lat,
                            longitude = lon,
                            imageUrl = liveInfo.merchantLogoUrl ?: infoView.merchantLogoUrl
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

        infoView.infoView = true
        isPopupOpen = true

        if (entry == null) {
            infoView.resetLandmarkDisplay()
            infoView.landmarkName = detection.displayLabel()
            infoView.landmarkConfidence = detection.confidence * 100
            infoView.landmarkDescription = "Discover more about this location."
            return
        }

        infoView.presentLandmark(
            entry = entry,
            clusterId = detection.clusterId.toIntOrNull() ?: 0,
            trainingRunId = detection.modelVersion,
            detectionConfidence = detection.confidence
        )

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
                    imageUrl = cachedImg
                )
            }
        }

        if (entry.landmarkId.isNotBlank()) {
            fetchLiveLandmarkInfo(entry.landmarkId, lat, lon, displayLabel)
        }
    }

    LaunchedEffect(isActive) {
        updatePauseState()
    }

    val currentLabel by detector.currentLabel.collectAsState()
    LaunchedEffect(currentLabel, isActive) {
        onIsDetectingChange(isActive && !currentLabel.isNullOrBlank())
    }

    val newlyDetectedLandmark by detector.newlyDetectedLandmark.collectAsState()
    LaunchedEffect(newlyDetectedLandmark) {
        if (newlyDetectedLandmark != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

            if (infoView.infoView) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).zIndex(1f))
            }

            if (!isActive) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(2f))
            }

            if (isActive && !isPopupOpen) {
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 120.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
                    IconButton(
                        onClick = { showThresholdControls = !showThresholdControls },
                        modifier = Modifier.background(Color.Black.copy(0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (showThresholdControls) Icons.Default.Close else Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isActive && !isPopupOpen && showThresholdControls,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 180.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
                    Surface(
                        color = Color.Black.copy(0.7f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "${(confidenceThreshold * 100).toInt()}% - ${(confidenceThreshold * thresholdMultiplier * 100).toInt()}%",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )

                            Slider(
                                value = confidenceThreshold,
                                onValueChange = {
                                    confidenceThreshold = it
                                    detector.confidenceThreshold = it
                                },
                                valueRange = 0.1f..0.95f,
                                colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                            )
                            Text("Threshold", color = Color.White, fontSize = 10.sp)

                            Slider(
                                value = thresholdMultiplier,
                                onValueChange = {
                                    thresholdMultiplier = it
                                    detector.trackingThresholdMultiplier = it
                                },
                                valueRange = 0.1f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                            )
                            Text("Range Multiplier", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
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

private fun applyLiveInfo(liveInfo: LiveLandmarkInfoResponse, infoView: VariableContainer) {
    if (liveInfo.label.isNotBlank()) infoView.landmarkName = liveInfo.label
    if (liveInfo.shortDescription.isNotBlank()) infoView.landmarkDescription = liveInfo.shortDescription
    infoView.landmarkWebsiteUrl = liveInfo.websiteUrl ?: ""

    if (liveInfo.isActive == false) {
        infoView.promoName = "No active promotion"
        return
    }

    liveInfo.activePromotion?.let { promo ->
        infoView.promoName = promo.name
        infoView.promoDescription = promo.description
        infoView.promoImageUrl = promo.imageUrl ?: ""
    } ?: run {
        infoView.promoName = "No active promotion"
    }
}

@Composable
fun ZStack(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    content: @Composable (BoxScope.() -> Unit)
) {
    Box(modifier = modifier, contentAlignment = alignment, content = content)
}