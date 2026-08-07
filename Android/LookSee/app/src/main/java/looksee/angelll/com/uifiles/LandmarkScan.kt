package looksee.angelll.com.uifiles

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Suppress("UNUSED_PARAMETER") // Fixed: Silences the "isNavVisible" unused warning
@Composable
fun LandmarkScanScreen(
    onTap: () -> Unit = {},
    onPinch: () -> Unit = {},
    isDetecting: MutableState<Boolean>,
    isNavVisible: MutableState<Boolean>,
    isActive: Boolean = true
) {
    val detector = remember { Detector() }
    val infoView = VariableContainer.shared
    val coroutineScope = rememberCoroutineScope()

    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }
    var isCameraPaused by remember { mutableStateOf(false) }

    var zoomFadeJob by remember { mutableStateOf<Job?>(null) }
    var liveInfoFetchJob by remember { mutableStateOf<Job?>(null) }

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
            delay(1200.milliseconds)
            zoomIndicatorVisible = false
        }
    }

    @Suppress("UNUSED_PARAMETER") // Fixed: Silences the "landmarkId" unused warning
    fun applyLiveInfo(liveInfo: LiveLandmarkInfoResponse, landmarkId: String) {
        val liveLabel = liveInfo.label.trim()
        val liveDescription = liveInfo.shortDescription.trim()
        val liveWebsiteUrl = liveInfo.websiteUrl?.trim() ?: ""

        if (liveLabel.isNotEmpty()) infoView.landmarkName = liveLabel
        if (liveDescription.isNotEmpty()) infoView.landmarkDescription = liveDescription

        infoView.landmarkWebsiteUrl = liveWebsiteUrl

        if (liveInfo.isActive == false) {
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
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
    }

    fun fetchLiveLandmarkInfo(landmarkId: String) {
        liveInfoFetchJob?.cancel()
        liveInfoFetchJob = coroutineScope.launch {
            try {
                val liveInfo = LiveLandmarkInfoService().fetchLiveInfo(
                    landmarkId = landmarkId,
                    timeoutSeconds = 2.5
                )

                if (infoView.landmarkId == landmarkId) {
                    applyLiveInfo(liveInfo, landmarkId)
                }
            } catch (e: Exception) {
                if (infoView.landmarkId == landmarkId) {
                    println("Live landmark info unavailable for $landmarkId. Keeping manifest fallback. Error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun openPopup(detection: Detection) {
        liveInfoFetchJob?.cancel()

        val entry = detection.landmarkEntry
        if (entry == null) {
            infoView.landmarkId = ""
            infoView.landmarkName = detection.displayLabel
            infoView.landmarkConfidence = detection.confidence * 100
            infoView.landmarkDescription = "Discover more about this location."
            infoView.landmarkURL = ""
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
            infoView.infoView = true
            return
        }

        infoView.presentLandmark(
            entry = entry,
            clusterId = detection.clusterID.toIntOrNull() ?: 0,
            trainingRunId = detection.modelVersion,
            detectionConfidence = detection.confidence
        )

        val landmarkId = entry.landmarkId.trim()
        if (landmarkId.isEmpty()) {
            infoView.landmarkWebsiteUrl = ""
            infoView.promoName = "No active promotion"
            infoView.promoDescription = ""
            infoView.promoImageUrl = ""
        } else {
            fetchLiveLandmarkInfo(landmarkId)
        }
    }

    DisposableEffect(Unit) {
        detector.hideBoundingBoxes = false
        updatePauseState()

        onDispose {
            liveInfoFetchJob?.cancel()
            zoomFadeJob?.cancel()
            isCameraPaused = true
            isDetecting.value = false
        }
    }

    LaunchedEffect(isActive, infoView.infoView) {
        updatePauseState()
    }

    LaunchedEffect(zoomLevel) {
        showZoomIndicatorThenFade()
        onTap()
    }

    LaunchedEffect(detector.currentLabel) {
        isDetecting.value = isActive && detector.currentLabel?.trim()?.isNotEmpty() == true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val geoWidth = maxWidth.value
        val geoHeight = maxHeight.value

        val lockedSafeZone = Rect(
            left = (geoWidth * 0.15f),
            top = (geoHeight * 0.20f),
            right = (geoWidth * 0.85f),
            bottom = (geoHeight * 0.65f)
        )

        LaunchedEffect(geoWidth, geoHeight) {
            detector.dynamicSafeZone = lockedSafeZone
        }

        val blurAmount = if (infoView.infoView) 10.dp else 0.dp

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

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
                    .blur(radius = blurAmount)
            )

            if (!isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            if (isActive && !infoView.infoView) {
                AnimatedVisibility(
                    visible = zoomIndicatorVisible,
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
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}