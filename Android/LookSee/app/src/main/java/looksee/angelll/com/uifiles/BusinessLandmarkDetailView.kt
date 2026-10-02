package looksee.angelll.com.uifiles

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.models.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.viewmodels.AuthViewModel
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarkDetailView(
    vm: AuthViewModel,
    initialLandmark: BusinessLandmark,
    onLandmarkUpdated: (BusinessLandmark) -> Unit = {},
    onLandmarkDeleted: (String) -> Unit = {},
    onNavigate: (String, Any?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val primaryColor = AppleBlue
    val secondaryGrouped = Color(0xFF1C1C1E)

    val landmarkService = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    var landmark by remember { mutableStateOf(initialLandmark) }
    var promotions by remember { mutableStateOf<List<BusinessPromotion>>(emptyList()) }
    var isLoadingPromotions by remember { mutableStateOf(false) }
    var promotionErrorMessage by remember { mutableStateOf<String?>(null) }

    // Header State
    var displayedShortDescription by remember { mutableStateOf(landmark.shortDescription?.trim() ?: "") }
    var showEditDescriptionSheet by remember { mutableStateOf(false) }
    var displayedWebsiteUrl by remember { mutableStateOf(landmark.websiteUrl?.trim() ?: "") }
    var showEditWebsiteSheet by remember { mutableStateOf(false) }

    // Management State
    var isSavingManagement by remember { mutableStateOf(false) }
    var managementErrorMessage by remember { mutableStateOf<String?>(null) }

    // Media State
    var isUploadingMedia by remember { mutableStateOf(false) }
    var activeUploadRole by remember { mutableStateOf<BusinessDatasetRole?>(null) }
    var uploadStatusMessage by remember { mutableStateOf<String?>(null) }
    var uploadErrorMessage by remember { mutableStateOf<String?>(null) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }

    var showPositiveCamera by remember { mutableStateOf(false) }
    var showNegativeCamera by remember { mutableStateOf(false) }

    // Promotions State
    var showPromotionEditor by remember { mutableStateOf(false) }
    var promotionEditorContext by remember { mutableStateOf<BusinessPromotionEditorContext?>(null) }
    var promotionPendingDelete by remember { mutableStateOf<BusinessPromotion?>(null) }
    var savingPromotionIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Delete State
    var showDeleteSheet by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }
    var isDeletingLandmark by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun uploadMediaBatch(uris: List<Uri>, role: BusinessDatasetRole) {
        isUploadingMedia = true
        activeUploadRole = role
        uploadStatusMessage = null
        uploadErrorMessage = null

        var completedCount = 0
        var failedCount = 0

        uris.forEachIndexed { index, uri ->
            uploadProgressText = "Uploading item ${index + 1} of ${uris.size}..."
            try {
                // 🚀 THE FIX: Offload file reading and uploading to a background thread to prevent UI blocks
                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Could not open file stream")
                    val bytes = inputStream.readBytes()
                    inputStream.close()

                    var mimeType = context.contentResolver.getType(uri)
                    if (mimeType == null) {
                        val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.takeIf { it.isNotEmpty() }
                            ?: File(uri.path ?: "").extension
                        mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "video/mp4"
                    }

                    val isVideo = mimeType.startsWith("video/") || uri.toString().endsWith(".mp4", true) || uri.toString().endsWith(".mov", true)
                    val kind = if (isVideo) BusinessMediaKind.VIDEO else BusinessMediaKind.PHOTO
                    val fileExt = if (isVideo) "mp4" else "jpg"

                    // 🚀 THE FIX: Strip all special characters, spaces, and punctuation to prevent AWS S3 Signature mismatches!
                    val safeLabel = landmark.label.replace(Regex("[^A-Za-z0-9]"), "_")
                    val filename = "${safeLabel}_${role.wireValue}_${index}_${UUID.randomUUID()}.$fileExt"

                    landmarkService.uploadBusinessMedia(
                        landmarkId = landmark.landmarkId,
                        datasetRole = role,
                        mediaKind = kind,
                        filename = filename,
                        contentType = mimeType,
                        data = bytes
                    )
                }
                completedCount++
            } catch (e: Exception) {
                Log.e("LookSee_Debug_Upload", "Failed to upload item $index", e)
                failedCount++
            }
        }

        isUploadingMedia = false
        if (failedCount == 0) {
            uploadStatusMessage = "$completedCount item(s) uploaded successfully."
        } else {
            uploadErrorMessage = "$failedCount item(s) failed to upload."
            if (completedCount > 0) uploadStatusMessage = "$completedCount item(s) uploaded successfully."
        }
        uploadProgressText = null
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                uploadErrorMessage = null
                val validUris = mutableListOf<Uri>()
                var overLimitCount = 0

                uris.forEach { uri ->
                    val mimeType = context.contentResolver.getType(uri)
                    if (mimeType?.startsWith("video/") == true) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationSecs = (durationStr?.toLongOrNull() ?: 0L) / 1000.0
                            if (durationSecs > 90) {
                                overLimitCount++
                            } else {
                                validUris.add(uri)
                            }
                        } catch (e: Exception) {
                            validUris.add(uri)
                        } finally {
                            retriever.release()
                        }
                    } else {
                        validUris.add(uri)
                    }
                }

                if (overLimitCount > 0) {
                    uploadErrorMessage = "$overLimitCount video(s) were not uploaded. Gallery videos must be 90 seconds or shorter."
                }

                if (validUris.isNotEmpty()) {
                    uploadMediaBatch(validUris, activeUploadRole ?: BusinessDatasetRole.POSITIVE)
                }
            }
        }
    }

    suspend fun loadPromotions() {
        isLoadingPromotions = true
        promotionErrorMessage = null
        try {
            val response = promotionService.fetchPromotions(landmark.landmarkId)
            promotions = response.items
        } catch (e: Exception) {
            promotionErrorMessage = e.localizedMessage
        } finally {
            isLoadingPromotions = false
        }
    }

    LaunchedEffect(landmark.landmarkId) {
        loadPromotions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Landmark Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = primaryColor
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            item {
                val status = landmark.status ?: ""
                val isProcessing = landmark.isProcessing

                val bannerColor: Color
                val bannerIcon: ImageVector
                val bannerTitle: String
                val bannerMessage: String

                when {
                    status == "NEEDS_MORE_MEDIA" -> {
                        bannerColor = Color(0xFFFF453A)
                        bannerIcon = Icons.Default.Warning
                        bannerTitle = landmark.displayStatus.uppercase()
                        bannerMessage = "Not enough video data to train. Please record more media below."
                    }
                    isProcessing || listOf("PREPARING_DATA", "PENDING_TRAINING", "TRAINING_MODEL", "OPTIMIZING_MODEL").contains(status) -> {
                        bannerColor = when(status) {
                            "PREPARING_DATA" -> Color(0xFFFF9F0A)
                            "PENDING_TRAINING" -> Color(0xFFAF52DE)
                            "TRAINING_MODEL" -> Color(0xFFFFD60A)
                            "OPTIMIZING_MODEL" -> Color(0xFF64D2FF)
                            else -> Color(0xFFFF9F0A)
                        }
                        bannerIcon = Icons.Default.HourglassBottom
                        bannerTitle = landmark.displayStatus.uppercase()
                        bannerMessage = "Your landmark is currently in progress of being trained. Training is done overnight starting at 7 PM EST."
                    }
                    landmark.isActive == true -> {
                        bannerColor = Color(0xFF32D74B)
                        bannerIcon = Icons.Default.VerifiedUser
                        bannerTitle = "ACTIVE"
                        bannerMessage = "Your landmark is trained and able to be detected by users."
                    }
                    else -> {
                        bannerColor = Color.Gray
                        bannerIcon = Icons.Default.PauseCircle
                        bannerTitle = "INACTIVE"
                        bannerMessage = "This landmark is currently disabled and cannot be detected."
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                        .background(bannerColor.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(bannerIcon, contentDescription = null, tint = bannerColor, modifier = Modifier.size(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(bannerTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = bannerColor)
                        Text(bannerMessage, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray, lineHeight = 20.sp)
                    }
                }
            }

            item {
                LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = landmark.label.ifEmpty { "Untitled Landmark" },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = displayedShortDescription.ifEmpty { "No description available." },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { showEditDescriptionSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor.copy(alpha = 0.1f), contentColor = primaryColor),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit Description", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color.Gray.copy(alpha = 0.2f))

                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("WEBSITE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(
                                text = displayedWebsiteUrl.ifEmpty { "No website added yet." },
                                fontSize = 14.sp,
                                fontWeight = if (displayedWebsiteUrl.isEmpty()) FontWeight.Medium else FontWeight.Bold,
                                color = if (displayedWebsiteUrl.isEmpty()) Color.Gray else Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { showEditWebsiteSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor.copy(alpha = 0.1f), contentColor = primaryColor),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (displayedWebsiteUrl.isEmpty()) "Add Website" else "Edit Website", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TRAINING DATASET", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val processed = landmark.cleanFrameCount ?: 0
                        val required = landmark.requiredFrames ?: 2000

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("TOTAL LABELED FRAMES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                Text(
                                    "$processed",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (processed >= required) Color.Green else if (processed > 0) Color(0xFFFFA500) else Color.Red
                                )
                            }
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = primaryColor.copy(alpha = 0.2f), modifier = Modifier.size(32.dp))
                        }

                        if (processed < required) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))
                            Text(
                                "We recommend reaching $required frames for optimal AI detection accuracy. You can upload more media below.",
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MANAGEMENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                imageVector = if (landmark.isActive == true) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = if (landmark.isActive == true) Color.Green else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (landmark.isActive == true) "Active Landmark" else "Inactive Landmark",
                                color = if (landmark.isActive == true) Color.Green else Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = landmark.isActive ?: true,
                                onCheckedChange = { newVal ->
                                    coroutineScope.launch {
                                        isSavingManagement = true
                                        try {
                                            val updated = landmarkService.updateLandmarkSettings(landmark.landmarkId, isActive = newVal)
                                            landmark = updated
                                            onLandmarkUpdated(updated)
                                        } catch (e: Exception) {
                                            managementErrorMessage = e.localizedMessage
                                        } finally {
                                            isSavingManagement = false
                                        }
                                    }
                                },
                                enabled = !isSavingManagement
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                imageVector = if (landmark.promotionEnabled == true) Icons.Default.LocalOffer else Icons.Default.LocalOffer,
                                contentDescription = null,
                                tint = if (landmark.promotionEnabled == true) Color(0xFFFFA500) else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (landmark.promotionEnabled == true) "Promotions Enabled" else "Promotions Disabled",
                                color = if (landmark.promotionEnabled == true) Color(0xFFFFA500) else Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = landmark.promotionEnabled ?: false,
                                onCheckedChange = { newVal ->
                                    coroutineScope.launch {
                                        isSavingManagement = true
                                        try {
                                            val updated = landmarkService.updateLandmarkSettings(landmark.landmarkId, promotionEnabled = newVal)
                                            landmark = updated
                                            onLandmarkUpdated(updated)
                                        } catch (e: Exception) {
                                            managementErrorMessage = e.localizedMessage
                                        } finally {
                                            isSavingManagement = false
                                        }
                                    }
                                },
                                enabled = !isSavingManagement,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFA500), checkedTrackColor = Color(0xFFFFA500).copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PROMOTIONS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(
                            onClick = {
                                promotionEditorContext = BusinessPromotionEditorContext.Create()
                                showPromotionEditor = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.AddCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Add Promotion", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = primaryColor)
                            }
                        }

                        if (isLoadingPromotions) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally).padding(16.dp))
                        } else if (promotions.isEmpty()) {
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                            Text(
                                "No promotions have been added for this landmark yet.",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            promotions.forEach { promo ->
                                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                                PromotionItemRow(
                                    promotion = promo,
                                    isSaving = savingPromotionIds.contains(promo.id),
                                    onEnabledChange = { enabled ->
                                        coroutineScope.launch {
                                            savingPromotionIds = savingPromotionIds + promo.id
                                            try {
                                                promotionService.updatePromotion(landmark.landmarkId, promo.id, enabled = enabled)
                                                loadPromotions()
                                            } catch (e: Exception) {
                                                promotionErrorMessage = e.localizedMessage
                                            } finally {
                                                savingPromotionIds = savingPromotionIds - promo.id
                                            }
                                        }
                                    },
                                    onEdit = {
                                        promotionEditorContext = BusinessPromotionEditorContext.Edit(promo)
                                        showPromotionEditor = true
                                    },
                                    onDelete = { promotionPendingDelete = promo }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LOCATION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        DetailDataRow(label = "Latitude", value = String.format(java.util.Locale.US, "%.6f", landmark.latitude ?: 0.0))
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                        DetailDataRow(label = "Longitude", value = String.format(java.util.Locale.US, "%.6f", landmark.longitude ?: 0.0))
                    }
                }
            }

            val legacyPromotion = landmark.promotion
            if (!legacyPromotion.isNullOrBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("LEGACY PROMOTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                        LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = legacyPromotion,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MEDIA UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        BusinessMediaHistoryNavigationRow(
                            landmarkId = landmark.landmarkId,
                            landmarkLabel = landmark.label,
                            onClick = { onNavigate("BusinessMediaHistoryView", landmark) }
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        MediaUploadButton(
                            title = "Record Positive Video",
                            subtitle = "Record new views of this landmark with the camera.",
                            icon = Icons.Default.Videocam,
                            color = primaryColor,
                            onClick = { showPositiveCamera = true }
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        MediaUploadButton(
                            title = "Choose Positive Media",
                            subtitle = "Select photos or videos of this landmark.",
                            icon = Icons.Default.AddCircle,
                            color = primaryColor,
                            onClick = {
                                activeUploadRole = BusinessDatasetRole.POSITIVE
                                mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        MediaUploadButton(
                            title = "Record Negative Video",
                            subtitle = "Record nearby objects without including the landmark.",
                            icon = Icons.Default.Videocam,
                            color = Color(0xFFFFA500),
                            onClick = { showNegativeCamera = true }
                        )

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        MediaUploadButton(
                            title = "Choose Negative Examples",
                            subtitle = "Select nearby objects that are not this landmark.",
                            icon = Icons.Default.RemoveCircle,
                            color = Color(0xFFFFA500),
                            onClick = {
                                activeUploadRole = BusinessDatasetRole.HARD_NEGATIVE
                                mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        )

                        if (isUploadingMedia) {
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(uploadProgressText ?: "Uploading...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        uploadStatusMessage?.let { msg ->
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Color.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        uploadErrorMessage?.let { msg ->
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Color(0xFFFFA500), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DANGER ZONE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Button(
                            onClick = { showDeleteSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Delete Landmark", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Deleting a landmark removes it from your account and starts backend cleanup for cluster mappings, dataset files, and promotions. This cannot be undone.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IDENTIFIERS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        DetailDataRow(label = "Landmark ID", value = landmark.landmarkId)
                        landmark.ownerUserId?.takeIf { it.isNotEmpty() }?.let {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                            DetailDataRow(label = "Owner User ID", value = it)
                        }
                        landmark.userEmail?.takeIf { it.isNotEmpty() }?.let {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                            DetailDataRow(label = "Owner Email", value = it)
                        }
                        landmark.updatedAt?.takeIf { it.isNotEmpty() }?.let {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                            DetailDataRow(label = "Updated At", value = it)
                        }
                    }
                }
            }
        }
    }

    if (showEditDescriptionSheet) {
        var draft by remember { mutableStateOf(displayedShortDescription) }
        var isSaving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(onDismissRequest = { showEditDescriptionSheet = false }, containerColor = secondaryGrouped) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Black, focusedContainerColor = Color.Black, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("This description is shown to users when LookSee identifies this landmark.", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isSaving = true
                            try {
                                val updated = landmarkService.updateShortDescription(landmark.landmarkId, draft)
                                landmark = updated
                                displayedShortDescription = updated.shortDescription ?: draft
                                showEditDescriptionSheet = false
                            } catch (e: Exception) {
                                error = e.localizedMessage
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isSaving && draft.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showEditWebsiteSheet) {
        var draft by remember { mutableStateOf(displayedWebsiteUrl) }
        var isSaving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(onDismissRequest = { showEditWebsiteSheet = false }, containerColor = secondaryGrouped) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("WEBSITE URL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.Black, focusedContainerColor = Color.Black, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("Users will be able to open this website from the landmark popup. You can enter example.com or a full https:// URL. Leave it blank to clear the website.", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isSaving = true
                            try {
                                val updated = landmarkService.updateWebsiteUrl(landmark.landmarkId, draft)
                                landmark = updated
                                displayedWebsiteUrl = updated.websiteUrl ?: draft
                                showEditWebsiteSheet = false
                            } catch (e: Exception) {
                                error = e.localizedMessage
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showDeleteSheet) {
        ModalBottomSheet(onDismissRequest = { showDeleteSheet = false }, containerColor = secondaryGrouped) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Text("CONFIRM LANDMARK DELETION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                LookSeeCard {
                    Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Text(landmark.landmarkId, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                    OutlinedTextField(
                        value = deleteConfirmationText,
                        onValueChange = { deleteConfirmationText = it },
                        placeholder = { Text("delete landmark") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("To confirm, type exactly: delete landmark", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isDeletingLandmark = true
                            try {
                                landmarkService.deleteLandmark(landmark.landmarkId, deleteConfirmationText)
                                onLandmarkDeleted(landmark.landmarkId)
                                showDeleteSheet = false
                            } catch (e: Exception) {
                                deleteErrorMessage = e.localizedMessage
                            } finally {
                                isDeletingLandmark = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = deleteConfirmationText == "delete landmark" && !isDeletingLandmark,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    if (isDeletingLandmark) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Confirm Delete Landmark", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                deleteErrorMessage?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showPromotionEditor && promotionEditorContext != null) {
        Dialog(
            onDismissRequest = { showPromotionEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BusinessPromotionEditor(
                landmark = landmark,
                context = promotionEditorContext!!,
                onSaved = {
                    showPromotionEditor = false
                    coroutineScope.launch { loadPromotions() }
                },
                onDismiss = { showPromotionEditor = false }
            )
        }
    }

    if (promotionPendingDelete != null) {
        AlertDialog(
            onDismissRequest = { promotionPendingDelete = null },
            title = { Text("Delete Promotion?") },
            text = { Text("This promotion will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    val promo = promotionPendingDelete ?: return@TextButton
                    coroutineScope.launch {
                        try {
                            promotionService.deletePromotion(landmark.landmarkId, promo.id)
                            loadPromotions()
                        } catch (e: Exception) {
                            promotionErrorMessage = e.localizedMessage
                        } finally {
                            promotionPendingDelete = null
                        }
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { promotionPendingDelete = null }) { Text("Cancel") }
            },
            containerColor = secondaryGrouped,
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }

    if (showPositiveCamera) {
        Dialog(onDismissRequest = { showPositiveCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BusinessPositiveVideoCameraScreen(
                onDone = { uri ->
                    showPositiveCamera = false
                    coroutineScope.launch { uploadMediaBatch(listOf(uri), BusinessDatasetRole.POSITIVE) }
                },
                onDismiss = { showPositiveCamera = false }
            )
        }
    }

    if (showNegativeCamera) {
        Dialog(onDismissRequest = { showNegativeCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // 🚀 THE FIX: Properly hooks up the BUSINESS negative camera instead of standard
            BusinessNegativeVideoCameraView(
                onDone = { video ->
                    showNegativeCamera = false
                    coroutineScope.launch { uploadMediaBatch(listOf(Uri.fromFile(video.file)), BusinessDatasetRole.HARD_NEGATIVE) }
                },
                onDismiss = { showNegativeCamera = false }
            )
        }
    }
}

@Composable
private fun PromotionItemRow(
    promotion: BusinessPromotion,
    isSaving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(promotion.name.ifEmpty { "Untitled Promotion" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                if (promotion.description.isNotEmpty()) {
                    Text(promotion.description, color = Color.Gray, fontSize = 14.sp, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
                val statusColor = if (promotion.enabled) Color(0xFFFFA500) else Color.Gray
                Text(
                    text = if (promotion.enabled) "Active" else "Inactive",
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(statusColor.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Switch(
                checked = promotion.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !isSaving,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFA500), checkedTrackColor = Color(0xFFFFA500).copy(alpha = 0.5f))
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onEdit,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Edit", fontWeight = FontWeight.Bold, color = Color.White) }
            Button(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Delete", fontWeight = FontWeight.Bold, color = Color.Red) }
        }
    }
}

@Composable
private fun DetailDataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MediaUploadButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
    }
}