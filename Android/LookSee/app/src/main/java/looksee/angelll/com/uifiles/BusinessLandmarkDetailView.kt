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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BusinessLandmarkDetailView(
    landmark: BusinessLandmark,
    onLandmarkUpdated: (BusinessLandmark) -> Unit,
    onLandmarkDeleted: (String) -> Unit,
    onPromotionTitlesChanged: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit // Helper to handle NavigationLinks like BusinessMediaHistoryView
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Colors moved inside to prevent collisions
    val PrimaryBlue = Color(0xFF387DFF)
    val SecondaryGrouped = Color(0xFF1C1C1E)

    // Description State
    var displayedShortDescription by remember { mutableStateOf(landmark.shortDescription?.trim() ?: "") }
    var draftShortDescription by remember { mutableStateOf(displayedShortDescription) }
    var isEditingDescription by remember { mutableStateOf(false) }
    var isSavingDescription by remember { mutableStateOf(false) }
    var saveErrorMessage by remember { mutableStateOf<String?>(null) }

    // Website State
    var displayedWebsiteUrl by remember { mutableStateOf(landmark.websiteUrl?.trim() ?: "") }
    var draftWebsiteUrl by remember { mutableStateOf(displayedWebsiteUrl) }
    var isEditingWebsiteUrl by remember { mutableStateOf(false) }
    var isSavingWebsiteUrl by remember { mutableStateOf(false) }
    var websiteUrlErrorMessage by remember { mutableStateOf<String?>(null) }

    // Management State
    var displayedIsActive by remember { mutableStateOf(landmark.isActive ?: true) }
    var displayedPromotionEnabled by remember { mutableStateOf(landmark.promotionEnabled ?: false) }
    var isSavingManagement by remember { mutableStateOf(false) }
    var managementErrorMessage by remember { mutableStateOf<String?>(null) }

    // Danger Zone State
    var isShowingDeleteLandmarkSheet by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }
    var isDeletingLandmark by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    val isDeleteConfirmationValid = deleteConfirmationText.trim() == "delete landmark"

    // Promotions State
    var promotions by remember { mutableStateOf<List<BusinessPromotion>>(emptyList()) }
    var isLoadingPromotions by remember { mutableStateOf(false) }
    var promotionErrorMessage by remember { mutableStateOf<String?>(null) }
    var showPromotionEditor by remember { mutableStateOf(false) }
    var promotionPendingDelete by remember { mutableStateOf<BusinessPromotion?>(null) }
    var savingPromotionIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Media Uploads State
    var showPositiveCamera by remember { mutableStateOf(false) }
    var showNegativeCamera by remember { mutableStateOf(false) }
    var selectedPositiveMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedNegativeMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // 🚀 YOUR FIX: Holding single merged URL/Uri from the camera directly
    var pendingPositiveVideoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }

    var isUploadingMedia by remember { mutableStateOf(false) }
    var uploadStatusMessage by remember { mutableStateOf<String?>(null) }
    var uploadErrorMessage by remember { mutableStateOf<String?>(null) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }

    // Photo Pickers
    val positivePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) selectedPositiveMediaItems = uris
    }

    val negativePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) selectedNegativeMediaItems = uris
    }

    // 🚀 BOSS'S FIX: Constants for the gallery check
    val maximumGalleryVideoDuration = 90L

    // Services (Stubs - connect to your real API logic)
    val service = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    fun displayDescription(): String {
        val cleaned = displayedShortDescription.trim()
        return if (cleaned.isEmpty()) "No description available." else cleaned
    }

    // Load Data
    LaunchedEffect(Unit) {
        isLoadingPromotions = true
        try {
            val items = promotionService.fetchPromotions(landmark.landmarkId)
            promotions = items
            onPromotionTitlesChanged(landmark.landmarkId, items.map { it.name }.filter { it.isNotEmpty() })
        } catch (e: Exception) {
            promotionErrorMessage = e.localizedMessage
        } finally {
            isLoadingPromotions = false
        }
    }

    // Upload Logic (Gallery)
    fun uploadSelectedMediaItems(items: List<Uri>, isPositive: Boolean) {
        if (isUploadingMedia || items.isEmpty()) return
        isUploadingMedia = true
        uploadStatusMessage = null
        uploadErrorMessage = null
        uploadProgressText = "Preparing ${items.size} item(s)..."

        coroutineScope.launch {
            var completedCount = 0
            var failedCount = 0
            var overLimitVideoCount = 0

            for ((index, uri) in items.withIndex()) {
                uploadProgressText = "Uploading item ${index + 1} of ${items.size}..."
                try {
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val isVideo = mimeType.startsWith("video/")

                    // 🚀 BOSS'S FIX: Video duration validation
                    if (isVideo) {
                        uploadProgressText = "Checking video ${index + 1} of ${items.size}..."
                        val durationSeconds = getVideoDurationInSeconds(context, uri)
                        if (durationSeconds > maximumGalleryVideoDuration) {
                            throw MediaSelectionError.VideoExceedsMaximumDuration(durationSeconds.toDouble())
                        }
                    }

                    uploadProgressText = "Uploading item ${index + 1} of ${items.size}..."
                    service.uploadBusinessMedia(landmark.landmarkId, uri)
                    completedCount++

                } catch (e: MediaSelectionError.VideoExceedsMaximumDuration) {
                    failedCount++
                    overLimitVideoCount++
                } catch (e: Exception) {
                    failedCount++
                }
            }

            isUploadingMedia = false
            uploadProgressText = null

            if (failedCount == 0) {
                uploadStatusMessage = "$completedCount item(s) uploaded successfully."
                if (isPositive) selectedPositiveMediaItems = emptyList() else selectedNegativeMediaItems = emptyList()
            } else {
                val otherFailureCount = failedCount - overLimitVideoCount
                val errorMessages = mutableListOf<String>()

                if (overLimitVideoCount > 0) {
                    errorMessages.add("$overLimitVideoCount video(s) were not uploaded. Gallery videos must be 90 seconds or shorter.")
                }
                if (otherFailureCount > 0) {
                    errorMessages.add("$otherFailureCount other item(s) failed to upload. Please try again.")
                }

                uploadErrorMessage = errorMessages.joinToString(" ")

                if (completedCount > 0 || overLimitVideoCount > 0) {
                    if (isPositive) selectedPositiveMediaItems = emptyList() else selectedNegativeMediaItems = emptyList()
                }
            }
        }
    }

    // 🚀 YOUR FIX: Upload logic adapted for single URL returned by the new Camera
    fun uploadPendingPositiveRecording() {
        if (isUploadingMedia || pendingPositiveVideoUris.isEmpty()) return
        val uploadUri = pendingPositiveVideoUris.firstOrNull() ?: return

        isUploadingMedia = true
        uploadProgressText = "Uploading positive recording..."
        uploadStatusMessage = null
        uploadErrorMessage = null

        coroutineScope.launch {
            try {
                service.uploadBusinessMedia(landmark.landmarkId, uploadUri)

                pendingPositiveVideoUris = emptyList()
                isUploadingMedia = false
                uploadStatusMessage = "Positive recording uploaded successfully."
            } catch (e: Exception) {
                isUploadingMedia = false
                uploadErrorMessage = e.localizedMessage
            }
        }
    }

    fun uploadPendingNegativeRecording() {
        if (isUploadingMedia || pendingNegativeVideo == null) return

        isUploadingMedia = true
        uploadProgressText = "Uploading negative recording..."
        uploadStatusMessage = null
        uploadErrorMessage = null

        coroutineScope.launch {
            try {
                service.uploadBusinessMedia(landmark.landmarkId, pendingNegativeVideo!!.fileURL)

                pendingNegativeVideo = null
                isUploadingMedia = false
                uploadStatusMessage = "Negative recording uploaded successfully."
            } catch (e: Exception) {
                isUploadingMedia = false
                uploadErrorMessage = e.localizedMessage
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { focusManager.clearFocus() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Toolbar (Back Button)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = PrimaryBlue)
                }
            }

            // MARK: - Header
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = landmark.label.ifEmpty { "Untitled Landmark" },
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                Text(displayDescription(), fontSize = 16.sp, color = Color.Gray)

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        draftShortDescription = displayedShortDescription
                        saveErrorMessage = null
                        isEditingDescription = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue.copy(alpha = 0.1f)),
                    shape = CircleShape
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                        Text("Edit Description", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.DarkGray)

                Surface(
                    color = SecondaryGrouped,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("WEBSITE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                if (displayedWebsiteUrl.trim().isEmpty()) {
                                    Text("No website added yet.", fontSize = 14.sp, color = Color.Gray)
                                } else {
                                    Text(displayedWebsiteUrl, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                draftWebsiteUrl = displayedWebsiteUrl
                                websiteUrlErrorMessage = null
                                isEditingWebsiteUrl = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue.copy(alpha = 0.1f)),
                            shape = CircleShape
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AddLink, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                                Text(if (displayedWebsiteUrl.trim().isEmpty()) "Add Website" else "Edit Website", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // MARK: - Media Uploads
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MEDIA UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))

                Surface(color = SecondaryGrouped, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                        // History Navigation Link
                        Row(modifier = Modifier.clickable { onNavigate("BusinessMediaHistoryView") }) {
                            Text("Media History", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                        }
                        HorizontalDivider(color = Color.DarkGray)

                        // Cameras & Pickers (Positive)
                        UploadRow(
                            title = "Record Positive Video",
                            subtitle = if(pendingPositiveVideoUris.isEmpty()) "Record new views of this landmark." else "A recording is ready to upload.",
                            icon = Icons.Default.Videocam,
                            color = PrimaryBlue
                        ) { showPositiveCamera = true }

                        if (pendingPositiveVideoUris.isNotEmpty() && !isUploadingMedia) {
                            Button(
                                onClick = { uploadPendingPositiveRecording() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) { Text("Upload Recorded Positive Video", fontWeight = FontWeight.Bold) }
                        }

                        UploadRow(
                            title = "Choose Positive Media",
                            subtitle = if(selectedPositiveMediaItems.isEmpty()) "Select photos or videos from gallery." else "${selectedPositiveMediaItems.size} items selected.",
                            icon = Icons.Default.PhotoLibrary,
                            color = PrimaryBlue
                        ) { positivePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

                        if (selectedPositiveMediaItems.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { uploadSelectedMediaItems(selectedPositiveMediaItems, true) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) { Text("Submit") }
                                Button(onClick = { selectedPositiveMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f), contentColor = Color.Red)) { Text("Clear") }
                            }
                        }

                        HorizontalDivider(color = Color.DarkGray)

                        // Cameras & Pickers (Negative)
                        UploadRow(
                            title = "Record Negative Video",
                            subtitle = if(pendingNegativeVideo == null) "Record nearby objects without including the landmark." else "A negative recording is ready to upload.",
                            icon = Icons.Default.VideocamOff,
                            color = Color(0xFFFFA500)
                        ) { showNegativeCamera = true }

                        if (pendingNegativeVideo != null && !isUploadingMedia) {
                            Button(
                                onClick = { uploadPendingNegativeRecording() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))
                            ) { Text("Upload Recorded Negative Video", fontWeight = FontWeight.Bold) }
                        }

                        UploadRow(
                            title = "Choose Negative Examples",
                            subtitle = if(selectedNegativeMediaItems.isEmpty()) "Select photos or videos from gallery." else "${selectedNegativeMediaItems.size} items selected.",
                            icon = Icons.Default.PhotoLibrary,
                            color = Color(0xFFFFA500)
                        ) { negativePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

                        if (selectedNegativeMediaItems.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { uploadSelectedMediaItems(selectedNegativeMediaItems, false) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))) { Text("Submit") }
                                Button(onClick = { selectedNegativeMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f), contentColor = Color.Red)) { Text("Clear") }
                            }
                        }

                        // Upload Status Area
                        if (isUploadingMedia) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(24.dp))
                                Text(uploadProgressText ?: "Uploading...", color = Color.White)
                            }
                        }
                        if (uploadStatusMessage != null) Text(uploadStatusMessage!!, color = Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (uploadErrorMessage != null) Text(uploadErrorMessage!!, color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // MARK: - Danger Zone
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DANGER ZONE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        deleteConfirmationText = ""
                        deleteErrorMessage = null
                        isShowingDeleteLandmarkSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                        Text("Delete Landmark", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    "Deleting a landmark removes it from your account and starts backend cleanup. This cannot be undone.",
                    fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    // Modal Dialogs (Translations for Sheets)

    if (isEditingDescription) {
        Dialog(onDismissRequest = { if (!isSavingDescription) isEditingDescription = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SecondaryGrouped) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Edit Description", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    OutlinedTextField(
                        value = draftShortDescription,
                        onValueChange = { draftShortDescription = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isEditingDescription = false }) { Text("Cancel", color = Color.Gray) }
                        Button(onClick = {
                            isSavingDescription = true
                            displayedShortDescription = draftShortDescription
                            isSavingDescription = false
                            isEditingDescription = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) { Text("Save") }
                    }
                }
            }
        }
    }

    if (isEditingWebsiteUrl) {
        Dialog(onDismissRequest = { if (!isSavingWebsiteUrl) isEditingWebsiteUrl = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SecondaryGrouped) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Edit Website", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    OutlinedTextField(
                        value = draftWebsiteUrl,
                        onValueChange = { draftWebsiteUrl = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isEditingWebsiteUrl = false }) { Text("Cancel", color = Color.Gray) }
                        Button(onClick = {
                            isSavingWebsiteUrl = true
                            displayedWebsiteUrl = draftWebsiteUrl
                            isSavingWebsiteUrl = false
                            isEditingWebsiteUrl = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) { Text("Save") }
                    }
                }
            }
        }
    }

    if (isShowingDeleteLandmarkSheet) {
        Dialog(onDismissRequest = { if (!isDeletingLandmark) isShowingDeleteLandmarkSheet = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = SecondaryGrouped) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Confirm Deletion", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Type exactly: delete landmark", color = Color.Gray, fontSize = 14.sp)
                    OutlinedTextField(
                        value = deleteConfirmationText,
                        onValueChange = { deleteConfirmationText = it.lowercase() },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isShowingDeleteLandmarkSheet = false }) { Text("Cancel", color = Color.Gray) }
                        Button(
                            onClick = {
                                isDeletingLandmark = true
                                coroutineScope.launch {
                                    onLandmarkDeleted(landmark.landmarkId)
                                    isDeletingLandmark = false
                                    isShowingDeleteLandmarkSheet = false
                                    onDismiss()
                                }
                            },
                            enabled = isDeleteConfirmationValid,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }

    // 🚀 YOUR FIX: Native Full Screen Camera Triggers
    if (showPositiveCamera) {
        PositiveVideoCameraView(
            isActive = true,
            isNavVisible = mutableStateOf(false),
            completionButtonTitle = "Use Recorded Videos",
            onDone = { urls ->
                pendingPositiveVideoUris = urls
                coroutineScope.launch { uploadPendingPositiveRecording() }
            },
            onCancel = { showPositiveCamera = false }
        )
    }

    if (showNegativeCamera) {
        NegativeVideoCameraView(
            onDone = { video ->
                pendingNegativeVideo = video
                coroutineScope.launch { uploadPendingNegativeRecording() }
            },
            onDismiss = { showNegativeCamera = false }
        )
    }
}

// Sub-components
@Composable
fun UploadRow(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.DarkGray)
    }
}

// Retrieves the exact duration of a video from the gallery using its Uri.
fun getVideoDurationInSeconds(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        (timeStr?.toLong() ?: 0L) / 1000L
    } catch (e: Exception) {
        0L // Fallback if format is unreadable
    } finally {
        retriever.release()
    }
}