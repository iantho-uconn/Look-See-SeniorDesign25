package looksee.angelll.com.uifiles

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.models.*
import looksee.angelll.com.detection.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.ceil

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

    // Upload Services
    val uploadService = remember {
        UploadService(
            httpClient = UrlConnectionUploadHttpClient(),
            videoMerger = Media3VideoMerger(context),
            gson = com.google.gson.Gson()
        )
    }
    val isUploading by uploadService.isUploading.collectAsState()
    val uploadStatus by uploadService.status.collectAsState()
    val uploadDetail by uploadService.detail.collectAsState()
    val uploadProgress by uploadService.progress.collectAsState()
    val uploadStage by uploadService.stage.collectAsState()

    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val isHardNegativeUploading by hardNegativeUploadService.isUploading.collectAsState()
    val hardNegativeStatus by hardNegativeUploadService.status.collectAsState()
    val hardNegativeProgress by hardNegativeUploadService.progress.collectAsState()

    val locationManager = remember { LocationManager(context) }
    val locationState by locationManager.state.collectAsState()

    // Forms
    var labelText by remember { mutableStateOf(existingLabel ?: "") }
    var shortDescription by remember { mutableStateOf(existingDescription ?: "") }
    var businessLandmarkId by remember { mutableStateOf(existingLandmarkId) }
    var showTextScanner by remember { mutableStateOf(false) }

    // Media State
    var pickedVideoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var clipDurations by remember { mutableStateOf<Map<Uri, Double>>(emptyMap()) }

    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    var pendingArchiveUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // UI Triggers
    var showNegativeCamera by remember { mutableStateOf(false) }
    var isFormVisible by remember { mutableStateOf(archivedMedia != null) }
    var statusText by remember { mutableStateOf(if (archivedMedia != null) "Loaded archived media." else "No landmark media selected.") }
    var showDiscardAlert by remember { mutableStateOf(false) }
    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }
    var showBackgroundUploadAlert by remember { mutableStateOf(false) }

    // Logic Trackers
    var extractedLatitude by remember { mutableStateOf<Double?>(null) }
    var extractedLongitude by remember { mutableStateOf<Double?>(null) }
    var isStitchingVideos by remember { mutableStateOf(false) }

    var completedPositiveResult by remember { mutableStateOf<PositiveSubmissionResult?>(null) }
    var isFullSubmissionComplete by remember { mutableStateOf(false) }

    val PrimaryBlue = Color(0xFF387DFF)
    val SecondaryGrouped = Color(0xFF1C1C1E)

    val negativeTargetDuration = if (existingLandmarkId != null) 1 else 10

    val hasPositiveMedia = pickedVideoUris.isNotEmpty() || pickedImageUri != null
    val hasLabel = labelText.trim().isNotEmpty()
    val hasRequiredShortDescription = shortDescription.trim().isNotEmpty()
    val hasRequiredNegativeVideo = existingLandmarkId != null || capturedNegativeVideo != null
    val totalClipDuration = pickedVideoUris.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImageUri != null || totalClipDuration >= 1.0
    val isSubmissionRunning = isUploading || isHardNegativeUploading || isStitchingVideos

    val canUpload = !isSubmissionRunning && !isFullSubmissionComplete &&
            if (completedPositiveResult != null) hasRequiredNegativeVideo
            else (hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativeVideo && hasMinimumClipDuration)

    val arePositiveDetailsLocked = isSubmissionRunning || completedPositiveResult != null || isFullSubmissionComplete
    val areNegativePhotosLocked = isSubmissionRunning || isFullSubmissionComplete

    fun makeBusinessLandmarkId() = "landmark_${UUID.randomUUID().toString().replace("-", "").take(8)}"

    fun deleteTemporaryVideoIfNeeded(uri: Uri?) {
        if (uri == null || archivedMedia != null) return
        val path = uri.path ?: return
        if (path.startsWith(context.cacheDir.path)) {
            try { File(path).delete() } catch (e: Exception) {}
        }
    }

    fun deleteAllTemporaryVideos(uris: List<Uri>) {
        uris.forEach { deleteTemporaryVideoIfNeeded(it) }
    }

    fun clearScreen() {
        deleteAllTemporaryVideos(pickedVideoUris)
        capturedNegativeVideo?.deleteLocalFile()
        pickedVideoUris = emptyList()
        clipDurations = emptyMap()
        pickedImageUri = null
        extractedLatitude = null
        extractedLongitude = null

        if (existingLandmarkId == null) {
            businessLandmarkId = null
            labelText = ""
            shortDescription = ""
        } else {
            businessLandmarkId = existingLandmarkId
            labelText = existingLabel ?: ""
            shortDescription = existingDescription ?: ""
        }

        capturedNegativeVideo = null
        completedPositiveResult = null
        isFullSubmissionComplete = false
        isFormVisible = false
        statusText = "No landmark media selected."
        uploadService.reset()
        hardNegativeUploadService.reset()
    }

    fun processAndStitchPendingMedia() {
        if (pendingArchiveUris.isEmpty()) return
        isStitchingVideos = true
        val urisToStitch = pendingArchiveUris

        coroutineScope.launch {
            if (urisToStitch.size > 1) {
                try {
                    val stitchedUri = VideoMerger.mergeAndValidate(context, urisToStitch, 1.0)
                    deleteAllTemporaryVideos(pickedVideoUris)
                    deleteAllTemporaryVideos(pendingArchiveUris)
                    pickedVideoUris = listOf(stitchedUri)
                    statusText = "Selected combined video."
                } catch (e: Exception) {
                    pickedVideoUris = urisToStitch
                    statusText = "Selected ${pickedVideoUris.size} videos."
                }
            } else {
                pickedVideoUris = urisToStitch
                statusText = "Selected video."
            }

            for (uri in pickedVideoUris) {
                val duration = 0.0 // Placeholder
                clipDurations = clipDurations.toMutableMap().apply { put(uri, duration) }
            }

            if (extractedLatitude == null) extractedLatitude = (locationState as? LookSeeLocationState.Ready)?.fix?.latitude
            if (extractedLongitude == null) extractedLongitude = (locationState as? LookSeeLocationState.Ready)?.fix?.longitude
            if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()

            uploadService.reset()
            pendingArchiveUris = emptyList()
            isStitchingVideos = false
            isFormVisible = true
        }
    }

    fun saveToArchiveFromForm() {
        val lat = extractedLatitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.latitude ?: 0.0
        val lon = extractedLongitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.longitude ?: 0.0
        val idToSave = businessLandmarkId ?: makeBusinessLandmarkId()

        coroutineScope.launch {
            val offlineManager = OfflineMediaManager.shared(context)
            val firstUri = pickedVideoUris.firstOrNull()
            val img = pickedImageUri
            val lbl = labelText
            val desc = shortDescription
            val negFile = capturedNegativeVideo?.file

            if (firstUri != null) {
                val file = File(firstUri.path ?: "")
                offlineManager.archiveVideo(file, lat, lon, idToSave, lbl, desc, "", negFile, false)
            } else if (img != null) {
                // Photo archive logic
            }
        }
    }

    fun startFullSubmission() {
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

        if (archivedMedia != null) {
            coroutineScope.launch {
                OfflineMediaManager.shared(context).updateDraft(archivedMedia, labelText, shortDescription, "")
            }
        } else {
            if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()
            saveToArchiveFromForm()

            if (existingLandmarkId == null) {
                vm.tokenBalance -= 1
                vm.activeLandmarksCount += 1
            }
        }

        AutoUploadManager.shared(context).forceRetry()
        showBackgroundUploadAlert = true
    }

    LaunchedEffect(archivedMedia) {
        archivedMedia?.let { archive ->
            coroutineScope.launch {
                val file = OfflineMediaManager.shared(context).getFile(archive)
                val negFile = OfflineMediaManager.shared(context).getNegativeVideoFile(archive)

                if (archive.isVideo) {
                    val uri = Uri.fromFile(file)
                    pickedVideoUris = listOf(uri)
                    clipDurations = clipDurations.toMutableMap().apply { put(uri, 0.0) } // Placeholder
                } else {
                    pickedImageUri = Uri.fromFile(file)
                }

                if (negFile != null && negFile.exists()) {
                    capturedNegativeVideo = CapturedNegativeVideo(negFile)
                }

                extractedLatitude = archive.latitude
                extractedLongitude = archive.longitude
                labelText = archive.savedLabel ?: ""
                shortDescription = archive.savedDescription ?: ""
                if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()
                statusText = "Loaded archived media."
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPositiveMedia && !isFormVisible) {
            BusinessPositiveVideoCameraScreen(
                completionButtonTitle = "Use Recorded Videos",
                onDone = { uri ->
                    pendingArchiveUris = listOf(uri)
                    processAndStitchPendingMedia()
                },
                onDismiss = onDismiss
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F1A))
                    .clickable { focusManager.clearFocus() }
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // Toolbar
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = PrimaryBlue)
                    }
                }

                // Positive Media Preview
                if (pickedVideoUris.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (hasMinimumClipDuration) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                            Text("${String.format("%.1f", totalClipDuration)}s total — ready to upload", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500))
                        }

                        pickedVideoUris.forEachIndexed { index, uri ->
                            Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                                UploadFormVideoPlayer(uri)

                                if (!arePositiveDetailsLocked) {
                                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp).background(Color.Black.copy(0.6f), CircleShape).clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (pickedVideoUris.size == 1) clearScreen()
                                        else {
                                            deleteTemporaryVideoIfNeeded(uri)
                                            clipDurations = clipDurations.toMutableMap().apply { remove(uri) }
                                            pickedVideoUris = pickedVideoUris.filter { it != uri }
                                            statusText = "Removed clip. ${pickedVideoUris.size} remaining."
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }

                                val clipLabel = clipDurations[uri]?.let { "Clip ${index + 1} · ${String.format("%.1f", it)}s" } ?: "Clip ${index + 1} · loading..."
                                Text(clipLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(0.5f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                } else if (pickedImageUri != null) {
                    LocalUriImage(
                        uri = pickedImageUri!!,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))
                    )
                }

                if (statusText.isNotEmpty()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)

                        if (statusText.contains("Outbox") || statusText.contains("queued")) {
                            Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue, modifier = Modifier.background(PrimaryBlue.copy(0.15f), CircleShape).clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                AutoUploadManager.shared(context).forceRetry()
                            }.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }

                // Location Banner
                Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
                            if (fix != null) {
                                Text("${fix.latitude}, ${fix.longitude}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                Text("Accuracy: ±${fix.accuracyMeters.toInt()}m", fontSize = 13.sp, color = Color.Gray)
                            } else if (locationState is LookSeeLocationState.Unavailable) {
                                Text("Location Unavailable", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                            } else {
                                Text("Requesting location...", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        if (locationState is LookSeeLocationState.PermissionRequired) {
                            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); locationManager.start() }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue), shape = CircleShape, enabled = !arePositiveDetailsLocked) { Text("Enable") }
                        }
                    }
                }

                if (isFormVisible) {

                    // Form Fields
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LANDMARK LABEL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            OutlinedTextField(
                                value = labelText, onValueChange = { labelText = it },
                                placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = SecondaryGrouped, unfocusedContainerColor = SecondaryGrouped, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                enabled = !arePositiveDetailsLocked && existingLandmarkId == null
                            )
                            if (businessLandmarkId != null) Text("ID: $businessLandmarkId", fontSize = 12.sp, color = Color.DarkGray, fontFamily = FontFamily.Monospace)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Box(contentAlignment = Alignment.BottomEnd) {
                                OutlinedTextField(
                                    value = shortDescription, onValueChange = { shortDescription = it },
                                    placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = SecondaryGrouped, unfocusedContainerColor = SecondaryGrouped, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                    enabled = !arePositiveDetailsLocked && existingLandmarkId == null
                                )
                                Box(modifier = Modifier.padding(12.dp).size(36.dp).background(if (arePositiveDetailsLocked || existingLandmarkId != null) PrimaryBlue.copy(0.5f) else PrimaryBlue, CircleShape).clickable(enabled = !arePositiveDetailsLocked && existingLandmarkId == null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showTextScanner = true
                                }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    if (completedPositiveResult != null && !isFullSubmissionComplete) {
                        Surface(color = Color.Green.copy(0.1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(24.dp))
                                Column {
                                    Text("Landmark Saved", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Your landmark and positive media were successfully uploaded to the cloud.", fontSize = 14.sp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    if (existingLandmarkId == null) {
                        // Negative Media Section
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Negative Background", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Record a >= ${negativeTargetDuration}s video panning the area. Do NOT include the landmark.", fontSize = 15.sp, color = Color.Gray)
                                }
                                Icon(if (hasRequiredNegativeVideo) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = if (hasRequiredNegativeVideo) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(22.dp))
                            }

                            Button(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showNegativeCamera = true },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C24)),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !areNegativePhotosLocked
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Text(if (capturedNegativeVideo == null) "Record Negative" else "Retake Negative", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (capturedNegativeVideo != null) {
                                Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
                                    UploadFormVideoPlayer(Uri.fromFile(capturedNegativeVideo!!.file))
                                    if (!areNegativePhotosLocked) {
                                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp).background(Color.Black.copy(0.6f), CircleShape).clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            capturedNegativeVideo?.deleteLocalFile()
                                            capturedNegativeVideo = null
                                        }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Main Upload Button Row
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (archivedMedia != null) {
                            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() }, modifier = Modifier.size(60.dp), colors = ButtonDefaults.buttonColors(containerColor = SecondaryGrouped), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        }

                        if (hasPositiveMedia || completedPositiveResult != null) {
                            Button(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); startFullSubmission() },
                                modifier = Modifier.weight(1f).height(60.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (canUpload) PrimaryBlue else Color.DarkGray, disabledContainerColor = Color.DarkGray),
                                shape = RoundedCornerShape(16.dp),
                                enabled = canUpload
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                                    Text(if (archivedMedia != null) "Upload Draft" else if (existingLandmarkId != null) "Upload Additional Media" else "Upload Landmark", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        } else if (archivedMedia != null) {
                            Spacer(Modifier.weight(1f))
                        }

                        if (archivedMedia == null) {
                            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showDiscardAlert = true }, modifier = Modifier.size(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.1f)), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }

                    // Status Cards
                    if (uploadStage != PositiveUploadStage.IDLE) {
                        Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                    if (isUploading) CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(24.dp).padding(top = 2.dp))
                                    else Icon(Icons.Default.Info, contentDescription = null, tint = if (uploadStage == PositiveUploadStage.COMPLETE) Color.Green else if (uploadStage == PositiveUploadStage.FAILED) Color.Red else PrimaryBlue, modifier = Modifier.size(24.dp))
                                    Column {
                                        Text(uploadStatus, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(uploadDetail, fontSize = 14.sp, color = Color.Gray)
                                    }
                                }
                                if (isUploading) LinearProgressIndicator(progress = { uploadProgress.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = PrimaryBlue)
                                if (uploadStage == PositiveUploadStage.FAILED) TextButton(onClick = { uploadService.reset() }) { Text("Dismiss", color = PrimaryBlue, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }

                    if (hardNegativeStatus != "Idle") {
                        Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                    if (isHardNegativeUploading) CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(24.dp).padding(top = 2.dp))
                                    else Icon(Icons.Default.Videocam, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                                    Column {
                                        Text("Reference Video", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(hardNegativeStatus, fontSize = 14.sp, color = Color.Gray)
                                    }
                                }
                                if (isHardNegativeUploading) LinearProgressIndicator(progress = { hardNegativeProgress.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = PrimaryBlue)
                            }
                        }
                    }

                    if (isFullSubmissionComplete) {
                        Surface(color = Color.Green.copy(0.1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(24.dp))
                                Text("Submission Complete", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        if (isStitchingVideos) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).zIndex(100f), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.background(Color(0xFF1C1C24), RoundedCornerShape(24.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    Text("Processing Video", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Please wait a moment.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (showDiscardAlert) {
        AlertDialog(
            onDismissRequest = { showDiscardAlert = false },
            title = { Text("Discard this upload?") },
            text = { Text("This will remove the media and clear the form.") },
            confirmButton = { TextButton(onClick = { showDiscardAlert = false; clearScreen() }) { Text("Discard", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDiscardAlert = false }) { Text("Cancel", color = Color.White) } },
            containerColor = SecondaryGrouped
        )
    }

    if (showBackgroundUploadAlert) {
        AlertDialog(
            onDismissRequest = {
                showBackgroundUploadAlert = false
                clearScreen()
                onDismiss()
            },
            title = { Text("Upload Queued!") },
            text = { Text("Your landmark has been securely queued! It will upload in the background. Feel free to keep using the app, but please make sure to leave it open until the upload finishes.") },
            confirmButton = { TextButton(onClick = { showBackgroundUploadAlert = false; clearScreen() }) { Text("Record Another Landmark", color = PrimaryBlue) } },
            dismissButton = { TextButton(onClick = { showBackgroundUploadAlert = false; clearScreen(); onDismiss() }) { Text("Done", color = Color.White) } },
            containerColor = SecondaryGrouped
        )
    }

    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle) },
            text = { Text(limitAlertMessage) },
            confirmButton = { TextButton(onClick = { showLimitAlert = false }) { Text("OK", color = PrimaryBlue) } },
            containerColor = SecondaryGrouped
        )
    }
}

// Custom Native Image Loader (Replaces Coil)
@Composable
fun LocalUriImage(uri: Uri, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
