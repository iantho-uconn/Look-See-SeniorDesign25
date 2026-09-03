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
import looksee.angelll.com.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.ceil

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

    // Upload & Background Services
    val uploadService = remember { UploadService(context) }
    val isUploading by uploadService.isUploading.collectAsState()
    
    val hardNegativeUploadService = remember { HardNegativeUploadService() }
    val isHardNegativeUploading by hardNegativeUploadService.isUploading.collectAsState()

    val locationManager = remember { LocationManager(context) }
    val locationState by locationManager.state.collectAsState()

    // Form State
    var labelText by remember { mutableStateOf(existingLabel ?: "") }
    var shortDescription by remember { mutableStateOf(existingDescription ?: "") }
    var businessLandmarkId by remember { mutableStateOf(existingLandmarkId) }
    var showTextScanner by remember { mutableStateOf(false) }

    // Media State
    var pickedVideoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var clipDurations by remember { mutableStateOf<Map<Uri, Double>>(emptyMap()) }
    var capturedNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }
    
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

    // Constants from Swift
    val uiTargetDuration = if (existingSecondsNeeded != null) ceil(existingSecondsNeeded).toInt() else if (existingLandmarkId != null) 1 else 30
    val negativeTargetDuration = if (existingLandmarkId != null) 1 else 10
    val totalClipDuration = pickedVideoUris.sumOf { clipDurations[it] ?: 0.0 }
    val hasMinimumClipDuration = pickedImageUri != null || totalClipDuration >= 1.0

    val canUpload = !isUploading && !isHardNegativeUploading && !isStitchingVideos && !isFullSubmissionComplete &&
            (if (completedPositiveResult != null) (existingLandmarkId != null || capturedNegativeVideo != null)
            else ( (pickedVideoUris.isNotEmpty() || pickedImageUri != null) && labelText.isNotBlank() && shortDescription.isNotBlank() && (existingLandmarkId != null || capturedNegativeVideo != null) && hasMinimumClipDuration ))

    val arePositiveDetailsLocked = isUploading || isHardNegativeUploading || completedPositiveResult != null || isFullSubmissionComplete
    val areNegativePhotosLocked = isUploading || isHardNegativeUploading || isFullSubmissionComplete

    fun makeBusinessLandmarkId() = "landmark_${UUID.randomUUID().toString().replace("-", "").take(8)}"

    fun clearScreen() {
        pickedVideoUris = emptyList()
        clipDurations = emptyMap()
        pickedImageUri = null
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

        // Logic: Queue to Archive and wake up AutoUploadManager
        coroutineScope.launch {
            val lat = extractedLatitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.latitude ?: 0.0
            val lon = extractedLongitude ?: (locationState as? LookSeeLocationState.Ready)?.fix?.longitude ?: 0.0
            val idToSave = businessLandmarkId ?: makeBusinessLandmarkId()
            
            val offlineManager = OfflineMediaManager.shared(context)
            if (pickedVideoUris.isNotEmpty()) {
                val file = File(pickedVideoUris.first().path ?: "")
                offlineManager.archiveVideo(file, lat, lon, idToSave, labelText, shortDescription, "", capturedNegativeVideo?.file, false)
            }
            
            if (existingLandmarkId == null) {
                vm.tokenBalance -= 1
                vm.activeLandmarksCount += 1
            }
            
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
                uiTargetDuration = 90,
                minTotalTimeLimit = uiTargetDuration,
                onDone = { uris ->
                    pickedVideoUris = uris
                    // Simulating Stitching Logic
                    isStitchingVideos = true
                    coroutineScope.launch {
                        delay(1000)
                        isStitchingVideos = false
                        isFormVisible = true
                    }
                },
                onCancel = onDismiss
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F1A))
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 100.dp)
            ) {
                // Toolbar
                Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Media Preview Section
                if (pickedVideoUris.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (hasMinimumClipDuration) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                            Text("${String.format("%.1f", totalClipDuration)}s total — ready to upload", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (hasMinimumClipDuration) Color.Green else Color(0xFFFFA500))
                            
                            if (statusText.contains("Outbox", true) || statusText.contains("queued", true)) {
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        coroutineScope.launch {
                                            archivedMedia?.let {
                                                OfflineMediaManager.shared(context).prioritizeAndRetry(it)
                                                AutoUploadManager.shared(context).forceRetry()
                                            }
                                        }
                                    },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Blue.copy(alpha = 0.15f), contentColor = Color.Blue),
                                    shape = CircleShape
                                ) {
                                    Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        pickedVideoUris.forEach { uri ->
                            Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                                UploadFormVideoPlayer(uri)
                                if (!arePositiveDetailsLocked) {
                                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(32.dp).background(Color.Black.copy(0.6f), CircleShape).clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        clearScreen()
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Location Card
                LookSeeCard(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = AppleBlue)
                        Column {
                            val fix = (locationState as? LookSeeLocationState.Ready)?.fix
                            if (fix != null) {
                                Text("${fix.latitude}, ${fix.longitude}", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                                Text("Accuracy: ±${fix.accuracyMeters.toInt()}m", fontSize = 13.sp, color = Color.Gray)
                            } else {
                                Text("Requesting location...", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    }
                }

                if (isFormVisible) {
                    // Landmark Form
                    LookSeeSectionHeader("Landmark Label")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = labelText, onValueChange = { labelText = it },
                            placeholder = { Text("e.g., Gampel Pavilion", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            enabled = !arePositiveDetailsLocked
                        )
                    }

                    LookSeeSectionHeader("Short Description")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = shortDescription, onValueChange = { shortDescription = it },
                            placeholder = { Text("e.g., Front entrance", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            enabled = !arePositiveDetailsLocked
                        )
                    }

                    // Negative Background Section
                    if (existingLandmarkId == null) {
                        LookSeeSectionHeader("Negative Background")
                        LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Record a >= ${negativeTargetDuration}s video panning the area. Do NOT include the landmark.", fontSize = 14.sp, color = Color.Gray)
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                        UploadFormVideoPlayer(Uri.fromFile(capturedNegativeVideo!!.file))
                                    }
                                }
                            }
                        }
                    }

                    // Upload Button
                    Button(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); startFullSubmission() },
                        modifier = Modifier.padding(16.dp).fillMaxWidth().height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if(canUpload) AppleBlue else Color.DarkGray),
                        shape = RoundedCornerShape(16.dp),
                        enabled = canUpload
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Upload Landmark", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Overlays
        if (isStitchingVideos) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).zIndex(100f), contentAlignment = Alignment.Center) {
                LookSeeCard(modifier = Modifier.padding(32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Processing Video", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    // Alerts
    if (showBackgroundUploadAlert) {
        AlertDialog(
            onDismissRequest = { showBackgroundUploadAlert = false; onDismiss() },
            title = { Text("Upload Queued!") },
            text = { Text("Your landmark has been securely queued! It will upload in the background. Feel free to keep using the app.") },
            confirmButton = { TextButton(onClick = { showBackgroundUploadAlert = false; clearScreen() }) { Text("Record Another") } },
            dismissButton = { TextButton(onClick = { showBackgroundUploadAlert = false; onDismiss() }) { Text("Done") } },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}
