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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    onPromotionTitlesChanged: (String, List<String>) -> Unit = { _, _ -> },
    onNavigate: (String, Any?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val primaryColor = AppleBlue
    val secondaryGrouped = Color(0xFF1C1C1E)

    val maxPositiveVideoDurationSeconds = 120.0
    val maxNegativeVideoDurationSeconds = 45.0

    val landmarkService = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    var landmark by remember { mutableStateOf(initialLandmark) }
    var promotions by remember { mutableStateOf(emptyList<BusinessPromotion>()) }
    var isLoadingPromotions by remember { mutableStateOf(false) }
    val initialNullString: String? = null
    var promotionErrorMessage by remember { mutableStateOf(initialNullString) }

    val processingTrackerState by BusinessProcessingTracker.state.collectAsState()
    val isProcessingNewData = BusinessProcessingTracker.isLandmarkProcessingNewData(landmark, processingTrackerState)

    // Header & Website State
    var displayedShortDescription by remember { mutableStateOf(landmark.shortDescription?.trim() ?: "") }
    var showEditDescriptionSheet by remember { mutableStateOf(false) }
    var isSavingDescription by remember { mutableStateOf(false) }

    var displayedWebsiteUrl by remember { mutableStateOf(landmark.websiteUrl?.trim() ?: "") }
    var showEditWebsiteSheet by remember { mutableStateOf(false) }
    var isSavingWebsiteUrl by remember { mutableStateOf(false) }
    var websiteUrlErrorMessage by remember { mutableStateOf(initialNullString) }

    // Management State
    var displayedIsActive by remember { mutableStateOf(landmark.isActive ?: true) }
    var displayedPromotionEnabled by remember { mutableStateOf(landmark.promotionEnabled ?: false) }
    var displayedCleanFrameCount by remember { mutableStateOf(landmark.cleanFrameCount ?: 0) }
    var isSavingManagement by remember { mutableStateOf(false) }
    var managementErrorMessage by remember { mutableStateOf(initialNullString) }
    val initialNullJob: Job? = null
    var managementUpdateJob by remember { mutableStateOf(initialNullJob) }

    // Media State
    var pendingPositiveVideoUris by remember { mutableStateOf(emptyList<Uri>()) }
    val initialNullUri: Uri? = null
    var pendingNegativeVideoUri by remember { mutableStateOf(initialNullUri) }
    var selectedPositiveMediaUris by remember { mutableStateOf(emptyList<Uri>()) }
    var selectedNegativeMediaUris by remember { mutableStateOf(emptyList<Uri>()) }

    var isUploadingMedia by remember { mutableStateOf(false) }
    val initialNullRole: BusinessDatasetRole? = null
    var activeUploadRole by remember { mutableStateOf(initialNullRole) }
    var uploadStatusMessage by remember { mutableStateOf(initialNullString) }
    var uploadErrorMessage by remember { mutableStateOf(initialNullString) }
    var uploadProgressText by remember { mutableStateOf(initialNullString) }

    var showPositiveCamera by remember { mutableStateOf(false) }
    var showNegativeCamera by remember { mutableStateOf(false) }
    var pickerTargetRole by remember { mutableStateOf(BusinessDatasetRole.POSITIVE) }

    // Promotions State
    var showPromotionEditor by remember { mutableStateOf(false) }
    val initialNullEditorContext: BusinessPromotionEditorContext? = null
    var promotionEditorContext by remember { mutableStateOf(initialNullEditorContext) }
    val initialNullPromotion: BusinessPromotion? = null
    var promotionPendingDelete by remember { mutableStateOf(initialNullPromotion) }
    var savingPromotionIds by remember { mutableStateOf(emptySet<String>()) }

    // Delete State
    var showDeleteSheet by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }
    var isDeletingLandmark by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf(initialNullString) }

    val deletionBlockedUploadMessage =
        "Upload blocked: deletion has been requested for this landmark. Additional media cannot be uploaded."

    fun sanitizedErrorMessage(error: Throwable): String? {
        val raw = error.localizedMessage ?: error.message ?: return null
        val lower = raw.lowercase(Locale.US)
        if (lower.contains("<!doctype html") ||
            lower.contains("<html>") ||
            lower.contains("generated by cloudfront") ||
            lower.contains("request blocked")
        ) {
            return null
        }
        return raw
    }

    fun isDeletionBlockedUpload(error: Throwable): Boolean {
        if (error is BusinessLandmarkServiceError.BadStatus && error.code == 409) {
            return error.responseBody.contains("Landmark deletion has been requested. Uploads are blocked.")
        }
        return false
    }

    fun publishPromotionTitles(items: List<BusinessPromotion>) {
        val titles = items.mapNotNull { promo -> promo.name.trim().takeIf { it.isNotEmpty() } }
        onPromotionTitlesChanged(landmark.landmarkId, titles)
    }

    fun scheduleManagementUpdate() {
        managementUpdateJob?.cancel()
        managementErrorMessage = null
        val targetActive = displayedIsActive
        val targetPromo = displayedPromotionEnabled

        managementUpdateJob = coroutineScope.launch {
            delay(600)
            isSavingManagement = true
            try {
                val updated = landmarkService.updateLandmarkSettings(
                    landmarkId = landmark.landmarkId,
                    isActive = targetActive,
                    promotionEnabled = targetPromo
                )
                landmark = updated
                displayedIsActive = updated.isActive ?: targetActive
                displayedPromotionEnabled = updated.promotionEnabled ?: targetPromo
                updated.cleanFrameCount?.let { displayedCleanFrameCount = it }
                onLandmarkUpdated(updated)
            } catch (e: Exception) {
                managementErrorMessage = sanitizedErrorMessage(e)
            } finally {
                isSavingManagement = false
            }
        }
    }

    fun previewLandmarkPopup() {
        VariableContainer.shared.presentNearbyLandmark(
            NearbyLandmark(
                landmarkId = landmark.landmarkId,
                label = landmark.label,
                shortDescription = displayedShortDescription,
                latitude = landmark.latitude ?: 0.0,
                longitude = landmark.longitude ?: 0.0,
                promotionEnabled = displayedPromotionEnabled,
                promotion = landmark.promotion,
                createdBy = landmark.ownerUserId,
                websiteUrl = displayedWebsiteUrl.ifEmpty { null },
                isActive = displayedIsActive,
                isVisibleOnMap = displayedIsActive
            )
        )
    }

    suspend fun uploadSingleUri(uri: Uri, role: BusinessDatasetRole, index: Int, isCameraRecording: Boolean): BusinessMediaUploadCompleteResponse {
        return withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: File(uri.path ?: "").takeIf { it.exists() }?.inputStream()
                ?: throw Exception("Could not load the selected media.")
            val bytes = inputStream.use { it.readBytes() }

            var mimeType = context.contentResolver.getType(uri)
            if (mimeType == null) {
                val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.takeIf { it.isNotEmpty() }
                    ?: File(uri.path ?: "").extension
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "video/mp4"
            }

            val isVideo = isCameraRecording || mimeType.startsWith("video/") ||
                    uri.toString().endsWith(".mp4", true) || uri.toString().endsWith(".mov", true)
            val kind = if (isVideo) BusinessMediaKind.VIDEO else BusinessMediaKind.PHOTO
            val fileExt = if (isVideo) "mp4" else "jpg"

            val cleanedLabel = landmark.label.trim().replace(Regex("[^A-Za-z0-9]"), "_").ifEmpty { landmark.landmarkId }
            val filename = if (isCameraRecording) {
                "${cleanedLabel}_${role.filenameComponent}_camera_${UUID.randomUUID()}.$fileExt"
            } else {
                "${cleanedLabel}_${role.filenameComponent}_${index}_${UUID.randomUUID()}.$fileExt"
            }

            landmarkService.uploadBusinessMedia(
                landmarkId = landmark.landmarkId,
                datasetRole = role,
                mediaKind = kind,
                filename = filename,
                contentType = mimeType,
                data = bytes
            )
        }
    }

    suspend fun uploadPendingPositiveRecording() {
        if (isUploadingMedia || pendingPositiveVideoUris.isEmpty()) return

        BusinessProcessingTracker.markProcessingNewData(landmark)
        isUploadingMedia = true
        activeUploadRole = BusinessDatasetRole.POSITIVE
        uploadStatusMessage = null
        uploadErrorMessage = null
        uploadProgressText = "Preparing recorded video..."

        try {
            var lastSubmissionId = ""
            pendingPositiveVideoUris.forEachIndexed { idx: Int, uri: Uri ->
                uploadProgressText = if (pendingPositiveVideoUris.size > 1) {
                    "Uploading positive clip ${idx + 1} of ${pendingPositiveVideoUris.size}..."
                } else {
                    "Uploading positive recording..."
                }
                val response = uploadSingleUri(uri, BusinessDatasetRole.POSITIVE, idx + 1, isCameraRecording = true)
                lastSubmissionId = response.submissionId
            }

            pendingPositiveVideoUris.forEach { uri ->
                uri.path?.let { path -> runCatching { File(path).delete() } }
            }
            pendingPositiveVideoUris = emptyList()
            onLandmarkUpdated(landmark)

            isUploadingMedia = false
            activeUploadRole = null
            uploadProgressText = null
            uploadStatusMessage = "Positive recording uploaded successfully. Submission: $lastSubmissionId"
        } catch (e: Exception) {
            isUploadingMedia = false
            activeUploadRole = null
            uploadProgressText = null
            uploadErrorMessage = if (isDeletionBlockedUpload(e)) deletionBlockedUploadMessage else sanitizedErrorMessage(e)
        }
    }

    suspend fun uploadPendingNegativeRecording() {
        val uri = pendingNegativeVideoUri ?: return
        if (isUploadingMedia) return

        isUploadingMedia = true
        activeUploadRole = BusinessDatasetRole.HARD_NEGATIVE
        uploadStatusMessage = null
        uploadErrorMessage = null
        uploadProgressText = "Uploading negative recording..."

        try {
            val response = uploadSingleUri(uri, BusinessDatasetRole.HARD_NEGATIVE, 1, isCameraRecording = true)
            uri.path?.let { path -> runCatching { File(path).delete() } }
            pendingNegativeVideoUri = null

            isUploadingMedia = false
            activeUploadRole = null
            uploadProgressText = null
            uploadStatusMessage = "Negative recording uploaded successfully. Submission: ${response.submissionId}"
        } catch (e: Exception) {
            isUploadingMedia = false
            activeUploadRole = null
            uploadProgressText = null
            uploadErrorMessage = if (isDeletionBlockedUpload(e)) deletionBlockedUploadMessage else sanitizedErrorMessage(e)
        }
    }

    suspend fun uploadSelectedGalleryItems(uris: List<Uri>, role: BusinessDatasetRole) {
        if (isUploadingMedia || uris.isEmpty()) return

        isUploadingMedia = true
        activeUploadRole = role
        uploadStatusMessage = null
        uploadErrorMessage = null
        uploadProgressText = "Preparing ${uris.size} item${if (uris.size == 1) "" else "s"}..."

        if (role == BusinessDatasetRole.POSITIVE) {
            BusinessProcessingTracker.markProcessingNewData(landmark)
        }

        var completedCount = 0
        var failedCount = 0
        var overLimitVideoCount = 0
        var deletionBlocked = false
        var lastSubmissionId: String? = null
        val maxAllowedDuration = if (role == BusinessDatasetRole.POSITIVE) maxPositiveVideoDurationSeconds else maxNegativeVideoDurationSeconds

        for ((index, uri) in uris.withIndex()) {
            uploadProgressText = "Checking item ${index + 1} of ${uris.size}..."
            try {
                val mimeType = context.contentResolver.getType(uri)
                val isVideo = mimeType?.startsWith("video/") == true ||
                        uri.toString().endsWith(".mp4", true) || uri.toString().endsWith(".mov", true)
                if (isVideo) {
                    val durationSeconds = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            (durationStr?.toLongOrNull() ?: 0L) / 1000.0
                        } finally {
                            retriever.release()
                        }
                    }
                    if (durationSeconds > maxAllowedDuration) {
                        overLimitVideoCount++
                        failedCount++
                        continue
                    }
                }

                uploadProgressText = "Uploading item ${index + 1} of ${uris.size}..."
                val response = uploadSingleUri(uri, role, index + 1, isCameraRecording = false)
                completedCount++
                lastSubmissionId = response.submissionId
            } catch (e: Exception) {
                Log.e("LookSee_Debug_Upload", "Failed to upload gallery item $index", e)
                failedCount++
                if (isDeletionBlockedUpload(e)) {
                    deletionBlocked = true
                    break
                }
            }
        }

        isUploadingMedia = false
        activeUploadRole = null
        uploadProgressText = null

        if (role == BusinessDatasetRole.POSITIVE && completedCount > 0) {
            BusinessProcessingTracker.markProcessingNewData(landmark)
            onLandmarkUpdated(landmark)
        }

        if (failedCount == 0) {
            val base = when (role) {
                BusinessDatasetRole.POSITIVE -> "$completedCount positive item${if (completedCount == 1) "" else "s"} uploaded successfully."
                BusinessDatasetRole.HARD_NEGATIVE -> "$completedCount negative example${if (completedCount == 1) "" else "s"} uploaded successfully."
            }
            uploadStatusMessage = if (lastSubmissionId != null) "$base Last submission: $lastSubmissionId" else base
            uploadErrorMessage = null
            if (role == BusinessDatasetRole.POSITIVE) selectedPositiveMediaUris = emptyList()
            else selectedNegativeMediaUris = emptyList()
        } else {
            uploadStatusMessage = if (completedCount > 0) "$completedCount item${if (completedCount == 1) "" else "s"} uploaded successfully." else null
            val otherFailureCount = failedCount - overLimitVideoCount - (if (deletionBlocked) 1 else 0)
            val errors = mutableListOf<String>()

            if (deletionBlocked) {
                errors.add(deletionBlockedUploadMessage)
            }
            if (overLimitVideoCount > 0) {
                val maxInt = maxAllowedDuration.toInt()
                errors.add("$overLimitVideoCount video${if (overLimitVideoCount == 1) " was" else "s were"} not uploaded. Gallery videos must be $maxInt seconds or shorter.")
            }
            if (otherFailureCount > 0) {
                errors.add("$otherFailureCount other item${if (otherFailureCount == 1) "" else "s"} failed to upload. Please try again.")
            }
            uploadErrorMessage = errors.joinToString(" ")

            if (completedCount > 0 || overLimitVideoCount > 0) {
                if (role == BusinessDatasetRole.POSITIVE) selectedPositiveMediaUris = emptyList()
                else selectedNegativeMediaUris = emptyList()
            }
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uploadStatusMessage = null
            uploadErrorMessage = null
            if (pickerTargetRole == BusinessDatasetRole.POSITIVE) {
                selectedPositiveMediaUris = uris
            } else {
                selectedNegativeMediaUris = uris
            }
        }
    }

    suspend fun loadPromotions() {
        isLoadingPromotions = true
        promotionErrorMessage = null
        try {
            val response = promotionService.fetchPromotions(landmark.landmarkId)
            promotions = response.items
            publishPromotionTitles(response.items)
        } catch (e: Exception) {
            promotionErrorMessage = sanitizedErrorMessage(e)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { previewLandmarkPopup() },
                        enabled = !isSavingDescription && !isSavingWebsiteUrl && !isSavingManagement && savingPromotionIds.isEmpty()
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = "Preview Popup",
                            tint = primaryColor
                        )
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
            // MARK: - Status Banner
            item {
                val status = landmark.status ?: ""
                val isProcessing = landmark.isProcessing

                val bannerColor: Color
                val bannerIcon: ImageVector
                val bannerTitle: String
                val bannerMessage: String

                when {
                    isProcessingNewData -> {
                        bannerColor = Color(0xFF64D2FF)
                        bannerIcon = Icons.Default.HourglassBottom
                        bannerTitle = "PROCESSING NEW DATA"
                        bannerMessage = "We are uploading and analyzing your new media to calculate your updated frame count."
                    }
                    status == "NEEDS_MORE_MEDIA" -> {
                        bannerColor = Color(0xFFFF453A)
                        bannerIcon = Icons.Default.Warning
                        bannerTitle = landmark.displayStatus.uppercase()
                        bannerMessage = "Not enough video data to train. Please record more media below."
                    }
                    isProcessing || listOf("PREPARING_DATA", "PENDING_TRAINING", "TRAINING_MODEL", "OPTIMIZING_MODEL").contains(status) -> {
                        bannerColor = when (status) {
                            "PREPARING_DATA" -> Color(0xFFFF9F0A)
                            "PENDING_TRAINING" -> Color(0xFFAF52DE)
                            "TRAINING_MODEL" -> Color(0xFFFFD60A)
                            "OPTIMIZING_MODEL" -> Color(0xFF0A84FF)
                            else -> Color(0xFFFF9F0A)
                        }
                        bannerIcon = Icons.Default.HourglassBottom
                        bannerTitle = landmark.displayStatus.uppercase()
                        bannerMessage = "Your landmark is currently in progress of being trained. Training runs daily starting at 6:00 PM EST."
                    }
                    displayedIsActive -> {
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

            // MARK: - Header (Label, Description, Website)
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
                                fontWeight = if (displayedWebsiteUrl.isEmpty()) FontWeight.Medium else FontWeight.SemiBold,
                                color = if (displayedWebsiteUrl.isEmpty()) Color.Gray else Color.White,
                                maxLines = 2
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            websiteUrlErrorMessage = null
                            showEditWebsiteSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor.copy(alpha = 0.1f), contentColor = primaryColor),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (displayedWebsiteUrl.isEmpty()) "Add Website" else "Edit Website", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    websiteUrlErrorMessage?.let { msg ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                            Text(msg, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }
                }
            }

            // MARK: - Training Dataset Card
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TRAINING DATASET", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val processed = displayedCleanFrameCount
                        val required = landmark.requiredFrames ?: 1500

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("TOTAL LABELED FRAMES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                Text(
                                    "$processed",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (processed >= required) Color(0xFF32D74B) else if (processed > 0) Color(0xFFFF9F0A) else Color(0xFFFF453A)
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

            // MARK: - Management
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MANAGEMENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                imageVector = if (displayedIsActive) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = if (displayedIsActive) Color(0xFF32D74B) else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (displayedIsActive) "Active Landmark" else "Inactive Landmark",
                                color = if (displayedIsActive) Color(0xFF32D74B) else Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = displayedIsActive,
                                onCheckedChange = { newVal ->
                                    displayedIsActive = newVal
                                    scheduleManagementUpdate()
                                }
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                imageVector = Icons.Default.LocalOffer,
                                contentDescription = null,
                                tint = if (displayedPromotionEnabled) Color(0xFFFF9F0A) else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (displayedPromotionEnabled) "Promotions Enabled" else "Promotions Disabled",
                                color = if (displayedPromotionEnabled) Color(0xFFFF9F0A) else Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = displayedPromotionEnabled,
                                onCheckedChange = { newVal ->
                                    displayedPromotionEnabled = newVal
                                    scheduleManagementUpdate()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFF9F0A),
                                    checkedTrackColor = Color(0xFFFF9F0A).copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    managementErrorMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }

            // MARK: - Promotions
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
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor, strokeWidth = 2.dp)
                                Text("Loading promotions...", fontSize = 14.sp, color = Color.Gray)
                            }
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
                                            promotionErrorMessage = null
                                            val previousPromotions = promotions
                                            promotions = promotions.map { if (it.id == promo.id) it.copy(enabled = enabled) else it }
                                            try {
                                                val updated = promotionService.updatePromotion(landmark.landmarkId, promo.id, enabled = enabled)
                                                promotions = promotions.map { if (it.id == promo.id) updated else it }
                                            } catch (e: Exception) {
                                                promotions = previousPromotions
                                                promotionErrorMessage = sanitizedErrorMessage(e)
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

                    Text(
                        text = if (displayedPromotionEnabled) {
                            "Promotions can be shown for this landmark when enabled and within their date range."
                        } else {
                            "Promotions are currently disabled for this landmark. You can still create and edit records here."
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    promotionErrorMessage?.let { msg ->
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                            Text(msg, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }
                }
            }

            // MARK: - Location
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LOCATION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        DetailDataRow(
                            label = "Latitude",
                            value = landmark.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "Not available"
                        )
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.Gray.copy(alpha = 0.2f))
                        DetailDataRow(
                            label = "Longitude",
                            value = landmark.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "Not available"
                        )
                    }
                }
            }

            // MARK: - Legacy Promotion
            val legacyPromotion = landmark.promotion?.trim()
            if (!legacyPromotion.isNullOrEmpty()) {
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

            // MARK: - Media Uploads
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

                        // Positive Camera Button + Retry Controls
                        MediaUploadButton(
                            title = "Record Positive Video",
                            subtitle = if (pendingPositiveVideoUris.isEmpty())
                                "Record new views of this landmark with the camera."
                            else
                                "A recording is ready to retry.",
                            icon = Icons.Default.Videocam,
                            color = primaryColor,
                            enabled = !isUploadingMedia,
                            onClick = {
                                pendingPositiveVideoUris = emptyList()
                                uploadStatusMessage = null
                                uploadErrorMessage = null
                                showPositiveCamera = true
                            }
                        )

                        if (pendingPositiveVideoUris.isNotEmpty() && !isUploadingMedia) {
                            RecordedVideoRetryControls(
                                retryTitle = "Retry Positive Upload",
                                retryColor = primaryColor,
                                onRetry = { coroutineScope.launch { uploadPendingPositiveRecording() } },
                                onClear = {
                                    pendingPositiveVideoUris.forEach { uri -> uri.path?.let { runCatching { File(it).delete() } } }
                                    pendingPositiveVideoUris = emptyList()
                                    uploadErrorMessage = null
                                }
                            )
                        }

                        // Positive Gallery Picker Button + Submit/Clear Controls
                        MediaUploadButton(
                            title = "Choose Positive Media",
                            subtitle = if (selectedPositiveMediaUris.isEmpty())
                                "Select photos or videos of this landmark."
                            else
                                "${selectedPositiveMediaUris.size} item${if (selectedPositiveMediaUris.size == 1) "" else "s"} selected. Tap Submit when ready.",
                            icon = Icons.Default.AddCircle,
                            color = primaryColor,
                            enabled = !isUploadingMedia,
                            onClick = {
                                pickerTargetRole = BusinessDatasetRole.POSITIVE
                                mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        )

                        if (selectedPositiveMediaUris.isNotEmpty()) {
                            GallerySelectionSubmitControls(
                                count = selectedPositiveMediaUris.size,
                                roleLabel = "positive",
                                submitColor = primaryColor,
                                enabled = !isUploadingMedia,
                                onSubmit = {
                                    coroutineScope.launch {
                                        uploadSelectedGalleryItems(selectedPositiveMediaUris, BusinessDatasetRole.POSITIVE)
                                    }
                                },
                                onClear = { selectedPositiveMediaUris = emptyList() }
                            )
                        }

                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                        // Negative Camera Button + Retry Controls
                        MediaUploadButton(
                            title = "Record Negative Video",
                            subtitle = if (pendingNegativeVideoUri == null)
                                "Record nearby objects without including the landmark."
                            else
                                "A negative recording is ready to retry.",
                            icon = Icons.Default.Videocam,
                            color = Color(0xFFFF9F0A),
                            enabled = !isUploadingMedia,
                            onClick = {
                                pendingNegativeVideoUri?.path?.let { runCatching { File(it).delete() } }
                                pendingNegativeVideoUri = null
                                uploadStatusMessage = null
                                uploadErrorMessage = null
                                showNegativeCamera = true
                            }
                        )

                        if (pendingNegativeVideoUri != null && !isUploadingMedia) {
                            RecordedVideoRetryControls(
                                retryTitle = "Retry Negative Upload",
                                retryColor = Color(0xFFFF9F0A),
                                onRetry = { coroutineScope.launch { uploadPendingNegativeRecording() } },
                                onClear = {
                                    pendingNegativeVideoUri?.path?.let { runCatching { File(it).delete() } }
                                    pendingNegativeVideoUri = null
                                    uploadErrorMessage = null
                                }
                            )
                        }

                        // Negative Gallery Picker Button + Submit/Clear Controls
                        MediaUploadButton(
                            title = "Choose Negative Examples",
                            subtitle = if (selectedNegativeMediaUris.isEmpty())
                                "Select nearby objects that are not this landmark."
                            else
                                "${selectedNegativeMediaUris.size} item${if (selectedNegativeMediaUris.size == 1) "" else "s"} selected. Tap Submit when ready.",
                            icon = Icons.Default.RemoveCircle,
                            color = Color(0xFFFF9F0A),
                            enabled = !isUploadingMedia,
                            onClick = {
                                pickerTargetRole = BusinessDatasetRole.HARD_NEGATIVE
                                mediaPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        )

                        if (selectedNegativeMediaUris.isNotEmpty()) {
                            GallerySelectionSubmitControls(
                                count = selectedNegativeMediaUris.size,
                                roleLabel = "negative",
                                submitColor = Color(0xFFFF9F0A),
                                enabled = !isUploadingMedia,
                                onSubmit = {
                                    coroutineScope.launch {
                                        uploadSelectedGalleryItems(selectedNegativeMediaUris, BusinessDatasetRole.HARD_NEGATIVE)
                                    }
                                },
                                onClear = { selectedNegativeMediaUris = emptyList() }
                            )
                        }

                        // Upload Status Area
                        if (isUploadingMedia) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor, strokeWidth = 2.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val roleTitle = activeUploadRole?.let { "Uploading ${it.displayName.lowercase(Locale.US)}..." } ?: "Uploading media..."
                                    Text(roleTitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    uploadProgressText?.let {
                                        Text(it, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }

                        uploadStatusMessage?.let { msg ->
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF32D74B), modifier = Modifier.size(16.dp))
                                Text(msg, color = Color(0xFF32D74B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        uploadErrorMessage?.let { msg ->
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                                Text(msg, color = Color(0xFFFF9F0A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        "Record new video with the camera or choose existing photos and videos from your library. Gallery videos must be ${maxPositiveVideoDurationSeconds.toInt()} seconds or shorter (${maxNegativeVideoDurationSeconds.toInt()}s for negative examples).",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            // MARK: - Danger Zone
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DANGER ZONE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Button(
                            onClick = {
                                deleteConfirmationText = ""
                                deleteErrorMessage = null
                                showDeleteSheet = true
                            },
                            enabled = !isDeletingLandmark,
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

            // MARK: - Identifiers
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

    // MARK: - Sheets & Dialogs

    if (showEditDescriptionSheet) {
        var draft by remember { mutableStateOf(displayedShortDescription) }
        var error by remember { mutableStateOf(initialNullString) }

        ModalBottomSheet(onDismissRequest = { if (!isSavingDescription) showEditDescriptionSheet = false }, containerColor = secondaryGrouped) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("SHORT DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !isSavingDescription,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Black,
                        focusedContainerColor = Color.Black,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("This description is shown to users when LookSee identifies this landmark.", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val cleaned = draft.trim()
                        if (cleaned.isEmpty()) {
                            error = "Short description cannot be empty."
                            return@Button
                        }
                        coroutineScope.launch {
                            isSavingDescription = true
                            error = null
                            try {
                                val updated = landmarkService.updateShortDescription(landmark.landmarkId, cleaned)
                                landmark = updated
                                displayedShortDescription = updated.shortDescription?.trim() ?: cleaned
                                updated.cleanFrameCount?.let { displayedCleanFrameCount = it }
                                onLandmarkUpdated(updated)
                                showEditDescriptionSheet = false
                            } catch (e: Exception) {
                                error = sanitizedErrorMessage(e)
                            } finally {
                                isSavingDescription = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isSavingDescription && draft.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (isSavingDescription) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                        Text(it, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showEditWebsiteSheet) {
        var draft by remember { mutableStateOf(displayedWebsiteUrl) }
        var error by remember { mutableStateOf(initialNullString) }

        ModalBottomSheet(onDismissRequest = { if (!isSavingWebsiteUrl) showEditWebsiteSheet = false }, containerColor = secondaryGrouped) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("WEBSITE URL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !isSavingWebsiteUrl,
                    placeholder = { Text("example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Black,
                        focusedContainerColor = Color.Black,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Users will be able to open this website from the landmark popup. You can enter example.com or a full https:// URL. Leave it blank to clear the website.",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val cleaned = draft.trim()
                        coroutineScope.launch {
                            isSavingWebsiteUrl = true
                            error = null
                            websiteUrlErrorMessage = null
                            try {
                                val updated = landmarkService.updateWebsiteUrl(landmark.landmarkId, cleaned)
                                landmark = updated
                                displayedWebsiteUrl = updated.websiteUrl?.trim() ?: cleaned
                                updated.cleanFrameCount?.let { displayedCleanFrameCount = it }
                                onLandmarkUpdated(updated)
                                showEditWebsiteSheet = false
                            } catch (e: Exception) {
                                val msg = sanitizedErrorMessage(e)
                                error = msg
                                websiteUrlErrorMessage = msg
                            } finally {
                                isSavingWebsiteUrl = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isSavingWebsiteUrl,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (isSavingWebsiteUrl) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                        Text(it, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showDeleteSheet) {
        val isDeleteConfirmationValid = deleteConfirmationText.trim() == "delete landmark"
        ModalBottomSheet(onDismissRequest = { if (!isDeletingLandmark) showDeleteSheet = false }, containerColor = secondaryGrouped) {
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
                        enabled = !isDeletingLandmark,
                        placeholder = { Text("delete landmark") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("To confirm, type exactly: delete landmark", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isDeletingLandmark = true
                            deleteErrorMessage = null
                            try {
                                landmarkService.deleteLandmark(landmark.landmarkId, deleteConfirmationText.trim())
                                isDeletingLandmark = false
                                showDeleteSheet = false
                                onLandmarkDeleted(landmark.landmarkId)
                                onDismiss()
                            } catch (e: Exception) {
                                deleteErrorMessage = sanitizedErrorMessage(e)
                                isDeletingLandmark = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = isDeleteConfirmationValid && !isDeletingLandmark,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    if (isDeletingLandmark) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Confirm Delete Landmark", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                deleteErrorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(16.dp))
                        Text(it, color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    val currentEditorContext = promotionEditorContext
    if (showPromotionEditor && currentEditorContext != null) {
        Dialog(
            onDismissRequest = { showPromotionEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BusinessPromotionEditor(
                landmark = landmark,
                context = currentEditorContext,
                onSaved = {
                    showPromotionEditor = false
                    coroutineScope.launch { loadPromotions() }
                },
                onDismiss = { showPromotionEditor = false }
            )
        }
    }

    val currentPendingDelete = promotionPendingDelete
    if (currentPendingDelete != null) {
        AlertDialog(
            onDismissRequest = { promotionPendingDelete = null },
            title = { Text("Delete Promotion?") },
            text = { Text("This promotion will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        savingPromotionIds = savingPromotionIds + currentPendingDelete.id
                        promotionErrorMessage = null
                        try {
                            promotionService.deletePromotion(landmark.landmarkId, currentPendingDelete.id)
                            promotions = promotions.filterNot { it.id == currentPendingDelete.id }
                            publishPromotionTitles(promotions)
                        } catch (e: Exception) {
                            promotionErrorMessage = sanitizedErrorMessage(e)
                        } finally {
                            savingPromotionIds = savingPromotionIds - currentPendingDelete.id
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
                    pendingPositiveVideoUris = listOf(uri)
                    coroutineScope.launch { uploadPendingPositiveRecording() }
                },
                onDismiss = { showPositiveCamera = false }
            )
        }
    }

    if (showNegativeCamera) {
        Dialog(onDismissRequest = { showNegativeCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BusinessNegativeVideoCameraView(
                onDone = { video ->
                    showNegativeCamera = false
                    pendingNegativeVideoUri = Uri.fromFile(video.file)
                    coroutineScope.launch { uploadPendingNegativeRecording() }
                },
                onDismiss = { showNegativeCamera = false }
            )
        }
    }
}

private fun promotionDateSummary(promotion: BusinessPromotion): String {
    val start = promotion.startDate.trim()
    val end = promotion.endDate.trim()
    if (start.isEmpty() && end.isEmpty()) {
        return if (promotion.enabled) "Active" else "Inactive"
    }
    val dateText = when {
        start.isNotEmpty() && end.isNotEmpty() -> "$start to $end"
        start.isNotEmpty() -> "Starts $start"
        else -> "Ends $end"
    }
    return if (promotion.enabled) "Active • $dateText" else "Inactive • $dateText"
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    promotion.name.ifEmpty { "Untitled Promotion" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                if (promotion.description.trim().isNotEmpty()) {
                    Text(promotion.description.trim(), color = Color.Gray, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                val statusColor = if (promotion.enabled) Color(0xFFFF9F0A) else Color.Gray
                Text(
                    text = promotionDateSummary(promotion),
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Switch(
                checked = promotion.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !isSaving,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFFF9F0A),
                    checkedTrackColor = Color(0xFFFF9F0A).copy(alpha = 0.5f)
                )
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onEdit,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Edit", fontWeight = FontWeight.Bold, color = Color.White) }
            Button(
                onClick = onDelete,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Delete", fontWeight = FontWeight.Bold, color = Color.Red) }
        }
    }
}

@Composable
private fun RecordedVideoRetryControls(
    retryTitle: String,
    retryColor: Color,
    onRetry: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onRetry,
            modifier = Modifier.weight(1f).height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = retryColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(retryTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Button(
            onClick = onClear,
            modifier = Modifier.height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Clear", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GallerySelectionSubmitControls(
    count: Int,
    roleLabel: String,
    submitColor: Color,
    enabled: Boolean,
    onSubmit: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "$count $roleLabel item${if (count == 1) "" else "s"} selected",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = submitColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Submit", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Clear", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) color else color.copy(alpha = 0.4f), modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
    }
}