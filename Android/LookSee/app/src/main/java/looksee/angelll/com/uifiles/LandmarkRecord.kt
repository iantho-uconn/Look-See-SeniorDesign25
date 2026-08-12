package looksee.angelll.com.uifiles

import android.app.Activity
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun LandmarkRecordScreen(
    vm: AuthViewModel,
    archivedMedia: ArchivedMedia? = null,
    onAddMoreMedia: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Services
    val uploadService = remember { UploadService() }
    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val locationManager = remember { LocationManager() }

    // State Variables
    var labelText by remember { mutableStateOf("") }
    var businessLandmarkId by remember { mutableStateOf<String?>(null) }
    var shortDescription by remember { mutableStateOf("") }
    var showTextScanner by remember { mutableStateOf(false) }

    var pickedVideoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var clipDurations by remember { mutableStateOf<Map<Uri, Double>>(emptyMap()) }

    var showVideoCamera by remember { mutableStateOf(false) }
    var extractedLatitude by remember { mutableStateOf<Double?>(null) }
    var extractedLongitude by remember { mutableStateOf<Double?>(null) }

    var statusText by remember { mutableStateOf("No landmark media selected.") }
    var showArchivePrompt by remember { mutableStateOf(false) }
    var showDiscardAlert by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    var pendingArchiveUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isStitchingVideos by remember { mutableStateOf(false) }
    var startRecording by remember { mutableStateOf(false) }

    var showAutoQueueAlert by remember { mutableStateOf(false) }
    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    var showNegativeCamera by remember { mutableStateOf(false) }

    var completedPositiveResult by remember { mutableStateOf<PositiveSubmissionResult?>(null) }
    var completedLandmarkId by remember { mutableStateOf<String?>(null) }
    var isFullSubmissionComplete by remember { mutableStateOf(false) }
    var showCompletionPopup by remember { mutableStateOf(false) }

    var isFormVisible by remember { mutableStateOf(false) }

    // Colors & Constants
    val primaryColor = Color(0xFF387DFF)
    val minimumCombinedVideoDuration = 15.0

    // Computed Properties
    val hasPositiveMedia = pickedVideoUris.isNotEmpty() || pickedImageUri != null
    val hasLabel = labelText.trim().isNotEmpty()
    val hasRequiredShortDescription = shortDescription.trim().isNotEmpty()
    val hasRequiredNegativeVideo = capturedNegativeVideo != null
    val totalClipDuration = pickedVideoUris.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImageUri != null || totalClipDuration >= minimumCombinedVideoDuration

    val isSubmissionRunning = uploadService.isUploading || hardNegativeUploadService.isUploading || isStitchingVideos
    val canUpload = !isSubmissionRunning && !isFullSubmissionComplete && (
            if (completedPositiveResult != null) hasRequiredNegativeVideo
            else hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativeVideo && hasMinimumClipDuration
            )

    val arePositiveDetailsLocked = isSubmissionRunning || completedPositiveResult != null || isFullSubmissionComplete
    val areNegativePhotosLocked = isSubmissionRunning || isFullSubmissionComplete

    // Helper Functions
    fun clearScreen() {
        pickedVideoUris = emptyList()
        capturedNegativeVideo = null
        clipDurations = emptyMap()
        pickedImageUri = null
        extractedLatitude = null
        extractedLongitude = null
        labelText = ""
        shortDescription = ""
        businessLandmarkId = null
        completedPositiveResult = null
        completedLandmarkId = null
        isFullSubmissionComplete = false
        isFormVisible = false
        statusText = "No landmark media selected."
        uploadService.reset()
        hardNegativeUploadService.reset()
    }

    suspend fun loadDuration(uri: Uri) {
        val duration = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                (timeString?.toLongOrNull() ?: 0L) / 1000.0
            } catch (_: Exception) { // Fixed: Suppressed unused 'e'
                0.0
            } finally {
                retriever.release()
            }
        }
        clipDurations = clipDurations + (uri to duration)
    }

    // Effect: Keep Screen On while recording
    LaunchedEffect(startRecording) {
        if (startRecording) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Effect: Load Archived Media on start
    LaunchedEffect(archivedMedia) {
        if (archivedMedia != null) {
            isFormVisible = true
            businessLandmarkId = businessLandmarkId ?: "landmark_${UUID.randomUUID().toString().replace("-", "").take(8)}"
            statusText = "Loaded archived media."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
            .clickable { focusManager.clearFocus() }
    ) {
        ScrollViewContent(
            hasPositiveMedia = hasPositiveMedia,
            isFormVisible = isFormVisible,
            statusText = statusText,
            primaryColor = primaryColor,
            labelText = labelText,
            onLabelChange = { labelText = it },
            shortDescription = shortDescription,
            onShortDescriptionChange = { shortDescription = it },
            businessLandmarkId = businessLandmarkId,
            arePositiveDetailsLocked = arePositiveDetailsLocked,
            onStartRecording = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showVideoCamera = true
                startRecording = true
            },
            pickedVideoUris = pickedVideoUris,
            pickedImageUri = pickedImageUri,
            totalClipDuration = totalClipDuration,
            hasMinimumClipDuration = hasMinimumClipDuration,
            minimumCombinedVideoDuration = minimumCombinedVideoDuration,
            clipDurations = clipDurations,
            onRemoveClip = { uri ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                pickedVideoUris = pickedVideoUris - uri
                clipDurations = clipDurations - uri
                statusText = if (pickedVideoUris.isEmpty()) "No media selected." else "Removed clip. ${pickedVideoUris.size} remaining."
            },
            locationManager = locationManager,
            completedPositiveResult = completedPositiveResult,
            isFullSubmissionComplete = isFullSubmissionComplete,
            hasRequiredNegativeVideo = hasRequiredNegativeVideo,
            areNegativePhotosLocked = areNegativePhotosLocked,
            onRecordNegative = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showNegativeCamera = true
            },
            capturedNegativeVideo = capturedNegativeVideo,
            onRemoveNegative = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                capturedNegativeVideo = null
            },
            uploadService = uploadService,
            hardNegativeUploadService = hardNegativeUploadService,
            isStitchingVideos = isStitchingVideos,
            canUpload = canUpload,
            onUploadClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                // Fixed: Consume variables and recreate the Swift 'startFullSubmission' flow
                if (completedPositiveResult == null) {
                    if (!vm.hasActiveSubscription) {
                        limitAlertTitle = "Subscription Required"
                        limitAlertMessage = "You need an active subscription or Free Trial to upload landmarks."
                        showLimitAlert = true
                        return@ScrollViewContent
                    }
                    if (vm.tokenBalance <= 0) {
                        limitAlertTitle = "Out of Tokens"
                        limitAlertMessage = "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                        showLimitAlert = true
                        return@ScrollViewContent
                    }
                }

                // Triggers Offline Queue logic if no connection
                coroutineScope.launch {
                    showAutoQueueAlert = true
                }
            },
            onDiscardClick = { showDiscardAlert = true },
            archivedMedia = archivedMedia,
            onBackClick = { onDismiss() },
            onTextScannerClick = { showTextScanner = true }
        )

        // Overlays
        if (showArchivePrompt) {
            ArchivePromptOverlay(
                primaryColor = primaryColor,
                onContinue = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showArchivePrompt = false
                    isFormVisible = false
                    isStitchingVideos = true
                    coroutineScope.launch {
                        delay(2.seconds) // Fixed: Legacy Long converted to Duration
                        pickedVideoUris = pendingArchiveUris
                        pendingArchiveUris.forEach { loadDuration(it) }
                        businessLandmarkId = businessLandmarkId ?: "landmark_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                        isStitchingVideos = false
                        isFormVisible = true
                        statusText = "Selected video(s)."
                    }
                },
                onDiscard = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showArchivePrompt = false
                    pendingArchiveUris = emptyList()
                }
            )
        }

        if (isStitchingVideos) {
            ProcessingOverlay()
        }
    }

    // Fixed: Added missing alerts back into the UI tree
    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle) },
            text = { Text(limitAlertMessage) },
            confirmButton = {
                TextButton(onClick = { showLimitAlert = false }) { Text("OK", color = primaryColor) }
            }
        )
    }

    if (showAutoQueueAlert) {
        AlertDialog(
            onDismissRequest = {
                showAutoQueueAlert = false
                if (archivedMedia != null) onDismiss()
            },
            title = { Text("Connection Offline") },
            text = { Text("You currently have no internet connection. This landmark has been securely added to your Upload Queue and will automatically sync when service returns!") },
            confirmButton = {
                TextButton(onClick = {
                    showAutoQueueAlert = false
                    if (archivedMedia != null) onDismiss()
                }) { Text("OK", color = primaryColor) }
            }
        )
    }

    if (showDiscardAlert) {
        AlertDialog(
            onDismissRequest = { showDiscardAlert = false },
            title = { Text("Discard this upload?") },
            text = { Text("This will remove the media and clear the form.") },
            confirmButton = {
                TextButton(onClick = { clearScreen(); showDiscardAlert = false }) {
                    Text("Discard", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardAlert = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }

    if (showCompletionPopup) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Landmark Uploaded!") },
            text = { Text("Your landmark media and negative reference video were uploaded successfully.") },
            confirmButton = {
                TextButton(onClick = {
                    clearScreen()
                    if (archivedMedia == null) {
                        coroutineScope.launch {
                            delay(300)
                            showVideoCamera = true
                        }
                    }
                    showCompletionPopup = false
                }) {
                    Text("Record Another Landmark")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearScreen(); showCompletionPopup = false; onDismiss() }) {
                    Text("Done", color = Color.White)
                }
            }
        )
    }

    // Camera Views
    if (showVideoCamera) {
        PositiveVideoCameraScreen(
            onDismiss = { showVideoCamera = false },
            onDone = { uris ->
                showVideoCamera = false
                pendingArchiveUris = uris
                showArchivePrompt = true
            }
        )
    }

    if (showNegativeCamera) {
        NegativeVideoCameraScreen(
            onDismiss = { showNegativeCamera = false },
            onDone = { video ->
                showNegativeCamera = false
                capturedNegativeVideo = video
                if (!hardNegativeUploadService.isUploading) {
                    hardNegativeUploadService.reset()
                }
            }
        )
    }
}

// -------------------------------------------------------------------
// Sub-components
// -------------------------------------------------------------------

@Composable
private fun ScrollViewContent(
    hasPositiveMedia: Boolean,
    isFormVisible: Boolean,
    statusText: String,
    primaryColor: Color,
    labelText: String,
    onLabelChange: (String) -> Unit,
    shortDescription: String,
    onShortDescriptionChange: (String) -> Unit,
    businessLandmarkId: String?,
    arePositiveDetailsLocked: Boolean,
    onStartRecording: () -> Unit,
    pickedVideoUris: List<Uri>,
    pickedImageUri: Uri?,
    totalClipDuration: Double,
    hasMinimumClipDuration: Boolean,
    minimumCombinedVideoDuration: Double,
    clipDurations: Map<Uri, Double>,
    onRemoveClip: (Uri) -> Unit,
    locationManager: LocationManager,
    completedPositiveResult: PositiveSubmissionResult?,
    isFullSubmissionComplete: Boolean,
    hasRequiredNegativeVideo: Boolean,
    areNegativePhotosLocked: Boolean,
    onRecordNegative: () -> Unit,
    capturedNegativeVideo: CapturedNegativeVideo?,
    onRemoveNegative: () -> Unit,
    uploadService: UploadService,
    hardNegativeUploadService: HardNegativeUploadService,
    isStitchingVideos: Boolean,
    canUpload: Boolean,
    onUploadClick: () -> Unit,
    onDiscardClick: () -> Unit,
    archivedMedia: ArchivedMedia?,
    onBackClick: () -> Unit,
    onTextScannerClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (!hasPositiveMedia) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Camera, contentDescription = "Camera", tint = primaryColor, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Capture Positive Media", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Follow the on-screen steps to capture the different angles of the landmark. This video should be from a typical place where a user may see the landmark.",
                        fontSize = 15.sp, color = Color.Gray, lineHeight = 20.sp)
                }
            }

            Button(
                onClick = onStartRecording,
                enabled = !arePositiveDetailsLocked,
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Video", tint = Color.White)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Start Recording Process", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (pickedVideoUris.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(
                    if (hasMinimumClipDuration) Icons.Default.CheckCircle else Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500)
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Fixed: String.format Locale bug squashed!
                val summaryText = if (hasMinimumClipDuration) {
                    String.format(Locale.US, "%.1fs total — ready to upload", totalClipDuration)
                } else {
                    val needed = maxOf(0.0, minimumCombinedVideoDuration - totalClipDuration)
                    String.format(Locale.US, "%.1fs total — need %.1fs more", totalClipDuration, needed)
                }
                Text(summaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500))
            }

            pickedVideoUris.forEachIndexed { index, uri ->
                Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                    UploadFormVideoPlayer(uri = uri)

                    if (!arePositiveDetailsLocked) {
                        IconButton(
                            onClick = { onRemoveClip(uri) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White)
                        }
                    }

                    val duration = clipDurations[uri]
                    val clipLabel = if (duration != null) String.format(Locale.US, "Clip %d · %.1fs", index + 1, duration) else "Clip ${index + 1} · loading…"
                    Text(
                        clipLabel,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        } else if (pickedImageUri != null) {
            AsyncImage(
                model = pickedImageUri,
                contentDescription = "Picked Image",
                modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))
            )
        }

        if (statusText.isNotEmpty()) {
            Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = primaryColor)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                if (locationManager.isAuthorized && locationManager.latitude != null) {
                    Text("${locationManager.latitude}, ${locationManager.longitude}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Accuracy: ±${locationManager.horizontalAccuracy?.toInt() ?: 0}m", fontSize = 13.sp, color = Color.Gray)
                } else {
                    Text("Requesting location…", fontSize = 14.sp, color = Color.Gray)
                }
            }
        }

        if (isFormVisible) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LANDMARK LABEL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                OutlinedTextField(
                    value = labelText,
                    onValueChange = onLabelChange,
                    placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                    enabled = !arePositiveDetailsLocked,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (businessLandmarkId != null) {
                    Text("ID: $businessLandmarkId", fontSize = 12.sp, color = Color.DarkGray)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = shortDescription,
                        onValueChange = onShortDescriptionChange,
                        placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                        enabled = !arePositiveDetailsLocked,
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    IconButton(
                        onClick = onTextScannerClick,
                        enabled = !arePositiveDetailsLocked,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).background(primaryColor, CircleShape)
                    ) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = "Scan Text", tint = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Negative Background", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Record a >= 10s video panning the area. Do NOT include the landmark.", fontSize = 15.sp, color = Color.Gray)
                    }
                    Icon(
                        if (hasRequiredNegativeVideo) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (hasRequiredNegativeVideo) Color.Green else Color(0xFFFFA500)
                    )
                }

                Button(
                    onClick = onRecordNegative,
                    enabled = !areNegativePhotosLocked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (capturedNegativeVideo == null) "Record Negative" else "Retake Negative", fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (capturedNegativeVideo != null) {
                    Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
                        UploadFormVideoPlayer(uri = capturedNegativeVideo.fileUri)
                        if (!areNegativePhotosLocked) {
                            IconButton(
                                onClick = onRemoveNegative,
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (archivedMedia != null) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(60.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Back", tint = Color.White)
                    }
                }

                if (hasPositiveMedia || completedPositiveResult != null) {
                    Button(
                        onClick = onUploadClick,
                        enabled = canUpload,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor, disabledContainerColor = Color.DarkGray),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(60.dp)
                    ) {
                        if (isStitchingVideos || uploadService.isUploading || hardNegativeUploadService.isUploading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(if (hardNegativeUploadService.isUploading) "Uploading reference..." else if (isStitchingVideos) "Processing..." else uploadService.status)
                        } else {
                            Icon(if (isFullSubmissionComplete) Icons.Default.CheckCircle else Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(if (isFullSubmissionComplete) "Complete" else if (completedPositiveResult != null) "Retry Negative" else "Upload Landmark", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (archivedMedia == null && !uploadService.isUploading && !isFullSubmissionComplete) {
                    IconButton(
                        onClick = onDiscardClick,
                        modifier = Modifier.size(60.dp).background(Color.Red.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------
// Overlays & Placeholders
// -------------------------------------------------------------------

@Composable
fun ProcessingOverlay() {
    Dialog(onDismissRequest = {}) {
        Box(
            modifier = Modifier.size(250.dp).background(Color.White, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = Color.Black)
                Text("Processing videos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text("Please wait a moment.", fontSize = 15.sp, color = Color.DarkGray)
            }
        }
    }
}

@Composable
fun ArchivePromptOverlay(primaryColor: Color, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(32.dp)).padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(70.dp).background(primaryColor.copy(alpha = 0.15f), CircleShape))
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(32.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Capture Complete", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text("Continue to fill out the landmark details. You can upload it when you're done.",
                    fontSize = 15.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onContinue,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Continue", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

                Button(
                    onClick = onDiscard,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Discard", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Red) }
            }
        }
    }
}

@Composable
fun UploadFormVideoPlayer(uri: Uri) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayCircle, contentDescription = "Play Placeholder", tint = Color.White, modifier = Modifier.size(48.dp))
    }
}