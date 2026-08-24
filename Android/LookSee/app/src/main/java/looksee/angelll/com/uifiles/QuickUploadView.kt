package looksee.angelll.com.uifiles

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

enum class ActivePicker {
    Camera, Library
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickUploadView(
    landmark: NearbyLandmark,
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    // Red until UploadService.kt is added!
    val uploadService = remember { UploadService() }

    var activePicker by remember { mutableStateOf<ActivePicker?>(null) }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var isVideo by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    // Tech UI Colors
    val bgDark = Color(0xFF0A0A0F)
    val panelBg = Color(0xFF141414)
    val accentCyan = Color(0xFF00CCFF)
    val primaryBlue = Color(0xFF1C388C)

    suspend fun triggerRealUpload() {
        val uri = selectedMediaUri ?: return

        vm.fetchUserDetails()
        val idToken = vm.fetchIdToken()

        val uploadImage = if (isVideo) null else File(uri.path ?: "") // Simplified for Android mapping
        val uploadVideoUri = if (isVideo) uri else null

        try {
            uploadService.upload(
                userEmail = vm.userEmail,
                idToken = idToken,
                label = landmark.label,
                landmarkId = landmark.landmarkId,
                landmarkLabel = landmark.label,
                shortDescription = landmark.shortDescription,
                userDescription = null,
                latitude = landmark.latitude,
                longitude = landmark.longitude,
                horizontalAccuracy = 10.0,
                videoURLs = uploadVideoUri?.let { listOf(it) } ?: emptyList(),
                image = uploadImage
            )
            println("✅ QuickUpload Completed Successfully")

            // 🚀 DEDUCT 1 TOKEN ON THE FRONTEND UI
            vm.tokenBalance -= 1
            vm.activeLandmarksCount += 1
        } catch (e: Exception) {
            println("❌ QuickUpload Failed: ${e.localizedMessage}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !(uploadService.isUploading && uploadService.stage != UploadStage.Complete)
                    ) {
                        Text(
                            "Abort",
                            fontFamily = FontFamily.Monospace,
                            color = Color.Red,
                            fontSize = 14.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = bgDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "TARGETING LANDMARK",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = accentCyan
                    )
                    Text(
                        landmark.label,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.FilterCenterFocus,
                    contentDescription = null,
                    tint = accentCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Media Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(panelBg, RoundedCornerShape(24.dp))
                    .border(
                        width = if (selectedMediaUri == null) 1.dp else 2.dp,
                        color = if (selectedMediaUri == null) Color.Gray.copy(alpha = 0.3f) else accentCyan.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .shadow(
                        elevation = if (selectedMediaUri == null) 0.dp else 10.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = accentCyan.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selectedMediaUri != null) {
                    if (isVideo) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(selectedMediaUri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                        )
                    } else {
                        AsyncImage(
                            model = selectedMediaUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                        )
                    }

                    // Close Button
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                        IconButton(
                            onClick = {
                                selectedMediaUri = null
                                uploadService.reset()
                            },
                            enabled = !uploadService.isUploading,
                            modifier = Modifier.size(36.dp).background(Color.Red.copy(alpha = 0.8f), CircleShape)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPasteGo,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.Gray
                        )

                        Text(
                            "AWAITING TRAINING DATA",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.Gray
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 10.dp)
                        ) {
                            // Camera Button
                            Button(
                                onClick = { activePicker = ActivePicker.Camera },
                                modifier = Modifier.weight(1f).height(80.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.CameraEnhance, contentDescription = null, tint = accentCyan, modifier = Modifier.size(24.dp))
                                    Text("CAPTURE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accentCyan)
                                }
                            }

                            // Library Button
                            Button(
                                onClick = { activePicker = ActivePicker.Library },
                                modifier = Modifier.weight(1f).height(80.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                    Text("BROWSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }

                        Text(
                            "Videos must be 30 - 90 seconds.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Action Area
            if (uploadService.isUploading || uploadService.stage == UploadStage.Complete) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (uploadService.stage) {
                        UploadStage.Complete -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(28.dp))
                        UploadStage.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(28.dp))
                        else -> LinearProgressIndicator(
                            progress = { uploadService.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = accentCyan,
                            trackColor = accentCyan.copy(alpha = 0.2f)
                        )
                    }

                    Text(
                        uploadService.status,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (uploadService.stage == UploadStage.Complete) Color.Green else if (uploadService.stage == UploadStage.Failed) Color.Red else accentCyan
                    )

                    if (uploadService.stage == UploadStage.Complete) {
                        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                            Text("DONE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (!vm.hasActiveSubscription) {
                            limitAlertTitle = "Subscription Required"
                            limitAlertMessage = "You need an active subscription or Free Trial to upload landmarks."
                            showLimitAlert = true
                        } else if (vm.tokenBalance <= 0) {
                            limitAlertTitle = "Out of Tokens"
                            limitAlertMessage = "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                            showLimitAlert = true
                        } else {
                            coroutineScope.launch { triggerRealUpload() }
                        }
                    },
                    enabled = selectedMediaUri != null && !uploadService.isUploading,
                    modifier = Modifier.fillMaxWidth().height(60.dp).padding(bottom = 20.dp).shadow(if (selectedMediaUri == null) 0.dp else 8.dp, RoundedCornerShape(16.dp), spotColor = primaryBlue.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMediaUri == null) Color.White.copy(alpha = 0.05f) else primaryBlue,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Public, contentDescription = null, tint = if (selectedMediaUri == null) Color.Gray else Color.White)
                        Text(
                            "INITIATE UPLOAD",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (selectedMediaUri == null) Color.Gray else Color.White
                        )
                    }
                }
            }
        }
    }

    // Media Picker Logic
    if (activePicker != null) {
        MediaPicker(
            sourceType = activePicker!!,
            onMediaPicked = { uri, isVid ->
                selectedMediaUri = uri
                isVideo = isVid
                activePicker = null
            },
            onDismiss = { activePicker = null }
        )
    }

    // Alert
    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle) },
            text = { Text(limitAlertMessage) },
            confirmButton = {
                TextButton(onClick = { showLimitAlert = false }) { Text("OK") }
            }
        )
    }
}

@Composable
fun MediaPicker(
    sourceType: ActivePicker,
    onMediaPicked: (Uri, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val isVid = uri.toString().contains("video") || uri.toString().endsWith(".mp4")
            onMediaPicked(uri, isVid)
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(sourceType) {
        if (sourceType == ActivePicker.Library) {
            galleryLauncher.launch("*/*") // Allows both image and video
        } else {
            // Stub for camera launch, as Android separates Image and Video capture intents.
            onDismiss()
        }
    }
}