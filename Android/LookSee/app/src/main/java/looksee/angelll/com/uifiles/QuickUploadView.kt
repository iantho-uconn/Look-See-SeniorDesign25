package looksee.angelll.com.uifiles

import android.net.Uri
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
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import looksee.angelll.com.models.NearbyLandmark
import looksee.angelll.com.services.UploadService
import looksee.angelll.com.services.UploadStage // Assuming this enum exists in your service
import looksee.angelll.com.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickUploadView(
    landmark: NearbyLandmark,
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val uploadService = remember { UploadService() }

    // 🚀 FIXED: Collect the StateFlows here!
    val isUploading by uploadService.isUploading.collectAsState()
    val uploadStage by uploadService.stage.collectAsState()
    val uploadProgress by uploadService.progress.collectAsState()
    val uploadStatus by uploadService.status.collectAsState()

    val hasActiveSubscription by vm.hasActiveSubscription.collectAsState()
    val tokenBalance by vm.tokenBalance.collectAsState()

    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var isVideo by remember { mutableStateOf(false) }

    var showLimitAlert by remember { mutableStateOf(false) }
    var limitAlertTitle by remember { mutableStateOf("") }
    var limitAlertMessage by remember { mutableStateOf("") }

    var showCaptureBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val bgDark = Color(0.04f, 0.04f, 0.06f)
    val panelBg = Color(0.08f, 0.08f, 0.08f)
    val accentCyan = Color(0.0f, 0.8f, 1.0f)
    val primaryBlue = Color(0.11f, 0.22f, 0.55f)

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            isVideo = uri.toString().contains("video") || uri.toString().endsWith(".mp4")
        }
    }

    val cameraPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // Handle bitmap save logic
    }

    Box(modifier = Modifier.fillMaxSize().background(bgDark)) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !(isUploading && uploadStage != UploadStage.COMPLETE)
                ) {
                    Text("Abort", fontFamily = FontFamily.Monospace, color = Color.Red, fontSize = 16.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TARGETING LANDMARK", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = accentCyan, fontSize = 12.sp)
                    Text(landmark.label, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.FilterCenterFocus, contentDescription = null, tint = accentCyan, modifier = Modifier.size(32.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(420.dp)
                    .shadow(if (selectedMediaUri == null) 0.dp else 10.dp, RoundedCornerShape(24.dp), spotColor = accentCyan.copy(alpha = 0.3f))
                    .background(panelBg, RoundedCornerShape(24.dp))
                    .border(if (selectedMediaUri == null) 1.dp else 2.dp, if (selectedMediaUri == null) Color.Gray.copy(alpha = 0.3f) else accentCyan.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            ) {
                if (selectedMediaUri != null) {
                    if (isVideo) {
                        PositiveSafeVideoPlayer(uri = selectedMediaUri!!)
                    } else {
                        AsyncImage(
                            model = selectedMediaUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                        )
                    }

                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                        IconButton(
                            onClick = {
                                selectedMediaUri = null
                                uploadService.reset()
                            },
                            enabled = !isUploading,
                            modifier = Modifier.background(Color.Red.copy(alpha = 0.8f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("AWAITING TRAINING DATA", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = Color.Gray, fontSize = 14.sp)

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(modifier = Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = { showCaptureBottomSheet = true },
                                modifier = Modifier.weight(1f).height(80.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f), contentColor = accentCyan)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Spacer(Modifier.height(8.dp))
                                    Text("CAPTURE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = { galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                modifier = Modifier.weight(1f).height(80.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f), contentColor = Color.White)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Spacer(Modifier.height(8.dp))
                                    Text("BROWSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Videos must be 15 - 60 seconds.", fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isUploading || uploadStage == UploadStage.COMPLETE) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (uploadStage) {
                        UploadStage.COMPLETE -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(32.dp))
                        UploadStage.FAILED -> Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(32.dp))
                        else -> CircularProgressIndicator(color = accentCyan, progress = uploadProgress)
                    }

                    val statusColor = when (uploadStage) {
                        UploadStage.COMPLETE -> Color.Green
                        UploadStage.FAILED -> Color.Red
                        else -> accentCyan
                    }

                    Text(uploadStatus, fontFamily = FontFamily.Monospace, color = statusColor, fontSize = 12.sp)

                    if (uploadStage == UploadStage.COMPLETE) {
                        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                            Text("DONE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (!hasActiveSubscription) {
                            limitAlertTitle = "Subscription Required"
                            limitAlertMessage = "You need an active subscription or Free Trial to upload landmarks."
                            showLimitAlert = true
                        } else if (tokenBalance <= 0) {
                            limitAlertTitle = "Out of Tokens"
                            limitAlertMessage = "You need 1 token to upload a new landmark. Purchase a token pack in Settings."
                            showLimitAlert = true
                        } else {
                            coroutineScope.launch {
                                vm.fetchUserDetails()
                                val idToken = vm.fetchIdToken()
                                val uploadVideoUrl = if (isVideo) selectedMediaUri else null

                                try {
                                    uploadService.upload(
                                        userEmail = vm.userEmail.value, // Collect values for upload params
                                        idToken = idToken,
                                        label = landmark.label,
                                        landmarkId = landmark.landmarkId,
                                        landmarkLabel = landmark.label,
                                        shortDescription = landmark.shortDescription,
                                        userDescription = null,
                                        latitude = landmark.latitude,
                                        longitude = landmark.longitude,
                                        horizontalAccuracy = 10.0,
                                        videoURLs = uploadVideoUrl?.let { listOf(it.toString()) } ?: emptyList(),
                                        imageUri = if (!isVideo) selectedMediaUri else null
                                    )

                                    vm.tokenBalance.value -= 1
                                    vm.activeLandmarksCount.value += 1
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    },
                    enabled = selectedMediaUri != null && !isUploading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp).height(56.dp).shadow(if (selectedMediaUri == null) 0.dp else 8.dp, RoundedCornerShape(16.dp), spotColor = primaryBlue.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMediaUri == null) Color.White.copy(alpha = 0.05f) else primaryBlue,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                        contentColor = Color.White,
                        disabledContentColor = Color.Gray
                    )
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Text("INITIATE UPLOAD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showCaptureBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showCaptureBottomSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp, top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select Media Type", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 20.dp))

                Button(onClick = { showCaptureBottomSheet = false; cameraPhotoLauncher.launch(null) }, modifier = Modifier.fillMaxWidth(0.8f).padding(bottom = 12.dp)) { Text("Take a Photo") }
                Button(onClick = { showCaptureBottomSheet = false }, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Record a Video") }
            }
        }
    }

    if (showLimitAlert) {
        AlertDialog(
            onDismissRequest = { showLimitAlert = false },
            title = { Text(limitAlertTitle) },
            text = { Text(limitAlertMessage) },
            confirmButton = { TextButton(onClick = { showLimitAlert = false }) { Text("OK") } }
        )
    }
}