package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import looksee.angelll.com.detection.*
import looksee.angelll.com.models.*
import looksee.angelll.com.ui.theme.*
import looksee.angelll.com.viewmodels.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScannerSheet(
    onTextScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0.dp) },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Point at text and tap to copy",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TextScannerCameraView { scannedText ->
                    onTextScanned(scannedText)
                    onDismiss()
                }
            }
        }
    }
}

private data class TargetTextChunk(
    val text: String,
    val bounds: Rect
)

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun TextScannerCameraView(onTextTapped: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var rawTextBlocks by remember { mutableStateOf(emptyList<Text.TextBlock>()) }
    var imageWidth by remember { mutableIntStateOf(1) }
    var imageHeight by remember { mutableIntStateOf(1) }

    val initialNullCamera: Camera? = null
    var camera by remember { mutableStateOf(initialNullCamera) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(5f) }

    val isAnalyzing = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (isAnalyzing.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            isAnalyzing.set(true)
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                            val isPortrait = imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270
                            val newWidth = if (isPortrait) imageProxy.height else imageProxy.width
                            val newHeight = if (isPortrait) imageProxy.width else imageProxy.height

                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    imageWidth = newWidth
                                    imageHeight = newHeight
                                    rawTextBlocks = visionText.textBlocks
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                    isAnalyzing.set(false)
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                val boundCamera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                camera = boundCamera
                boundCamera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                    currentZoom = state.zoomRatio
                    maxZoomRatio = state.maxZoomRatio.coerceAtMost(5f)
                }
            } catch (e: Exception) {
                Log.e("LookSeeScanner", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            recognizer.close()
            executor.shutdown()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(camera) {
                detectTransformGestures { _, _, zoom, _ ->
                    camera?.let { cam ->
                        val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val newZoom = (zoomState.zoomRatio * zoom).coerceIn(
                            zoomState.minZoomRatio,
                            maxZoomRatio
                        )
                        cam.cameraControl.setZoomRatio(newZoom)
                    }
                }
            }
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val screenCenterX = containerWidth / 2f
        val screenCenterY = containerHeight / 2f

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        val visibleChunks = remember(rawTextBlocks, imageWidth, imageHeight, containerWidth, containerHeight) {
            val scaleX = containerWidth / imageWidth.toFloat()
            val scaleY = containerHeight / imageHeight.toFloat()
            val scale = maxOf(scaleX, scaleY)

            val scaledWidth = imageWidth * scale
            val scaledHeight = imageHeight * scale

            val offsetX = (containerWidth - scaledWidth) / 2f
            val offsetY = (containerHeight - scaledHeight) / 2f

            rawTextBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null

                val left = box.left * scale + offsetX
                val top = box.top * scale + offsetY
                val right = box.right * scale + offsetX
                val bottom = box.bottom * scale + offsetY

                if (right > 20f && left < containerWidth - 20f && bottom > 20f && top < containerHeight - 20f) {
                    val clampedBounds = Rect(
                        left = max(10f, left),
                        top = max(10f, top),
                        right = min(containerWidth - 10f, right),
                        bottom = min(containerHeight - 10f, bottom)
                    )
                    TargetTextChunk(block.text.trim(), clampedBounds)
                } else null
            }
        }

        val targetChunk = remember(visibleChunks, screenCenterX, screenCenterY) {
            visibleChunks.minByOrNull { chunk ->
                val chunkCenterX = (chunk.bounds.left + chunk.bounds.right) / 2f
                val chunkCenterY = (chunk.bounds.top + chunk.bounds.bottom) / 2f
                val dx = chunkCenterX - screenCenterX
                val dy = chunkCenterY - screenCenterY
                sqrt(dx * dx + dy * dy)
            }
        }

        val targetLeft = targetChunk?.bounds?.left ?: screenCenterX
        val targetTop = targetChunk?.bounds?.top ?: screenCenterY
        val targetRight = targetChunk?.bounds?.right ?: screenCenterX
        val targetBottom = targetChunk?.bounds?.bottom ?: screenCenterY

        val animSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        val smoothLeft by animateFloatAsState(targetValue = targetLeft, animationSpec = animSpec, label = "boxLeft")
        val smoothTop by animateFloatAsState(targetValue = targetTop, animationSpec = animSpec, label = "boxTop")
        val smoothRight by animateFloatAsState(targetValue = targetRight, animationSpec = animSpec, label = "boxRight")
        val smoothBottom by animateFloatAsState(targetValue = targetBottom, animationSpec = animSpec, label = "boxBottom")

        if (targetChunk != null && smoothRight > smoothLeft + 20f && smoothBottom > smoothTop + 14f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 12f
                val left = smoothLeft - pad
                val top = smoothTop - pad
                val right = smoothRight + pad
                val bottom = smoothBottom + pad

                val bracketLen = min(28f, min(right - left, bottom - top) / 3f)
                val strokeW = 3.5.dp.toPx()
                val bracketColor = Color(0xFFFFD60A)

                drawRoundRect(
                    color = Color(0xFFFFCC00).copy(alpha = 0.16f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                )

                drawLine(bracketColor, Offset(left, top + bracketLen), Offset(left, top), strokeW)
                drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), strokeW)

                drawLine(bracketColor, Offset(right - bracketLen, top), Offset(right, top), strokeW)
                drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), strokeW)

                drawLine(bracketColor, Offset(left, bottom - bracketLen), Offset(left, bottom), strokeW)
                drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeW)

                drawLine(bracketColor, Offset(right - bracketLen, bottom), Offset(right, bottom), strokeW)
                drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - bracketLen), strokeW)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val cleanLines = targetChunk.text
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        onTextTapped(cleanLines)
                    }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.70f))
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(1f, 2f, 3f).forEach { zoomLevel ->
                val isSelected = (currentZoom in (zoomLevel - 0.4f)..(zoomLevel + 0.4f))
                Text(
                    text = "${zoomLevel.toInt()}x",
                    color = if (isSelected) AppleBlue else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                    modifier = Modifier.clickable {
                        camera?.cameraControl?.setZoomRatio(zoomLevel)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkRecordScreen(
    vm: AuthViewModel,
    isActive: Boolean = true,
    archivedMedia: ArchivedMedia? = null,
    existingLandmarkId: String? = null,
    existingLabel: String? = null,
    existingDescription: String? = null,
    existingSecondsNeeded: Double? = null,
    onAddMoreMedia: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    val scrollState = rememberScrollState()
    var labelSectionY by remember { mutableIntStateOf(0) }
    var descSectionY by remember { mutableIntStateOf(0) }
    var isLabelFocused by remember { mutableStateOf(false) }
    var isDescFocused by remember { mutableStateOf(false) }

    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    // Smoothly scroll the active text field into position when the keyboard opens or focus changes,
    // without any scroll-interceptor that could prematurely close the keyboard.
    LaunchedEffect(isKeyboardVisible, isLabelFocused, isDescFocused) {
        if (isKeyboardVisible) {
            delay(150)
            if (isLabelFocused && labelSectionY > 0) {
                scrollState.animateScrollTo((labelSectionY - 40).coerceAtLeast(0))
            } else if (isDescFocused && descSectionY > 0) {
                scrollState.animateScrollTo((descSectionY - 40).coerceAtLeast(0))
            }
        }
    }

    val uploadService = remember { UploadService(context) }
    val isUploading by uploadService.isUploading.collectAsState()

    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val isHardNegativeUploading by hardNegativeUploadService.isUploading.collectAsState()

    val locationManager = remember { LocationManager(context) }
    val locationState by locationManager.state.collectAsState()

    val isAdditionalMedia = existingLandmarkId != null

    // Exact iOS duration limits:
    // Positive: 30s min / 120s max (1s min for additional media)
    // Negative: 10s min / 45s max (1s min for additional media)
    val maxPositiveVideoDuration = 120
    val maxNegativeVideoDuration = 45
    val minPositiveVideoDuration = if (existingLandmarkId != null) 1 else 30
    val negativeTargetDuration = if (existingLandmarkId != null) 1 else 10

    DisposableEffect(Unit) {
        locationManager.start()
        onDispose {
            locationManager.stop()
        }
    }

    var labelText by remember { mutableStateOf(existingLabel ?: "") }
    var shortDescription by remember { mutableStateOf(existingDescription ?: "") }
    var businessLandmarkId by remember { mutableStateOf(existingLandmarkId) }

    var pickedVideoUris by remember { mutableStateOf(emptyList<Uri>()) }
    val initialNullUri: Uri? = null
    var pickedImageUri by remember { mutableStateOf(initialNullUri) }
    var clipDurations by remember { mutableStateOf(emptyMap<Uri, Double>()) }
    val initialNullNegative: CapturedNegativeVideo? = null
    var capturedNegativeVideo by remember { mutableStateOf(initialNullNegative) }
    var showTextScanner by remember { mutableStateOf(false) }

    var showNegativeCamera by remember { mutableStateOf(false) }
    var isFormVisible by remember { mutableStateOf(archivedMedia != null) }
    var statusText by remember { mutableStateOf(if (archivedMedia != null) "Loaded archived media." else "No landmark media selected.") }
    var showBackgroundUploadAlert by remember { mutableStateOf(false) }

    var showDiscardAlert by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    val initialNullDouble: Double? = null
    var extractedLatitude by remember { mutableStateOf(initialNullDouble) }
    var extractedLongitude by remember { mutableStateOf(initialNullDouble) }
    var isStitchingVideos by remember { mutableStateOf(false) }
    val initialNullResult: PositiveSubmissionResult? = null
    var completedPositiveResult by remember { mutableStateOf(initialNullResult) }
    var isFullSubmissionComplete by remember { mutableStateOf(false) }

    LaunchedEffect(isFormVisible) {
        if (isFormVisible && archivedMedia == null) {
            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
            if (fix != null) {
                extractedLatitude = fix.latitude
                extractedLongitude = fix.longitude
                // Stop continuous GPS updates once coordinates are locked so the form doesn't recompose while typing
                locationManager.stop()
            }
        }
    }

    LaunchedEffect(locationState) {
        if (isFormVisible && archivedMedia == null && extractedLatitude == null) {
            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
            if (fix != null) {
                extractedLatitude = fix.latitude
                extractedLongitude = fix.longitude
                locationManager.stop()
            }
        }
    }

    LaunchedEffect(pickedVideoUris) {
        if (archivedMedia != null) {
            extractedLatitude = archivedMedia.latitude
            extractedLongitude = archivedMedia.longitude
        }
        withContext(Dispatchers.IO) {
            val newDurations = mutableMapOf<Uri, Double>()
            for (uri in pickedVideoUris) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val millis = timeStr?.toLongOrNull() ?: 0L
                    val actualLength = millis / 1000.0
                    newDurations[uri] = if (actualLength > 0.0) actualLength else 15.0
                } catch (e: Exception) {
                    newDurations[uri] = 15.0
                } finally {
                    retriever.release()
                }
            }
            clipDurations = newDurations
        }
    }

    val totalClipDuration = pickedVideoUris.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImageUri != null || totalClipDuration >= 1.0

    val canUpload = !isUploading && !isHardNegativeUploading && !isStitchingVideos && !isFullSubmissionComplete &&
            (if (completedPositiveResult != null) (existingLandmarkId != null || capturedNegativeVideo != null)
            else ((pickedVideoUris.isNotEmpty() || pickedImageUri != null) && labelText.isNotBlank() && shortDescription.isNotBlank() && (existingLandmarkId != null || capturedNegativeVideo != null) && hasMinimumClipDuration))

    val arePositiveDetailsLocked = isUploading || isHardNegativeUploading || completedPositiveResult != null || isFullSubmissionComplete || isAdditionalMedia

    fun clearScreen() {
        pickedVideoUris = emptyList()
        clipDurations = emptyMap()
        pickedImageUri = null

        capturedNegativeVideo?.file?.let {
            try { it.delete() } catch (e: Exception) { Log.e("Cleanup", "Failed to delete temp file", e) }
        }

        capturedNegativeVideo = null
        if (existingLandmarkId == null) {
            businessLandmarkId = null
            labelText = ""
            shortDescription = ""
        }
        isFormVisible = false
        statusText = "No landmark media selected."
    }

    fun startFullSubmission() {
        focusManager.clearFocus()
        keyboardController?.hide()
        Log.d("LookSee_Debug_Record", "Starting full submission!")
        if (completedPositiveResult == null) {
            if (!vm.hasActiveSubscription) {
                limitAlertTitle = "Subscription Required"
                limitAlertMessage = "You need an active subscription or Free Trial to upload landmarks."
                showLimitAlert = true
                return
            }
            if (existingLandmarkId == null && vm.tokenBalance <= 0) {
                limitAlertTitle = "Out of Tokens"
                limitAlertMessage = "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                showLimitAlert = true
                return
            }
        }

        coroutineScope.launch {
            val lat = extractedLatitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.latitude ?: 0.0
            val lon = extractedLongitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.longitude ?: 0.0
            val idToSave = businessLandmarkId ?: "landmark_${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val offlineManager = OfflineMediaManager.shared(context)

            if (archivedMedia != null) {
                offlineManager.updateDraft(archivedMedia, labelText, shortDescription, null)
            } else {
                if (pickedVideoUris.isNotEmpty()) {
                    val file = File(pickedVideoUris.first().path ?: "")
                    offlineManager.archiveVideo(file, lat, lon, idToSave, labelText, shortDescription, "", capturedNegativeVideo?.file, false)
                }
                if (existingLandmarkId == null) {
                    vm.tokenBalance -= 1
                    vm.activeLandmarksCount += 1
                }
            }

            Log.d("LookSee_Debug_Record", "Queued successfully. Retrying AutoUploadManager.")
            AutoUploadManager.shared(context).forceRetry()
            showBackgroundUploadAlert = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (pickedVideoUris.isEmpty() && pickedImageUri == null && !isFormVisible) {
            val navState = remember { mutableStateOf(true) }
            PositiveVideoCameraView(
                isActive = isActive,
                isNavVisible = navState,
                uiTargetDuration = maxPositiveVideoDuration,
                minTotalTimeLimit = minPositiveVideoDuration,
                maxTotalTimeLimit = maxPositiveVideoDuration,
                onDone = { uris ->
                    if (uris.size <= 1) {
                        pickedVideoUris = uris
                        isFormVisible = true
                    } else {
                        isStitchingVideos = true
                        coroutineScope.launch {
                            try {
                                val stitchedUri = withContext(Dispatchers.Default) {
                                    VideoMerger.mergeAndValidate(context, uris, 1.0)
                                }
                                pickedVideoUris = listOf(stitchedUri)
                            } catch (e: Exception) {
                                Log.e("LookSee", "Merge failed", e)
                                pickedVideoUris = listOf(uris.first())
                            } finally {
                                isStitchingVideos = false
                                isFormVisible = true
                            }
                        }
                    }
                },
                onCancel = onDismiss
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                        .verticalScroll(scrollState)
                        .padding(bottom = 120.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    if (pickedVideoUris.isNotEmpty()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (hasMinimumClipDuration) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                                Text("${String.format(Locale.US, "%.1f", totalClipDuration)}s total — ready to upload", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500))
                            }

                            pickedVideoUris.forEach { uri ->
                                Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                                    PositiveSafeVideoPlayer(uri = uri, modifier = Modifier.fillMaxSize())
                                    if (!arePositiveDetailsLocked && !isAdditionalMedia) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(12.dp)
                                                .size(32.dp)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    clearScreen()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(modifier = Modifier.padding(16.dp).fillMaxWidth(), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.NearMe, contentDescription = null, tint = AppleBlue)
                            Column {
                                if (extractedLatitude != null && extractedLongitude != null) {
                                    Text(String.format(Locale.US, "%.6f, %.6f", extractedLatitude, extractedLongitude), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                    Text("Location Locked", fontSize = 13.sp, color = Color.Gray)
                                } else {
                                    val fix = (locationState as? LookSeeLocationState.Ready)?.fix
                                    if (fix != null) {
                                        Text(String.format(Locale.US, "%.6f, %.6f", fix.latitude, fix.longitude), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                        Text("Accuracy: ±${fix.accuracyMeters.toInt()}m", fontSize = 13.sp, color = Color.Gray)
                                    } else {
                                        Text("Requesting location...", fontSize = 15.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }

                    if (isFormVisible) {
                        // LANDMARK LABEL SECTION
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    labelSectionY = coords.positionInParent().y.roundToInt()
                                }
                        ) {
                            Text("LANDMARK LABEL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            Surface(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp)) {
                                Box(modifier = Modifier.padding(if (isAdditionalMedia) 16.dp else 8.dp)) {
                                    if (isAdditionalMedia) {
                                        Text(
                                            text = labelText.ifEmpty { "Untitled Landmark" },
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        OutlinedTextField(
                                            value = labelText,
                                            onValueChange = { labelText = it },
                                            placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                capitalization = KeyboardCapitalization.Words,
                                                imeAction = ImeAction.Next
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { state ->
                                                    isLabelFocused = state.isFocused
                                                },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color.Transparent,
                                                unfocusedBorderColor = Color.Transparent,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            ),
                                            enabled = !arePositiveDetailsLocked
                                        )
                                    }
                                }
                            }
                        }

                        if (businessLandmarkId != null) {
                            Text("ID: $businessLandmarkId", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }

                        Spacer(Modifier.height(10.dp))

                        // SHORT DESCRIPTION SECTION
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    descSectionY = coords.positionInParent().y.roundToInt()
                                }
                        ) {
                            Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            Surface(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp)) {
                                Box(modifier = Modifier.padding(if (isAdditionalMedia) 16.dp else 8.dp).fillMaxWidth().defaultMinSize(minHeight = if (isAdditionalMedia) 60.dp else 100.dp)) {
                                    if (isAdditionalMedia) {
                                        Text(
                                            text = shortDescription.ifEmpty { "No description provided." },
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        OutlinedTextField(
                                            value = shortDescription,
                                            onValueChange = { shortDescription = it },
                                            placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                                            keyboardOptions = KeyboardOptions(
                                                capitalization = KeyboardCapitalization.Sentences
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 36.dp)
                                                .onFocusChanged { state ->
                                                    isDescFocused = state.isFocused
                                                },
                                            minLines = 3,
                                            maxLines = 6,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color.Transparent,
                                                unfocusedBorderColor = Color.Transparent,
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            ),
                                            enabled = !arePositiveDetailsLocked
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .size(36.dp)
                                                .background(AppleBlue, CircleShape)
                                                .clickable {
                                                    focusManager.clearFocus()
                                                    keyboardController?.hide()
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    showTextScanner = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (!isAdditionalMedia) {
                            Spacer(Modifier.height(20.dp))
                            Surface(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp)) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Negative Background", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Icon(
                                            imageVector = if (capturedNegativeVideo != null) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (capturedNegativeVideo != null) Color.Green else Color(0xFFFFA500)
                                        )
                                    }
                                    Text("Record a >= ${negativeTargetDuration}s video panning the area. Do NOT include the landmark.", fontSize = 14.sp, color = Color.Gray)
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            showNegativeCamera = true
                                        },
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (capturedNegativeVideo == null) "Record Negative" else "Retake Negative", fontWeight = FontWeight.Bold)
                                    }

                                    val currentNeg = capturedNegativeVideo
                                    if (currentNeg != null) {
                                        Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))) {
                                            PositiveSafeVideoPlayer(uri = Uri.fromFile(currentNeg.file), modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); startFullSubmission() },
                                modifier = Modifier.weight(1f).height(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (canUpload) AppleBlue else Color(0xFF2C2C2E)),
                                shape = RoundedCornerShape(16.dp),
                                enabled = canUpload
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (archivedMedia != null) "Upload Draft" else (if (isAdditionalMedia) "Upload Additional Media" else "Upload Landmark"),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (canUpload) Color.White else Color.Gray
                                )
                            }

                            if (archivedMedia == null) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        showDiscardAlert = true
                                    },
                                    modifier = Modifier.size(60.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                                }
                            }
                        }
                    }
                }

                // Keyboard accessory bar (stays anchored cleanly above the keyboard)
                if (isKeyboardVisible) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF2C2C2E),
                        tonalElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isLabelFocused) "Landmark Label" else if (isDescFocused) "Short Description" else "",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            TextButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Done", color = AppleBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (isStitchingVideos) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).zIndex(100f), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.background(Color(0xFF1E1E24), RoundedCornerShape(24.dp)).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Processing Video", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Please wait a moment.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
        }

        if (showNegativeCamera) {
            Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
                NegativeVideoCameraView(
                    uiTargetDuration = maxNegativeVideoDuration,
                    minTotalTimeLimit = negativeTargetDuration,
                    maxTotalTimeLimit = maxNegativeVideoDuration,
                    onDone = { video ->
                        capturedNegativeVideo = video
                        showNegativeCamera = false
                    },
                    onDismiss = {
                        showNegativeCamera = false
                    }
                )
            }
        }

        if (showTextScanner) {
            TextScannerSheet(
                onTextScanned = { newText: String ->
                    shortDescription = if (shortDescription.isBlank()) newText else "$shortDescription\n$newText"
                },
                onDismiss = { showTextScanner = false }
            )
        }
    }

    if (showBackgroundUploadAlert) {
        AlertDialog(
            onDismissRequest = { showBackgroundUploadAlert = false; onDismiss() },
            title = { Text("Upload Queued!", color = Color.White) },
            text = { Text("Your landmark has been securely queued! It will upload in the background. Feel free to keep using the app, but please make sure to leave it open until the upload finishes.", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = { showBackgroundUploadAlert = false; clearScreen() }) {
                    Text(if (isAdditionalMedia) "Record More" else "Record Another", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { showBackgroundUploadAlert = false; onDismiss() }) { Text("Done", color = Color.White) } },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle, color = Color.White) },
            text = { Text(limitAlertMessage, color = Color.LightGray) },
            confirmButton = { TextButton(onClick = { showLimitAlert = false }) { Text("OK", color = Color.White) } },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    if (showDiscardAlert) {
        AlertDialog(
            onDismissRequest = { showDiscardAlert = false },
            title = { Text("Discard this upload?", color = Color.White) },
            text = { Text("This will remove the media and clear the form.", color = Color.LightGray) },
            confirmButton = { TextButton(onClick = { showDiscardAlert = false; clearScreen() }) { Text("Discard", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDiscardAlert = false }) { Text("Cancel", color = Color.White) } },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}