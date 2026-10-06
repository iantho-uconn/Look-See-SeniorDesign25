package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
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
    // 🚀 THE FIX: Force the sheet to skip the half-way expanded state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState, // 🚀 Apply the state here
        containerColor = Color(0xFF1C1C1E),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0.dp) },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tap highlighted text to copy",
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

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun TextScannerCameraView(onTextTapped: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var recognizedTextBlocks by remember { mutableStateOf<List<Text.TextBlock>>(emptyList()) }
    var imageWidth by remember { mutableIntStateOf(1) }
    var imageHeight by remember { mutableIntStateOf(1) }

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
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                            val isPortrait = imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270
                            val newWidth = if (isPortrait) imageProxy.height else imageProxy.width
                            val newHeight = if (isPortrait) imageProxy.width else imageProxy.height

                            if (imageWidth != newWidth || imageHeight != newHeight) {
                                imageWidth = newWidth
                                imageHeight = newHeight
                            }

                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    recognizedTextBlocks = visionText.textBlocks
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("LookSeeScanner", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            recognizer.close()
            executor.shutdown()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        recognizedTextBlocks.forEach { block ->
            block.boundingBox?.let { boundingBox ->
                val scaleX = containerWidth / imageWidth
                val scaleY = containerHeight / imageHeight
                val scale = maxOf(scaleX, scaleY)

                val scaledWidth = imageWidth * scale
                val scaledHeight = imageHeight * scale

                val offsetX = (containerWidth - scaledWidth) / 2f
                val offsetY = (containerHeight - scaledHeight) / 2f

                val left = boundingBox.left * scale + offsetX
                val top = boundingBox.top * scale + offsetY
                val right = boundingBox.right * scale + offsetX
                val bottom = boundingBox.bottom * scale + offsetY

                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { left.toDp() },
                            y = with(density) { top.toDp() }
                        )
                        .size(
                            width = with(density) { (right - left).toDp() },
                            height = with(density) { (bottom - top).toDp() }
                        )
                        .background(Color.Yellow.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .border(2.dp, Color.Yellow, RoundedCornerShape(4.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTextTapped(block.text)
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
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val uploadService = remember { UploadService(context) }
    val isUploading by uploadService.isUploading.collectAsState()

    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val isHardNegativeUploading by hardNegativeUploadService.isUploading.collectAsState()

    val locationManager = remember { LocationManager(context) }
    val locationState by locationManager.state.collectAsState()

    val isAdditionalMedia = existingLandmarkId != null

    DisposableEffect(Unit) {
        locationManager.start()
        onDispose {
            locationManager.stop()
        }
    }

    var labelText by remember { mutableStateOf(existingLabel ?: "") }
    var shortDescription by remember { mutableStateOf(existingDescription ?: "") }
    var businessLandmarkId by remember { mutableStateOf(existingLandmarkId) }

    var pickedVideoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var clipDurations by remember { mutableStateOf<Map<Uri, Double>>(emptyMap()) }
    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    var showTextScanner by remember { mutableStateOf(false) }

    var showNegativeCamera by remember { mutableStateOf(false) }
    var isFormVisible by remember { mutableStateOf(archivedMedia != null) }
    var statusText by remember { mutableStateOf(if (archivedMedia != null) "Loaded archived media." else "No landmark media selected.") }
    var showBackgroundUploadAlert by remember { mutableStateOf(false) }

    var showDiscardAlert by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    var extractedLatitude by remember { mutableStateOf<Double?>(null) }
    var extractedLongitude by remember { mutableStateOf<Double?>(null) }
    var isStitchingVideos by remember { mutableStateOf(false) }
    var completedPositiveResult by remember { mutableStateOf<PositiveSubmissionResult?>(null) }
    var isFullSubmissionComplete by remember { mutableStateOf(false) }

    LaunchedEffect(isFormVisible) {
        if (isFormVisible && archivedMedia == null) {
            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
            if (fix != null) {
                extractedLatitude = fix.latitude
                extractedLongitude = fix.longitude
            }
        }
    }

    LaunchedEffect(locationState) {
        if (isFormVisible && archivedMedia == null && extractedLatitude == null) {
            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
            if (fix != null) {
                extractedLatitude = fix.latitude
                extractedLongitude = fix.longitude
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

    val uiTargetDuration = if (existingSecondsNeeded != null) ceil(existingSecondsNeeded).toInt() else if (existingLandmarkId != null) 1 else 30
    val negativeTargetDuration = if (existingLandmarkId != null) 1 else 10
    val totalClipDuration = pickedVideoUris.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImageUri != null || totalClipDuration >= 1.0

    val canUpload = !isUploading && !isHardNegativeUploading && !isStitchingVideos && !isFullSubmissionComplete &&
            (if (completedPositiveResult != null) (existingLandmarkId != null || capturedNegativeVideo != null)
            else ( (pickedVideoUris.isNotEmpty() || pickedImageUri != null) && labelText.isNotBlank() && shortDescription.isNotBlank() && (existingLandmarkId != null || capturedNegativeVideo != null) && hasMinimumClipDuration ))

    val arePositiveDetailsLocked = isUploading || isHardNegativeUploading || completedPositiveResult != null || isFullSubmissionComplete || isAdditionalMedia

    fun clearScreen() {
        pickedVideoUris = emptyList()
        clipDurations = emptyMap()
        pickedImageUri = null

        capturedNegativeVideo?.file?.let { try { it.delete() } catch(e:Exception){ Log.e("Cleanup", "Failed to delete temp file", e) } }

        capturedNegativeVideo = null
        if (existingLandmarkId == null) { businessLandmarkId = null; labelText = ""; shortDescription = "" }
        isFormVisible = false
        statusText = "No landmark media selected."
    }

    fun startFullSubmission() {
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
                uiTargetDuration = uiTargetDuration,
                minTotalTimeLimit = uiTargetDuration,
                onDone = { uris ->
                    isStitchingVideos = true
                    coroutineScope.launch {
                        try {
                            val stitchedUri = VideoMerger.mergeAndValidate(context, uris, 1.0)
                            pickedVideoUris = listOf(stitchedUri)
                        } catch (e: Exception) {
                            Log.e("LookSee", "Merge failed", e)
                            pickedVideoUris = listOf(uris.first())
                        } finally {
                            isStitchingVideos = false
                            isFormVisible = true
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
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { focusManager.clearFocus() }
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(bottom = 100.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (pickedVideoUris.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (hasMinimumClipDuration) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                            Text("${String.format(java.util.Locale.US, "%.1f", totalClipDuration)}s total — ready to upload", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500))
                        }

                        pickedVideoUris.forEach { uri ->
                            Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                                PositiveSafeVideoPlayer(uri = uri, modifier = Modifier.fillMaxSize())
                                if (!arePositiveDetailsLocked && !isAdditionalMedia) {
                                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp).background(Color.Black.copy(0.6f), CircleShape).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); clearScreen() }, contentAlignment = Alignment.Center) {
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
                                Text(String.format(java.util.Locale.US, "%.6f, %.6f", extractedLatitude, extractedLongitude), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                Text("Location Locked", fontSize = 13.sp, color = Color.Gray)
                            } else {
                                val fix = (locationState as? LookSeeLocationState.Ready)?.fix
                                if (fix != null) {
                                    Text(String.format(java.util.Locale.US, "%.6f, %.6f", fix.latitude, fix.longitude), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                    Text("Accuracy: ±${fix.accuracyMeters.toInt()}m", fontSize = 13.sp, color = Color.Gray)
                                } else {
                                    Text("Requesting location...", fontSize = 15.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }

                if (isFormVisible) {
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
                                    value = labelText, onValueChange = { labelText = it },
                                    placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                    enabled = !arePositiveDetailsLocked
                                )
                            }
                        }
                    }

                    if (businessLandmarkId != null) {
                        Text("ID: $businessLandmarkId", fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }

                    Spacer(Modifier.height(10.dp))
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
                                    value = shortDescription, onValueChange = { shortDescription = it },
                                    placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                    enabled = !arePositiveDetailsLocked
                                )
                                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).background(AppleBlue, CircleShape).clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showTextScanner = true
                                }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
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

                                if (capturedNegativeVideo != null) {
                                    Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))) {
                                        PositiveSafeVideoPlayer(uri = Uri.fromFile(capturedNegativeVideo!!.file), modifier = Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); startFullSubmission() },
                            modifier = Modifier.weight(1f).height(60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if(canUpload) AppleBlue else Color(0xFF2C2C2E)),
                            shape = RoundedCornerShape(16.dp),
                            enabled = canUpload
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (archivedMedia != null) "Upload Draft" else (if (isAdditionalMedia) "Upload Additional Media" else "Upload Landmark"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        if (archivedMedia == null) {
                            Button(
                                onClick = { showDiscardAlert = true },
                                modifier = Modifier.size(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }

        if (isStitchingVideos) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).zIndex(100f), contentAlignment = Alignment.Center) {
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
                    uiTargetDuration = negativeTargetDuration,
                    minTotalTimeLimit = negativeTargetDuration,
                    maxTotalTimeLimit = 30,
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
                    if (shortDescription.trim().isEmpty()) {
                        shortDescription = newText
                    } else {
                        shortDescription += " $newText"
                    }
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