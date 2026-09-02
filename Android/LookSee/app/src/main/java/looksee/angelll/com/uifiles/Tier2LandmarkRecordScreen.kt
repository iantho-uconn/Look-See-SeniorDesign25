package looksee.angelll.com.uifiles

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.models.*
import looksee.angelll.com.detection.*
import java.io.File
import looksee.angelll.com.detection.NetworkMonitor as Monitor

private val PrimaryColor = Color(0xFF387DFF)
private val SystemGroupedBackground = Color(0xFFF2F2F7)
private val SecondaryGroupedBackground = Color(0xFFFFFFFF)
private val OverlayBackground = Color(0xFF1C1C29)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tier2LandmarkRecordScreen(
    initialLandmarkId: String? = null,
    archivedMedia: ArchivedMedia? = null,
    onInitialLandmarkConsumed: () -> Unit = {},
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val uploadService = remember { UploadService(context) }
    val isUploading by uploadService.isUploading.collectAsState()
    val uploadStage by uploadService.stage.collectAsState()
    val uploadStatus by uploadService.status.collectAsState()
    val uploadDetail by uploadService.detail.collectAsState()

    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val isHardNegativeUploading by hardNegativeUploadService.isUploading.collectAsState()
    val hardNegativeStatus by hardNegativeUploadService.status.collectAsState()
    val hardNegativeProgress by hardNegativeUploadService.progress.collectAsState()

    val locationManager = remember { LocationManager(context) }
    val locationState by locationManager.state.collectAsState()
    val nearbyService = remember { NearbyLandmarkService() }
    val nearbyItems by nearbyService.items.collectAsState()
    val isNearbyLoading by nearbyService.isLoading.collectAsState()
    val nearbyError by nearbyService.errorMessage.collectAsState()

    var pickedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var showVideoPicker by remember { mutableStateOf(false) }
    var extractedLatitude by remember { mutableStateOf<Double?>(null) }
    var extractedLongitude by remember { mutableStateOf<Double?>(null) }
    var statusText by remember { mutableStateOf("No media selected.") }

    var showArchivePrompt by remember { mutableStateOf(false) }
    var showDiscardAlert by remember { mutableStateOf(false) }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var pendingArchiveLocation by remember { mutableStateOf<LookSeeLocationFix?>(null) }
    var showAutoQueueAlert by remember { mutableStateOf(false) }

    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    var showNegativeCamera by remember { mutableStateOf(false) }

    var selectedLandmark by remember { mutableStateOf<NearbyLandmark?>(null) }
    var hasAppliedInitialLandmark by remember { mutableStateOf(false) }
    var showVideoDurationAlert by remember { mutableStateOf(false) }
    var videoDurationAlertMessage by remember { mutableStateOf("") }

    val maxAllowedAccuracy = 75.0
    val radiusMeters = 100.0

    val hasMedia = pickedVideoUri != null
    val currentFix = (locationState as? LookSeeLocationState.Ready)?.fix
    val hasUsableLocation = currentFix != null && currentFix.accuracyMeters <= maxAllowedAccuracy

    val canSubmitUpload = (hasMedia || capturedNegativeVideo != null) &&
            selectedLandmark != null &&
            !isUploading &&
            !isHardNegativeUploading

    val areNegativePhotosLocked = isUploading || isHardNegativeUploading

    fun deleteTemporaryVideoIfNeeded(uri: Uri?) {
        if (uri != null) {
            Log.d("Cleanup", "Temporary video evaluated for deletion at $uri")
        }
    }

    fun clearScreen() {
        deleteTemporaryVideoIfNeeded(pickedVideoUri)
        capturedNegativeVideo?.deleteLocalFile()
        pickedVideoUri = null
        extractedLatitude = null
        extractedLongitude = null
        selectedLandmark = null
        capturedNegativeVideo = null

        if (initialLandmarkId != null) {
            hasAppliedInitialLandmark = false
            coroutineScope.launch { nearbyService.fetchNearby(currentFix?.latitude ?: 0.0, currentFix?.longitude ?: 0.0, radiusMeters) }
        }
        statusText = "No media selected."
        uploadService.reset()
        hardNegativeUploadService.reset()
    }

    fun discardPendingMedia() {
        pendingArchiveUri?.let { deleteTemporaryVideoIfNeeded(it) }
        pendingArchiveUri = null
        pendingArchiveLocation = null
        pickedVideoUri = null
        statusText = "No media selected."
    }

    fun applyPendingMedia() {
        deleteTemporaryVideoIfNeeded(pickedVideoUri)
        pendingArchiveUri?.let {
            pickedVideoUri = it
            statusText = "Selected video."
        }
        extractedLatitude = pendingArchiveLocation?.latitude ?: currentFix?.latitude
        extractedLongitude = pendingArchiveLocation?.longitude ?: currentFix?.longitude
        uploadService.reset()
        pendingArchiveUri = null
        pendingArchiveLocation = null
    }

    fun saveToArchiveFromPrompt() {
        val lat = pendingArchiveLocation?.latitude ?: currentFix?.latitude ?: 0.0
        val lon = pendingArchiveLocation?.longitude ?: currentFix?.longitude ?: 0.0
        val id = selectedLandmark?.landmarkId
        val label = selectedLandmark?.label ?: "Tier 2 Media"
        val desc = selectedLandmark?.shortDescription ?: ""
        val negFile = capturedNegativeVideo?.file

        coroutineScope.launch(Dispatchers.IO) {
            val uriToSave = pendingArchiveUri
            if (uriToSave != null) {
                val file = File(uriToSave.path ?: "")
                OfflineMediaManager.shared(context).archiveVideo(file, lat, lon, id, label, desc, "", negFile, true)
            }
            withContext(Dispatchers.Main) {
                discardPendingMedia()
                statusText = "Media securely saved to Offline Archive."
            }
        }
    }

    fun saveToArchiveFromForm() {
        val landmark = selectedLandmark ?: return
        val uri = pickedVideoUri ?: return
        val lat = extractedLatitude ?: currentFix?.latitude ?: 0.0
        val lon = extractedLongitude ?: currentFix?.longitude ?: 0.0
        val negFile = capturedNegativeVideo?.file

        coroutineScope.launch(Dispatchers.IO) {
            val file = File(uri.path ?: "")
            OfflineMediaManager.shared(context).archiveVideo(file, lat, lon, landmark.landmarkId, landmark.label, landmark.shortDescription, "", negFile, true)
            withContext(Dispatchers.Main) {
                clearScreen()
                statusText = "Media securely saved to Upload Queue."
            }
        }
    }

    fun startUpload() {
        val landmark = selectedLandmark ?: return
        val uri = pickedVideoUri ?: return
        if (!Monitor.getInstance(context).isConnected.value) {
            saveToArchiveFromForm()
            showAutoQueueAlert = true
            return
        }
        coroutineScope.launch {
            vm.fetchUserEmail()
            val idToken = vm.fetchIdToken()
            try {
                val file = File(uri.path ?: "")
                uploadService.upload(
                    vm.userEmail, idToken, landmark.label, landmark.landmarkId,
                    landmark.label, landmark.shortDescription, "",
                    extractedLatitude ?: currentFix?.latitude ?: 0.0,
                    extractedLongitude ?: currentFix?.longitude ?: 0.0,
                    currentFix?.accuracyMeters?.toDouble() ?: 0.0,
                    listOf(file), null
                )
                capturedNegativeVideo?.let { negVideo ->
                    hardNegativeUploadService.upload(landmark.landmarkId, idToken, negVideo)
                }
                clearScreen()
                statusText = "Media uploaded successfully."
            } catch (e: Exception) {
                statusText = "Upload failed."
                Log.e("Upload", "Failed", e)
            }
        }
    }

    suspend fun refreshNearbyIfPossible(force: Boolean = false) {
        val fix = currentFix ?: return
        if (locationState !is LookSeeLocationState.Ready || fix.accuracyMeters > maxAllowedAccuracy) return

        if (force) Log.d("Nearby", "Forcing refresh")
        nearbyService.fetchNearby(fix.latitude, fix.longitude, radiusMeters)

        if (!hasAppliedInitialLandmark && initialLandmarkId != null) {
            val match = nearbyItems.find { it.landmarkId == initialLandmarkId }
            if (match != null) {
                selectedLandmark = match
                deleteTemporaryVideoIfNeeded(pickedVideoUri)
                pickedVideoUri = null
                statusText = "No media selected."
                uploadService.reset()
                hasAppliedInitialLandmark = true
                onInitialLandmarkConsumed()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (archivedMedia != null) {
            pickedVideoUri = Uri.fromFile(OfflineMediaManager.shared(context).getFile(archivedMedia))
            extractedLatitude = archivedMedia.latitude
            extractedLongitude = archivedMedia.longitude
            statusText = "Loaded archived media."
        }
        refreshNearbyIfPossible()
    }

    LaunchedEffect(initialLandmarkId) {
        if (initialLandmarkId != null) {
            hasAppliedInitialLandmark = false
            refreshNearbyIfPossible(force = true)
        }
    }

    LaunchedEffect(currentFix) {
        refreshNearbyIfPossible()
    }

    Box(modifier = Modifier.fillMaxSize().background(SystemGroupedBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (archivedMedia == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(4.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp), color = SecondaryGroupedBackground
                ) {
                    Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.VideoCall, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Tier 2 Upload", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Record a short video of a nearby landmark. Choose a valid landmark from the list below to help improve model recognition.", fontSize = 14.sp, color = Color.Gray, lineHeight = 20.sp)
                        }
                    }
                }

                Button(
                    onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showVideoPicker = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isUploading && archivedMedia == null
                ) {
                    Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Text("Record Media", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            pickedVideoUri?.let { uri ->
                UploadFormVideoPlayer(
                    uri = uri,
                    modifier = Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 16.dp).shadow(4.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
                )
            }

            if (statusText.isNotEmpty()) {
                Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (currentFix != null) {
                            Text("${currentFix.latitude}, ${currentFix.longitude}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Accuracy: ±${currentFix.accuracyMeters.toInt()}m", fontSize = 12.sp, color = Color.Gray)
                        } else if (locationState is LookSeeLocationState.Unavailable) {
                            Text("Location Denied", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                        } else {
                            Text("Requesting location…", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                    if (locationState is LookSeeLocationState.PermissionRequired) {
                        Button(
                            onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); locationManager.start() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor), shape = CircleShape
                        ) { Text("Enable", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    } else {
                        IconButton(
                            onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); coroutineScope.launch { refreshNearbyIfPossible(true) } },
                            enabled = !isUploading,
                            modifier = Modifier.background(PrimaryColor.copy(alpha = 0.1f), CircleShape)
                        ) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = PrimaryColor, modifier = Modifier.size(20.dp)) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nearby Landmarks", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))

                if (nearbyItems.isEmpty() && isNearbyLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = 20.dp).size(24.dp))
                } else if (nearbyError != null) {
                    Text("Error: $nearbyError", fontSize = 14.sp, color = Color.Red, modifier = Modifier.padding(horizontal = 20.dp))
                } else if (!hasUsableLocation) {
                    Text("Nearby landmarks will appear once location is available.", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                } else if (nearbyItems.isEmpty()) {
                    val radiusStr = radiusMeters.toInt().toString()
                    Text("No landmarks found within $radiusStr meters.", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        nearbyItems.forEach { landmark ->
                            val isSelected = selectedLandmark?.landmarkId == landmark.landmarkId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(2.dp, RoundedCornerShape(20.dp))
                                    .background(if (isSelected) PrimaryColor.copy(alpha = 0.1f) else SecondaryGroupedBackground, RoundedCornerShape(20.dp))
                                    .border(if (isSelected) 2.dp else 0.dp, if (isSelected) PrimaryColor else Color.Transparent, RoundedCornerShape(20.dp))
                                    .clickable(!isUploading) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedLandmark = landmark
                                        if (initialLandmarkId != null && !hasAppliedInitialLandmark) {
                                            hasAppliedInitialLandmark = true
                                            onInitialLandmarkConsumed()
                                        }
                                        uploadService.reset()
                                    }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) PrimaryColor else Color.Gray, modifier = Modifier.size(22.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(landmark.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text("${landmark.distanceMeters.toInt()}m", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    }
                                    Text(landmark.shortDescription, fontSize = 14.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }

                if (selectedLandmark != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).shadow(4.dp, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp), color = SecondaryGroupedBackground
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("SELECTED TARGET", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(selectedLandmark!!.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${selectedLandmark!!.distanceMeters.toInt()} meters away", fontSize = 14.sp, color = PrimaryColor)
                        }
                    }
                }
            }

            if (selectedLandmark != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp), color = SecondaryGroupedBackground) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Negative Background", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    Text("Optional: Add a >= 10s pan of the surrounding area to improve model recognition.", fontSize = 14.sp, color = Color.Gray)
                                }
                                Icon(
                                    imageVector = if (capturedNegativeVideo != null) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (capturedNegativeVideo != null) Color.Green else Color(0xFFFFA000),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Button(
                                onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showNegativeCamera = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = OverlayBackground, disabledContainerColor = OverlayBackground.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !areNegativePhotosLocked
                            ) {
                                Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Text(if (capturedNegativeVideo == null) "Record Negative" else "Re-record Negative", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            capturedNegativeVideo?.let { negVideo ->
                                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                                    UploadFormVideoPlayer(uri = Uri.fromFile(negVideo.file), modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
                                    IconButton(
                                        onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); negVideo.deleteLocalFile(); capturedNegativeVideo = null },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape).size(32.dp),
                                        enabled = !areNegativePhotosLocked
                                    ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (archivedMedia != null) {
                            Button(
                                onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() },
                                modifier = Modifier.size(60.dp), colors = ButtonDefaults.buttonColors(containerColor = SecondaryGroupedBackground), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.Black) }
                        } else if (hasMedia && !isUploading) {
                            Button(
                                onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); saveToArchiveFromForm() },
                                modifier = Modifier.size(60.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                            ) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Archive", tint = Color.White) }
                        }

                        Button(
                            onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); startUpload() },
                            modifier = Modifier.weight(1f).height(60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (canSubmitUpload) PrimaryColor else Color.Gray.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(16.dp),
                            enabled = canSubmitUpload
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isUploading || isHardNegativeUploading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Uploading...", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                    Text("Upload Media", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (archivedMedia == null && hasMedia && !isUploading) {
                            Button(
                                onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showDiscardAlert = true },
                                modifier = Modifier.size(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)), shape = RoundedCornerShape(16.dp)
                            ) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }
                        }
                    }

                    if (uploadStage != PositiveUploadStage.IDLE) {
                        Surface(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp), color = SecondaryGroupedBackground) {
                            Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                if (isUploading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                else Icon(Icons.Default.Info, contentDescription = null, tint = if (uploadStage == PositiveUploadStage.COMPLETE) Color.Green else if (uploadStage == PositiveUploadStage.FAILED) Color.Red else PrimaryColor, modifier = Modifier.size(24.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(uploadStatus, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(uploadDetail, fontSize = 14.sp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    if (hardNegativeStatus != "Idle") {
                        Surface(modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp), color = SecondaryGroupedBackground) {
                            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                    if (isHardNegativeUploading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    else Icon(Icons.Default.Videocam, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(24.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Reference Video", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text(hardNegativeStatus, fontSize = 14.sp, color = Color.Gray)
                                    }
                                }
                                if (isHardNegativeUploading) {
                                    LinearProgressIndicator(progress = { hardNegativeProgress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = PrimaryColor)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showArchivePrompt) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).background(OverlayBackground, RoundedCornerShape(32.dp)).border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp)).padding(30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Box(modifier = Modifier.size(70.dp).background(PrimaryColor.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Media Captured", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("What would you like to do with this media? You can upload it now or save it to your offline archive.", fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showArchivePrompt = false; applyPendingMedia() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor), shape = RoundedCornerShape(16.dp)) { Text("Upload Now", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        Button(onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showArchivePrompt = false; saveToArchiveFromPrompt() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)), shape = RoundedCornerShape(16.dp)) { Text("Save to Offline Archive", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        Button(onClick = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress); showArchivePrompt = false; discardPendingMedia() }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)), shape = RoundedCornerShape(16.dp)) { Text("Discard", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Red) }
                    }
                }
            }
        }
    }

    if (showVideoPicker) {
        Tier2CameraView(
            onVideoRecorded = { uri, location ->
                pendingArchiveUri = uri
                pendingArchiveLocation = location
                showVideoPicker = false
                showArchivePrompt = true
            },
            onDismiss = { showVideoPicker = false }
        )
    }

    if (showNegativeCamera) {
        NegativeVideoCameraView(
            onDone = { video ->
                capturedNegativeVideo = video
                showNegativeCamera = false
            },
            onDismiss = { showNegativeCamera = false }
        )
    }

    if (showVideoDurationAlert) {
        AlertDialog(onDismissRequest = { showVideoDurationAlert = false }, confirmButton = { TextButton(onClick = { showVideoDurationAlert = false }) { Text("OK") } }, title = { Text("Invalid Video Length") }, text = { Text(videoDurationAlertMessage) })
    }

    if (showDiscardAlert) {
        AlertDialog(onDismissRequest = { showDiscardAlert = false }, title = { Text("Discard this upload?") }, text = { Text("This will remove the media and clear the form.") }, confirmButton = { TextButton(onClick = { clearScreen(); showDiscardAlert = false }) { Text("Discard", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showDiscardAlert = false }) { Text("Cancel") } })
    }

    if (showAutoQueueAlert) {
        AlertDialog(onDismissRequest = { showAutoQueueAlert = false }, title = { Text("Connection Offline") }, text = { Text("You currently have no internet connection. This media has been securely added to your Upload Queue and will automatically sync when service returns!") }, confirmButton = { TextButton(onClick = { showAutoQueueAlert = false }) { Text("OK") } })
    }
}

@Composable
fun Tier2CameraView(
    onVideoRecorded: (Uri, LookSeeLocationFix?) -> Unit,
    onDismiss: () -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview Placeholder
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Camera Preview", color = Color.White)
            ViewfinderCircle()
        }

        // Guided Overlay
        GuidedCaptureOverlay(
            isNegative = false,
            isRecording = isRecording
        )

        // UI Controls
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            // Shutter Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.White)
                    .clickable { isRecording = !isRecording },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Box(modifier = Modifier.size(30.dp).background(Color.White, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
fun ViewfinderCircle() {
    Canvas(modifier = Modifier.size(240.dp)) {
        drawCircle(
            color = Color.White.copy(alpha = 0.3f),
            radius = size.minDimension / 2,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun UploadFormVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PlayCircleOutline, contentDescription = "Play Video", tint = Color.White, modifier = Modifier.size(48.dp))
    }
}
