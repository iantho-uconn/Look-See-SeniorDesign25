package looksee.angelll.com.uifiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarkDetailView(
    initialLandmark: BusinessLandmark,
    onNavigate: (String, Any?) -> Unit,
    onDismiss: () -> Unit
) {
    var landmark by remember { mutableStateOf(initialLandmark) }
    var promotions by remember { mutableStateOf<List<BusinessPromotion>>(emptyList()) }
    var isLoadingPromotions by remember { mutableStateOf(false) }

    var selectedPromotion by remember { mutableStateOf<BusinessPromotion?>(null) }
    var showPromotionEditor by remember { mutableStateOf(false) }

    var showMediaPicker by remember { mutableStateOf(false) }
    var isUploadingMedia by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<String?>(null) }
    var lastUploadedVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val landmarkService = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    val landmarkId = landmark.landmarkId

    // Fetch promotions on load
    LaunchedEffect(landmarkId) {
        isLoadingPromotions = true
        try {
            val response = promotionService.fetchPromotions(landmarkId)
            promotions = response.items
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoadingPromotions = false
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                uploadMedia(it, BusinessMediaKind.PHOTO, context, landmarkService, landmarkId) { progress ->
                    uploadProgress = progress
                }
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val duration = getVideoDuration(it, context)
                    if (duration > 30000) { // 30 seconds limit
                        uploadProgress = "Video too long (max 30s)"
                        return@launch
                    }
                    uploadMedia(it, BusinessMediaKind.VIDEO, context, landmarkService, landmarkId) { progress ->
                        uploadProgress = progress
                    }
                } catch (e: Exception) {
                    uploadProgress = "Error: ${e.localizedMessage}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(landmark.label, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: More options */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Stats Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "Media",
                    value = "${landmark.cleanFrameCount ?: 0}",
                    subtitle = "Frames",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Status",
                    value = landmark.displayStatus,
                    subtitle = "Current",
                    modifier = Modifier.weight(1f),
                    valueColor = if (landmark.isActive == true) Color(0xFF34C759) else Color.Red
                )
            }

            // Info Section
            SettingsSection(header = "Information") {
                InfoRow(label = "Description", value = landmark.displayDescription)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray.copy(alpha = 0.3f))
                InfoRow(label = "Website", value = landmark.websiteUrl ?: "None")
            }

            // Promotions Section
            SettingsSection(header = "Promotions") {
                if (isLoadingPromotions) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (promotions.isEmpty()) {
                    Text(
                        "No active promotions for this landmark.",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                } else {
                    promotions.forEach { promo ->
                        PromotionRow(promo) {
                            selectedPromotion = promo
                            showPromotionEditor = true
                        }
                        if (promo != promotions.last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                }
                
                TextButton(
                    onClick = {
                        selectedPromotion = null
                        showPromotionEditor = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Promotion")
                    }
                }
            }

            // Quick Actions
            SettingsSection(header = "Actions") {
                ActionRow(icon = Icons.Default.PhotoCamera, label = "Upload Photos", tint = Color(0xFF007AFF)) {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray.copy(alpha = 0.3f))
                ActionRow(icon = Icons.Default.Videocam, label = "Upload Video", tint = Color(0xFF5856D6)) {
                    videoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray.copy(alpha = 0.3f))
                ActionRow(icon = Icons.Default.History, label = "View Media History", tint = Color.Gray) {
                    onNavigate("media_history", landmarkId)
                }
            }

            if (uploadProgress != null) {
                Surface(
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(uploadProgress!!, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showPromotionEditor) {
        val contextObj = if (selectedPromotion == null) {
            BusinessPromotionEditorContext.Create()
        } else {
            BusinessPromotionEditorContext.Edit(selectedPromotion!!)
        }
        
        Dialog(onDismissRequest = { showPromotionEditor = false }) {
            BusinessPromotionEditor(
                landmark = landmark,
                context = contextObj,
                onSaved = {
                    showPromotionEditor = false
                    // Refresh promotions
                    coroutineScope.launch {
                        try {
                            val response = promotionService.fetchPromotions(landmarkId)
                            promotions = response.items
                        } catch (_: Exception) {}
                    }
                },
                onDismiss = { showPromotionEditor = false }
            )
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier, valueColor: Color = Color.Black) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = valueColor)
            Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 15.sp)
    }
}

@Composable
private fun PromotionRow(promotion: BusinessPromotion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(promotion.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(promotion.description, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
    }
}

private suspend fun uploadMedia(
    uri: Uri,
    kind: BusinessMediaKind,
    context: Context,
    service: BusinessLandmarkService,
    landmarkId: String,
    onProgress: (String) -> Unit
) {
    onProgress("Preparing upload...")
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val bytes = inputStream.readBytes()
        inputStream.close()
        
        onProgress("Uploading...")
        service.uploadBusinessMedia(
            landmarkId = landmarkId,
            datasetRole = BusinessDatasetRole.POSITIVE,
            mediaKind = kind,
            filename = "upload_${System.currentTimeMillis()}.${if (kind == BusinessMediaKind.PHOTO) "jpg" else "mp4"}",
            contentType = if (kind == BusinessMediaKind.PHOTO) "image/jpeg" else "video/mp4",
            data = bytes
        )
        onProgress("Upload successful!")
        kotlinx.coroutines.delay(2000)
        onProgress("")
    } catch (e: Exception) {
        onProgress("Error: ${e.localizedMessage}")
    }
}

private fun getVideoDuration(uri: Uri, context: Context): Long {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(context, uri)
    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
    retriever.release()
    return time?.toLong() ?: 0L
}
