package looksee.angelll.com.uifiles

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.services.LiveLandmarkInfoService
import looksee.angelll.com.models.Detection
import looksee.angelll.com.models.LiveLandmarkInfoResponse

@Composable
fun LandmarkScan(
    onTap: () -> Unit = {},
    onPinch: () -> Unit = {},
    isDetecting: MutableState<Boolean>,
    isNavVisible: MutableState<Boolean>, // Tells the Ad if the bottom nav is currently on screen
    isActive: Boolean = true
) {
    // Shared state and detector instance
    val detector = remember { Detector() }
    val infoView = VariableContainer.shared
    val coroutineScope = rememberCoroutineScope()

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }

    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }
    var liveInfoFetchJob by remember { mutableStateOf<Job?>(null) }
    var isCameraPaused by remember { mutableStateOf(false) }

    // MARK: - Internal Methods
    fun updatePauseState() {
        isCameraPaused = !isActive || infoView.infoView
        if (!isActive) {
            isDetecting.value = false
        }
    }

    fun showZoomIndicatorThenFade() {
        zoomFadeJob?.cancel()
        zoomIndicatorVisible = true

        zoomFadeJob = coroutineScope.launch {
            delay(1200) // 1.2 seconds equivalent
            zoomIndicatorVisible = false
        }
    }

    fun applyLiveInfo(liveInfo: LiveLandmarkInfoResponse, landmarkId: String) {
        val liveLabel = liveInfo.label.trim()
        val liveDescription = liveInfo.shortDescription.trim()
        val liveWebsiteUrl = liveInfo.websiteUrl?.trim() ?: ""

        if (liveLabel.isNotEmpty()) infoView.landmarkName = liveLabel
        if (liveDescription.isNotEmpty()) infoView.landmarkDescription = liveDescription
        infoView.landmarkWebsiteUrl = liveWebsiteUrl

        if (liveWebsiteUrl.isNotEmpty()) {
            Log.d("LandmarkScan", "🔗 Live website URL applied for $landmarkId: $liveWebsiteUrl")
        } else {
            Log.d("LandmarkScan", "ℹ️ No live website URL returned for $landmarkId")
        }

        if (liveInfo.isActive == false) {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
            Log.d("LandmarkScan", "ℹ️ Live landmark info says $landmarkId is inactive.")
            return
        }

        val promotion = liveInfo.activePromotion
        if (promotion != null) {
            val promoName = promotion.name.trim()
            val promoDescription = promotion.description.trim()
            val promoImageUrl = promotion.imageUrl?.trim() ?: ""

            if (promoName.isNotEmpty()) {
                infoView.promoName = promoName
                infoView.promoDescription = promoDescription
                infoView.promoImageUrl = promoImageUrl

                if (promoImageUrl.isNotEmpty()) {
                    Log.d("LandmarkScan", "🖼️ Live promotion image URL applied for $landmarkId: $promoImageUrl")
                }
            } else {
                infoView.promoName = "No active promotion"
                infoView.promoDescription = ""
                infoView.promoImageUrl = ""
            }
        } else {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        }

        Log.d("LandmarkScan", "✅ Live landmark info applied for $landmarkId")
    }

    fun fetchLiveLandmarkInfo(landmarkId: String) {
        liveInfoFetchJob?.cancel()

        liveInfoFetchJob = coroutineScope.launch {
            try {
                // Timeout parameter in milliseconds
                val liveInfo = LiveLandmarkInfoService.fetchLiveInfo(landmarkId, 2500L)

                if (infoView.landmarkId != landmarkId) {
                    Log.d("LandmarkScan", "ℹ️ Ignoring stale live-info response for $landmarkId")
                    return@launch
                }
                applyLiveInfo(liveInfo, landmarkId)

            } catch (e: Exception) {
                if (e is CancellationException) throw e

                if (infoView.landmarkId != landmarkId) {
                    Log.d("LandmarkScan", "ℹ️ Ignoring stale live-info error for $landmarkId")
                    return@launch
                }
                Log.w("LandmarkScan", "⚠️ Live landmark info unavailable for $landmarkId. Keeping manifest fallback. Error: ${e.localizedMessage}")
            }
        }
    }

    fun openPopup(detection: Detection) {
        liveInfoFetchJob?.cancel()

        val entry = detection.landmarkEntry
        if (entry == null) {
            infoView.landmarkId = ""
            infoView.landmarkName = detection.displayLabel
            infoView.landmarkConfidence = detection.confidence * 100f
            infoView.landmarkDescription = "Discover more about this location."
            infoView.landmarkURL = ""
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
            infoView.infoView = true
            return
        }

        // Open immediately from the local manifest.
        infoView.presentLandmark(
            entry = entry,
            clusterId = detection.clusterID.toIntOrNull() ?: 0,
            trainingRunId = detection.modelVersion,
            detectionConfidence = detection.confidence
        )

        val landmarkId = entry.landmarkId.trim()

        if (landmarkId.isEmpty()) {
            Log.d("LandmarkScan", "⚠️ No landmarkId found on detection. Using manifest fallback only.")
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        } else {
            Log.d("LandmarkScan", "🔎 Fetching live landmark info for landmarkId: $landmarkId")
            fetchLiveLandmarkInfo(landmarkId)
        }
    }

    // MARK: - Layout Body
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val geoWidth = maxWidth.value
        val geoHeight = maxHeight.value

        // Equivalent math: 15% x, 20% y, 70% width, 45% height
        val lockedSafeZone = Rect(
            left = geoWidth * 0.15f,
            top = geoHeight * 0.20f,
            right = (geoWidth * 0.15f) + (geoWidth * 0.70f),
            bottom = (geoHeight * 0.20f) + (geoHeight * 0.45f)
        )

        val blurAmount = if (infoView.infoView) 10.dp else 0.dp

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {

            // Core Camera Preview with NMS-enabled Detector integration
            CameraPreview(
                detector = detector,
                zoomLevel = zoomLevel,
                onZoomChange = { zoomLevel = it },
                showSafeZone = false,
                safeZoneRect = lockedSafeZone,
                onTap = onTap,
                onPinch = onPinch,
                isAIPaused = isCameraPaused,
                onBoxTap = { detection ->
                    // THIS NOW OPENS THE SLIDE-UP SHEET WHEN THE GREEN BOX IS TAPPED!
                    openPopup(detection)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurAmount)
            )

            // Black overlay when inactive
            if (!isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // PopUp is presented by Buttons at the root level so it
            // always appears above the app chrome.

            // Zoom Indicator
            AnimatedVisibility(
                visible = isActive && !infoView.infoView && zoomIndicatorVisible,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", zoomLevel),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Side Effects (Equivalent to SwiftUI modifiers)
        LaunchedEffect(zoomLevel) {
            showZoomIndicatorThenFade()
            onTap()
        }

        LaunchedEffect(detector.currentLabel) {
            val label = detector.currentLabel
            isDetecting.value = isActive && !label.isNullOrBlank()
        }

        LaunchedEffect(isActive, infoView.infoView) {
            updatePauseState()
        }

        DisposableEffect(Unit) {
            detector.dynamicSafeZone = lockedSafeZone
            // Keep the green detection boxes visible while testing.
            detector.hideBoundingBoxes = false
            updatePauseState()

            onDispose {
                liveInfoFetchJob?.cancel()
                zoomFadeJob?.cancel()
                isCameraPaused = true
                isDetecting.value = false
            }
        }
    }
}