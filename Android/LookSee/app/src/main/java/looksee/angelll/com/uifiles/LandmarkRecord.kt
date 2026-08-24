package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkRecord(
    vm: AuthViewModel,
    isNavVisible: MutableState<Boolean>,
    isActive: Boolean = true,
    archivedMedia: ArchivedMedia? = null,
    existingLandmarkId: String? = null,
    existingLabel: String? = null,
    existingDescription: String? = null,
    @Suppress("UNUSED_PARAMETER") onAddMoreMedia: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // 🚀 THE S3 FOLDER FIX:
    // We inject the existing ID directly into the State upon initialization.
    // This completely eliminates the race condition that was generating new UUID folders!
    var businessLandmarkId by remember { mutableStateOf(existingLandmarkId) }
    var labelText by remember { mutableStateOf(existingLabel ?: "") }

    // Using explicit MutableState for the ScannerSheet (matches SwiftUI @Binding behavior)
    val shortDescriptionState = remember { mutableStateOf(existingDescription ?: "") }

    var showTextScanner by remember { mutableStateOf(false) }
    var pickedVideoURLs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickedImage by remember { mutableStateOf<Bitmap?>(null) }
    val clipDurations = remember { mutableStateMapOf<Uri, Double>() }

    var extractedLatitude by remember { mutableStateOf<Double?>(null) }
    var extractedLongitude by remember { mutableStateOf<Double?>(null) }
    var statusText by remember { mutableStateOf("No landmark media selected.") }
    var showArchivePrompt by remember { mutableStateOf(false) }
    var showDiscardAlert by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    var pendingArchiveURLs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isStitchingVideos by remember { mutableStateOf(false) }

    var showAutoQueueAlert by remember { mutableStateOf(false) }
    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    var showNegativeCamera by remember { mutableStateOf(false) }

    var completedPositiveResult by remember { mutableStateOf<PositiveSubmissionResult?>(null) }
    var completedLandmarkId by remember { mutableStateOf<String?>(null) }
    var isFullSubmissionComplete by remember { mutableStateOf(false) }
    var showCompletionPopup by remember { mutableStateOf(false) }

    var isFormVisible by remember { mutableStateOf(false) }

    // External Services (Will be unresolved red until implemented)
    val uploadService = remember { UploadService() }
    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val locationManager = remember { LocationManager() }

    val primaryColor = Color(0xFF387DFF) // 0.22, 0.49, 1.00
    val groupedBg = Color(0xFFF2F2F7)
    val secondaryGroupedBg = Color.White

    val minimumCombinedVideoDuration = 30.0

    val hasPositiveMedia = pickedVideoURLs.isNotEmpty() || pickedImage != null
    val hasLabel = labelText.trim().isNotEmpty()
    val hasRequiredShortDescription = shortDescriptionState.value.trim().isNotEmpty()

    // 🚀 THE NEGATIVE VIDEO FIX:
    // If it's a Redo (existingLandmarkId exists), we bypass the negative video requirement!
    val hasRequiredNegativeVideo = existingLandmarkId != null || capturedNegativeVideo != null

    val totalClipDuration = pickedVideoURLs.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImage != null || totalClipDuration >= minimumCombinedVideoDuration
    val isSubmissionRunning = uploadService.isUploading || hardNegativeUploadService.isUploading || isStitchingVideos

    val canUpload = !isSubmissionRunning && !isFullSubmissionComplete && (
            if (completedPositiveResult != null) hasRequiredNegativeVideo
            else hasPositiveMedia && hasLabel && hasRequiredShortDescription && hasRequiredNegativeVideo && hasMinimumClipDuration
            )

    val arePositiveDetailsLocked = isSubmissionRunning || completedPositiveResult != null || isFullSubmissionComplete
    val areNegativePhotosLocked = isSubmissionRunning || isFullSubmissionComplete

    val durationSummaryText = if (hasMinimumClipDuration) {
        "${String.format("%.1f", totalClipDuration)}s total — ready to upload"
    } else {
        "${String.format("%.1f", totalClipDuration)}s total — need ${String.format("%.1f", max(0.0, minimumCombinedVideoDuration - totalClipDuration))}s more"
    }

    fun makeBusinessLandmarkId(): String {
        return "landmark_" + UUID.randomUUID().toString().replace("-", "").take(8)
    }

    fun deleteTemporaryVideoIfNeeded(videoURL: Uri?) {
        if (videoURL == null || archivedMedia != null) return
        val path = videoURL.path ?: return
        if (path.startsWith(context.cacheDir.absolutePath)) {
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    fun deleteAllTemporaryVideos(urls: List<Uri>) {
        urls.forEach { deleteTemporaryVideoIfNeeded(it) }
    }

    fun clearScreen() {
        deleteAllTemporaryVideos(pickedVideoURLs)
        capturedNegativeVideo?.deleteLocalFile()
        pickedVideoURLs = emptyList()
        clipDurations.clear()
        pickedImage = null
        extractedLatitude = null
        extractedLongitude = null

        // 🚀 THE FIX: Safely restore the Redo IDs so it doesn't accidentally generate a new one!
        if (existingLandmarkId == null) {
            businessLandmarkId = null
            labelText = ""
            shortDescriptionState.value = ""
        } else {
            businessLandmarkId = existingLandmarkId
            labelText = existingLabel ?: ""
            shortDescriptionState.value = existingDescription ?: ""
        }

        capturedNegativeVideo = null
        completedPositiveResult = null
        completedLandmarkId = null
        isFullSubmissionComplete = false
        isFormVisible = false
        statusText = "No landmark media selected."
        uploadService.reset()
        hardNegativeUploadService.reset()
    }

    fun resetForAnotherLandmark() { clearScreen() }

    fun discardPendingMedia() {
        deleteAllTemporaryVideos(pendingArchiveURLs)
        pendingArchiveURLs = emptyList()
    }

    fun removeClip(url: Uri) {
        val mutableList = pickedVideoURLs.toMutableList()
        if (mutableList.remove(url)) {
            deleteTemporaryVideoIfNeeded(url)
            clipDurations.remove(url)
            pickedVideoURLs = mutableList
            statusText = if (pickedVideoURLs.isEmpty()) "No media selected." else "Removed clip. ${pickedVideoURLs.size} remaining."
        }
    }

    suspend fun loadDuration(url: Uri) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, url)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val seconds = durationMs / 1000.0
            if (seconds > 0) {
                withContext(Dispatchers.Main) { clipDurations[url] = seconds }
            }
        } catch (e: Exception) {
            println("Could not read duration for ${url.lastPathSegment}: $e")
        } finally {
            retriever.release()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stitchVideos(urls: List<Uri>): Uri? {
        // Pure Android natively requires Media3 Transformer API or MediaMuxer here.
        // Simulated return boundary to match Swift AVMutableComposition structure:
        return null
    }

    fun processAndStitchPendingMedia() {
        if (pendingArchiveURLs.isEmpty()) return
        isStitchingVideos = true
        val urlsToStitch = pendingArchiveURLs.toList()

        coroutineScope.launch(Dispatchers.IO) {
            if (urlsToStitch.size > 1) {
                val stitchedURL = stitchVideos(urlsToStitch)
                withContext(Dispatchers.Main) {
                    deleteAllTemporaryVideos(pickedVideoURLs)
                    deleteAllTemporaryVideos(pendingArchiveURLs)
                    if (stitchedURL != null) {
                        pickedVideoURLs = listOf(stitchedURL)
                        statusText = "Selected combined video."
                    } else {
                        pickedVideoURLs = urlsToStitch
                        statusText = "Selected ${pickedVideoURLs.size} videos."
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    pickedVideoURLs = urlsToStitch
                    statusText = "Selected video."
                }
            }

            val finalURLs = pickedVideoURLs
            finalURLs.forEach { loadDuration(it) }

            withContext(Dispatchers.Main) {
                if (extractedLatitude == null) extractedLatitude = locationManager.latitude
                if (extractedLongitude == null) extractedLongitude = locationManager.longitude
                if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()
                uploadService.reset()
                pendingArchiveURLs = emptyList()
                isStitchingVideos = false
                isFormVisible = true
            }
        }
    }

    fun saveToArchiveFromForm() {
        val lat = extractedLatitude ?: locationManager.latitude ?: 0.0
        val lon = extractedLongitude ?: locationManager.longitude ?: 0.0
        coroutineScope.launch(Dispatchers.IO) {
            val firstURL = pickedVideoURLs.firstOrNull()
            val img = pickedImage
            val id = businessLandmarkId ?: return@launch
            val lbl = labelText
            val desc = shortDescriptionState.value
            val negURL = capturedNegativeVideo?.fileURL

            if (firstURL != null) {
                OfflineMediaManager.shared.archiveVideo(firstURL, lat, lon, id, lbl, desc, null, negURL, false)
            } else if (img != null) {
                OfflineMediaManager.shared.archivePhoto(img, lat, lon, id, lbl, desc, null, negURL, false)
            } else {
                return@launch
            }

            withContext(Dispatchers.Main) {
                clearScreen()
                statusText = "Landmark safely queued in Outbox for upload."
            }
        }
    }

    fun saveDraftAndDismiss() {
        if (archivedMedia != null) {
            OfflineMediaManager.shared.updateDraft(archivedMedia, labelText, shortDescriptionState.value, null)
        }
        onDismiss()
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

        if (!NetworkMonitor.shared.isConnected) {
            if (archivedMedia != null) {
                OfflineMediaManager.shared.updateDraft(archivedMedia, labelText, shortDescriptionState.value, null)
            } else {
                saveToArchiveFromForm()
            }
            coroutineScope.launch(Dispatchers.Main) {
                delay(300.milliseconds)
                showAutoQueueAlert = true
            }
            return
        }

        coroutineScope.launch {
            if (isSubmissionRunning || isFullSubmissionComplete) return@launch
            if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()
            val generatedLandmarkId = businessLandmarkId ?: return@launch

            try {
                val positiveResult: PositiveSubmissionResult
                vm.fetchUserDetails()
                val idToken = vm.fetchIdToken()

                val existingPositive = completedPositiveResult
                if (existingPositive != null) {
                    positiveResult = existingPositive
                } else {
                    val trimmedLabel = labelText.trim()
                    val trimmedShortDescription = shortDescriptionState.value.trim()

                    if (trimmedLabel.isEmpty() || trimmedShortDescription.isEmpty()) return@launch

                    positiveResult = uploadService.upload(
                        vm.userEmail,
                        idToken,
                        trimmedLabel,
                        generatedLandmarkId,
                        trimmedLabel,
                        trimmedShortDescription,
                        null,
                        extractedLatitude ?: locationManager.latitude ?: 0.0,
                        extractedLongitude ?: locationManager.longitude ?: 0.0,
                        locationManager.horizontalAccuracy ?: 0.0,
                        pickedVideoURLs,
                        pickedImage
                    )

                    completedPositiveResult = positiveResult
                    statusText = "Landmark media saved. Uploading reference video…"

                    if (existingLandmarkId == null) {
                        withContext(Dispatchers.Main) {
                            vm.tokenBalance -= 1
                            vm.activeLandmarksCount += 1
                        }
                    }
                }

                val finalLandmarkId = positiveResult.landmarkId ?: generatedLandmarkId

                // 🚀 THE FIX: Never uploads a negative video if it's a Redo!
                val negVideo = capturedNegativeVideo
                if (negVideo != null && existingLandmarkId == null) {
                    hardNegativeUploadService.upload(finalLandmarkId, idToken, negVideo)
                }

                completedLandmarkId = finalLandmarkId
                isFullSubmissionComplete = true
                statusText = "Landmark uploaded. The reference video is processing in the background."
                showCompletionPopup = true

                if (archivedMedia != null) {
                    OfflineMediaManager.shared.deleteArchive(archivedMedia)
                }
            } catch (e: Exception) {
                if (e.localizedMessage?.contains("ERR_SUBSCRIPTION_EXPIRED") == true) {
                    withContext(Dispatchers.Main) {
                        limitAlertTitle = "Subscription Expired"
                        limitAlertMessage = "Your subscription period has ended. Please renew to continue uploading landmarks."
                        showLimitAlert = true
                    }
                } else {
                    println("Full landmark submission failed: ${e.localizedMessage}")
                }
            }
        }
    }

    LaunchedEffect(archivedMedia) {
        val archive = archivedMedia ?: return@LaunchedEffect
        isFormVisible = true
        coroutineScope.launch(Dispatchers.IO) {
            val fileURL = OfflineMediaManager.shared.getFileURL(archive)
            var videoURLs: List<Uri> = emptyList()
            var loadedImage: Bitmap? = null
            var negVideo: CapturedNegativeVideo? = null

            if (archive.isVideo) {
                if (fileURL is Uri) videoURLs = listOf(fileURL)
            } else {
                val path = if (fileURL is Uri) fileURL.path else null
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        loadedImage = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    }
                }
            }

            val negURL = OfflineMediaManager.shared.getNegativeVideoURL(archive)
            if (negURL is Uri && File(negURL.path ?: "").exists()) {
                negVideo = CapturedNegativeVideo(fileURL = negURL)
            }

            withContext(Dispatchers.Main) {
                pickedVideoURLs = videoURLs
                pickedImage = loadedImage
                capturedNegativeVideo = negVideo
                extractedLatitude = archive.latitude
                extractedLongitude = archive.longitude
                labelText = archive.savedLabel ?: ""
                shortDescriptionState.value = archive.savedDescription ?: ""
                if (businessLandmarkId == null) businessLandmarkId = makeBusinessLandmarkId()
                statusText = "Loaded archived media."
            }

            videoURLs.forEach { loadDuration(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!hasPositiveMedia && !isFormVisible) {
            PositiveVideoCameraView(
                isActive = isActive,
                isNavVisible = isNavVisible,
                onDone = { urls ->
                    pendingArchiveURLs = urls
                    showArchivePrompt = true
                },
                onCancel = { onDismiss() }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(groupedBg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        focusManager.clearFocus()
                    }
            ) {
                Spacer(Modifier.height(50.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Spacer(Modifier.height(16.dp))

                    // Positive Media Preview
                    if (pickedVideoURLs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                            // Duration Summary Banner
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = if (hasMinimumClipDuration) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = durationSummaryText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500)
                                )
                                Spacer(Modifier.weight(1f))
                            }

                            pickedVideoURLs.forEachIndexed { index, url ->
                                Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                                    UploadFormVideoPlayer(url = url)

                                    if (!arePositiveDetailsLocked) {
                                        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopEnd) {
                                            IconButton(
                                                onClick = {
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                    if (pickedVideoURLs.size == 1) clearScreen() else removeClip(url)
                                                },
                                                modifier = Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                                        Text(
                                            text = if (clipDurations[url] != null) "Clip ${index + 1} · ${String.format("%.1f", clipDurations[url])}s" else "Clip ${index + 1} · loading…",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        pickedImage?.let { img ->
                            Image(
                                bitmap = img.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }

                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Location Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(secondaryGroupedBg, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (locationManager.isAuthorized && locationManager.latitude != null && locationManager.longitude != null) {
                                Text("${locationManager.latitude}, ${locationManager.longitude}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("Accuracy: ±${locationManager.horizontalAccuracy?.toInt() ?: 0}m", fontSize = 13.sp, color = Color.Gray)
                            } else if (locationManager.authorizationStatus.toString() == "Denied" || locationManager.authorizationStatus.toString() == "Restricted") {
                                Text("Location Denied", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                            } else {
                                Text("Requesting location…", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!locationManager.isAuthorized) {
                            Button(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    locationManager.requestPermissionIfNeeded()
                                },
                                enabled = !arePositiveDetailsLocked,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = CircleShape
                            ) { Text("Enable", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                    }

                    if (isFormVisible) {
                        // Landmark Form
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                            // Label
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("LANDMARK LABEL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                TextField(
                                    value = labelText,
                                    onValueChange = { labelText = it },
                                    placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                                    enabled = !arePositiveDetailsLocked && existingLandmarkId == null,
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = secondaryGroupedBg,
                                        unfocusedContainerColor = secondaryGroupedBg,
                                        disabledContainerColor = secondaryGroupedBg,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (businessLandmarkId != null) {
                                    Text("ID: $businessLandmarkId", fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = Color.Gray.copy(alpha = 0.5f))
                                }
                            }

                            // Short Description
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                                    TextField(
                                        value = shortDescriptionState.value,
                                        onValueChange = { shortDescriptionState.value = it },
                                        placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                                        enabled = !arePositiveDetailsLocked && existingLandmarkId == null,
                                        minLines = 3,
                                        maxLines = 6,
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = secondaryGroupedBg,
                                            unfocusedContainerColor = secondaryGroupedBg,
                                            disabledContainerColor = secondaryGroupedBg,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            disabledIndicatorColor = Color.Transparent
                                        ),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    IconButton(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            showTextScanner = true
                                        },
                                        enabled = !arePositiveDetailsLocked && existingLandmarkId == null,
                                        modifier = Modifier.padding(12.dp).size(36.dp).background(if (arePositiveDetailsLocked || existingLandmarkId != null) primaryColor.copy(alpha = 0.5f) else primaryColor, CircleShape)
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (completedPositiveResult != null && !isFullSubmissionComplete) {
                                Row(modifier = Modifier.fillMaxWidth().background(Color.Green.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(20.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Landmark Saved", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                        Text("Your landmark and positive media were successfully uploaded to the cloud.", fontSize = 14.sp, color = Color.Gray)
                                    }
                                }
                            }

                            // 🚀 THE FIX: Only show Negative Video section if this is a BRAND-NEW upload!
                            if (existingLandmarkId == null) {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().background(secondaryGroupedBg, RoundedCornerShape(16.dp)).padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Negative Background", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                            Text("Record a >= 10s video panning the area. Do NOT include the landmark.", fontSize = 15.sp, color = Color.Gray)
                                        }
                                        Icon(
                                            imageVector = if (hasRequiredNegativeVideo) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                            contentDescription = null,
                                            tint = if (hasRequiredNegativeVideo) Color.Green else Color(0xFFFFA500),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            showNegativeCamera = true
                                        },
                                        enabled = !areNegativePhotosLocked,
                                        modifier = Modifier.fillMaxWidth().height(54.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C29), disabledContainerColor = Color(0xFF1C1C29).copy(alpha = 0.6f))
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Camera, contentDescription = null, tint = Color.White)
                                            Text(if (capturedNegativeVideo == null) "Record Negative" else "Retake Negative", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }

                                    capturedNegativeVideo?.let { negVid ->
                                        Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
                                            UploadFormVideoPlayer(url = negVid.fileURL)
                                            Button(
                                                onClick = {
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                    negVid.deleteLocalFile()
                                                    capturedNegativeVideo = null
                                                },
                                                enabled = !areNegativePhotosLocked,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                                                shape = CircleShape
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Upload Button Row
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (archivedMedia != null) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        saveDraftAndDismiss()
                                    },
                                    modifier = Modifier.size(60.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = secondaryGroupedBg)
                                ) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                            }

                            if (hasPositiveMedia || completedPositiveResult != null) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        startFullSubmission()
                                    },
                                    enabled = canUpload,
                                    modifier = Modifier.weight(1f).height(60.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryColor,
                                        disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (isSubmissionRunning) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                            Text(
                                                text = if (hardNegativeUploadService.isUploading) "Uploading reference..." else if (isStitchingVideos) "Processing..." else uploadService.status,
                                                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White
                                            )
                                        } else {
                                            Icon(if (isFullSubmissionComplete) Icons.Filled.CheckCircle else Icons.Filled.CloudUpload, contentDescription = null, tint = Color.White)
                                            Text(
                                                text = if (isFullSubmissionComplete) "Complete" else if (completedPositiveResult != null) "Retry Negative" else if (existingLandmarkId != null) "Upload Additional Media" else "Upload Landmark",
                                                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White
                                            )
                                        }
                                    }
                                }
                            } else if (archivedMedia != null) {
                                Spacer(Modifier.weight(1f))
                            }
                        }

                        // Status Cards
                        if (uploadService.stage.toString() != "Idle") {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(secondaryGroupedBg, RoundedCornerShape(16.dp)).padding(20.dp)) {
                                if (uploadService.isUploading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = primaryColor)
                                } else {
                                    Icon(
                                        imageVector = if (uploadService.stage.toString() == "Complete") Icons.Filled.CheckCircle else if (uploadService.stage.toString() == "Failed") Icons.Filled.Error else Icons.Filled.CloudUpload,
                                        contentDescription = null,
                                        tint = if (uploadService.stage.toString() == "Complete") Color.Green else if (uploadService.stage.toString() == "Failed") Color.Red else primaryColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(uploadService.status, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text(uploadService.detail, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                                    if (uploadService.isUploading) LinearProgressIndicator(progress = { uploadService.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = primaryColor)
                                }
                                if (uploadService.stage.toString() == "Failed") {
                                    IconButton(onClick = { uploadService.reset() }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                                }
                            }
                        }

                        if (hardNegativeUploadService.status != "Idle") {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(secondaryGroupedBg, RoundedCornerShape(16.dp)).padding(20.dp)) {
                                if (hardNegativeUploadService.isUploading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = primaryColor)
                                } else {
                                    Icon(Icons.Filled.Videocam, contentDescription = null, tint = primaryColor, modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Reference Video", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text(hardNegativeUploadService.status, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                                    if (hardNegativeUploadService.isUploading) LinearProgressIndicator(progress = { hardNegativeUploadService.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = primaryColor)
                                }
                            }
                        }

                        if (isFullSubmissionComplete) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.Green.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Text("Submission Complete", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }

                        if (archivedMedia == null && !uploadService.isUploading && !isFullSubmissionComplete) {
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        showDiscardAlert = true
                                    },
                                    modifier = Modifier.size(60.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f))
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(40.dp))
                }
                Spacer(Modifier.height(90.dp))
            }

            // Overlays
            if (showArchivePrompt) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp).background(Color.White, RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(modifier = Modifier.size(70.dp).background(primaryColor.copy(alpha = 0.15f), CircleShape))
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(32.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Capture Complete", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("Continue to fill out the landmark details. You can upload it when you're done.", fontSize = 15.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    showArchivePrompt = false
                                    processAndStitchPendingMedia()
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                            ) { Text("Continue", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                            Button(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    showArchivePrompt = false
                                    discardPendingMedia()
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(2.dp, Color.Red.copy(alpha = 0.8f)),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray.copy(alpha = 0.15f))
                            ) { Text("Discard", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.Red) }
                        }
                    }
                }
            }

            if (isStitchingVideos) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.background(Color.White, RoundedCornerShape(32.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        CircularProgressIndicator(color = primaryColor)
                        Text("Processing videos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Please wait a moment.", fontSize = 15.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    // Alerts and Dialogs
    if (showDiscardAlert) {
        AlertDialog(
            onDismissRequest = { showDiscardAlert = false },
            title = { Text("Discard this upload?") },
            text = { Text("This will remove the media and clear the form.") },
            confirmButton = {
                TextButton(
                    onClick = { showDiscardAlert = false; clearScreen() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardAlert = false }) { Text("Cancel") }
            }
        )
    }

    if (showCompletionPopup) {
        AlertDialog(
            onDismissRequest = { showCompletionPopup = false },
            title = { Text("Landmark Uploaded!") },
            text = { Text("Your landmark media was uploaded. The negative reference video may continue processing in the background.") },
            confirmButton = {
                if (archivedMedia != null || existingLandmarkId != null) {
                    TextButton(onClick = { showCompletionPopup = false; onDismiss() }) { Text("Done") }
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { showCompletionPopup = false; resetForAnotherLandmark() }) { Text("Record Another Landmark") }
                        TextButton(onClick = { showCompletionPopup = false; resetForAnotherLandmark(); onDismiss() }) { Text("Done") }
                    }
                }
            }
        )
    }

    if (showAutoQueueAlert) {
        AlertDialog(
            onDismissRequest = { showAutoQueueAlert = false },
            title = { Text("Connection Offline") },
            text = { Text("You currently have no internet connection. This landmark has been securely added to your Upload Queue and will automatically sync when service returns!") },
            confirmButton = {
                TextButton(onClick = {
                    showAutoQueueAlert = false
                    if (archivedMedia != null) onDismiss()
                }) { Text("OK") }
            }
        )
    }

    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle) },
            text = { Text(limitAlertMessage) },
            confirmButton = { TextButton(onClick = { showLimitAlert = false }) { Text("OK") } }
        )
    }

    if (showNegativeCamera) {
        NegativeVideoCameraView(
            onDone = { video ->
                capturedNegativeVideo = video
                if (!hardNegativeUploadService.isUploading) hardNegativeUploadService.reset()
                showNegativeCamera = false
            },
            onDismiss = { showNegativeCamera = false }
        )
    }

    if (showTextScanner) {
        ScannerSheet(
            scannedText = shortDescriptionState,
            onDismiss = { showTextScanner = false }
        )
    }
}

@Composable
fun UploadFormVideoPlayer(url: Uri) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(url)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}