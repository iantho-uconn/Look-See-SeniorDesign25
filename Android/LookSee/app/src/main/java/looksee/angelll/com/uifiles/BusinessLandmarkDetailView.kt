package looksee.angelll.com.uifiles

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessDatasetRole
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.models.BusinessMediaKind
import looksee.angelll.com.models.BusinessPromotion
import looksee.angelll.com.models.BusinessPromotionEditorContext
import looksee.angelll.com.services.BusinessLandmarkService
import looksee.angelll.com.services.BusinessPromotionService
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarkDetailView(
    landmark: BusinessLandmark,
    onLandmarkUpdated: (BusinessLandmark) -> Unit = {},
    onLandmarkDeleted: (String) -> Unit = {},
    onPromotionTitlesChanged: (String, List<String>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val primaryColor = Color(0xFF387DFF)

    // State Mapping
    var displayedShortDescription by remember { mutableStateOf(landmark.shortDescription?.trim() ?: "") }
    var draftShortDescription by remember { mutableStateOf(displayedShortDescription) }
    var isEditingDescription by remember { mutableStateOf(false) }
    var isSavingDescription by remember { mutableStateOf(false) }
    var saveErrorMessage by remember { mutableStateOf<String?>(null) }

    var displayedIsActive by remember { mutableStateOf(landmark.isActive ?: true) }
    var displayedPromotionEnabled by remember { mutableStateOf(landmark.promotionEnabled ?: false) }
    var isSavingManagement by remember { mutableStateOf(false) }
    var managementErrorMessage by remember { mutableStateOf<String?>(null) }

    var promotions by remember { mutableStateOf<List<BusinessPromotion>>(emptyList()) }
    var isLoadingPromotions by remember { mutableStateOf(false) }
    var promotionErrorMessage by remember { mutableStateOf<String?>(null) }
    var promotionEditorContext by remember { mutableStateOf<BusinessPromotionEditorContext?>(null) }
    var promotionPendingDelete by remember { mutableStateOf<BusinessPromotion?>(null) }
    var savingPromotionIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isShowingDeleteLandmarkSheet by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }
    var isDeletingLandmark by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }

    // Media Upload State
    var selectedPositiveMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedNegativeMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isUploadingMedia by remember { mutableStateOf(false) }
    var activeUploadRole by remember { mutableStateOf<BusinessDatasetRole?>(null) }
    var uploadStatusMessage by remember { mutableStateOf<String?>(null) }
    var uploadErrorMessage by remember { mutableStateOf<String?>(null) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }

    var displayedWebsiteUrl by remember { mutableStateOf(landmark.websiteUrl?.trim() ?: "") }
    var draftWebsiteUrl by remember { mutableStateOf(displayedWebsiteUrl) }
    var isEditingWebsiteUrl by remember { mutableStateOf(false) }
    var isSavingWebsiteUrl by remember { mutableStateOf(false) }
    var websiteUrlErrorMessage by remember { mutableStateOf<String?>(null) }

    val service = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    // Pickers
    val positivePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        selectedPositiveMediaItems = uris
    }
    val negativePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        selectedNegativeMediaItems = uris
    }

    // Extracted Methods
    val publishPromotionTitles: () -> Unit = {
        val titles = promotions.map { it.name.trim() }.filter { it.isNotEmpty() }
        onPromotionTitlesChanged(landmark.landmarkId, titles)
    }

    val loadPromotions: () -> Unit = {
        isLoadingPromotions = true
        promotionErrorMessage = null
        coroutineScope.launch {
            try {
                val response = promotionService.fetchPromotions(landmark.landmarkId)
                promotions = response.items
                publishPromotionTitles()
            } catch (e: Exception) {
                promotionErrorMessage = e.localizedMessage
            } finally {
                isLoadingPromotions = false
            }
        }
    }

    LaunchedEffect(Unit) { loadPromotions() }

    val updateManagementSetting: (Boolean?, Boolean?) -> Unit = { isActive, promoEnabled ->
        if (!isSavingManagement) {
            val prevActive = displayedIsActive
            val prevPromo = displayedPromotionEnabled
            if (isActive != null) displayedIsActive = isActive
            if (promoEnabled != null) displayedPromotionEnabled = promoEnabled
            isSavingManagement = true
            managementErrorMessage = null

            coroutineScope.launch {
                try {
                    val updated = service.updateLandmarkSettings(landmark.landmarkId, isActive, promoEnabled)
                    displayedIsActive = updated.isActive ?: displayedIsActive
                    displayedPromotionEnabled = updated.promotionEnabled ?: displayedPromotionEnabled
                    onLandmarkUpdated(updated)
                } catch (e: Exception) {
                    displayedIsActive = prevActive
                    displayedPromotionEnabled = prevPromo
                    managementErrorMessage = e.localizedMessage
                } finally {
                    isSavingManagement = false
                }
            }
        }
    }

    val uploadMedia: (List<Uri>, BusinessDatasetRole) -> Unit = { uris, role ->
        if (!isUploadingMedia && uris.isNotEmpty()) {
            isUploadingMedia = true
            activeUploadRole = role
            uploadStatusMessage = null
            uploadErrorMessage = null
            uploadProgressText = "Preparing ${uris.size} item${if (uris.size == 1) "" else "s"}..."

            coroutineScope.launch {
                var completedCount = 0
                var failedCount = 0
                var lastSubmissionId: String? = null

                for ((index, uri) in uris.withIndex()) {
                    uploadProgressText = "Uploading item ${index + 1} of ${uris.size}..."
                    try {
                        val resolver = context.contentResolver
                        val bytes = resolver.openInputStream(uri)?.readBytes() ?: throw Exception("Could not load media")
                        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                        val isVideo = mimeType.startsWith("video/")
                        val mediaKind = if (isVideo) BusinessMediaKind.VIDEO else BusinessMediaKind.PHOTO
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: (if (isVideo) "mp4" else "jpg")

                        val cleanedLabel = landmark.label.trim().replace(" ", "_").replace("/", "_")
                        val labelComponent = cleanedLabel.ifEmpty { landmark.landmarkId }
                        val filename = "${labelComponent}_${role.filenameComponent}_${index + 1}_${UUID.randomUUID()}.$extension"

                        val response = service.uploadBusinessMedia(
                            landmarkId = landmark.landmarkId,
                            datasetRole = role,
                            mediaKind = mediaKind,
                            filename = filename,
                            contentType = mimeType,
                            data = bytes
                        )
                        completedCount++
                        lastSubmissionId = response.submissionId
                    } catch (e: Exception) {
                        failedCount++
                    }
                }

                isUploadingMedia = false
                activeUploadRole = null
                uploadProgressText = null

                if (failedCount == 0) {
                    val baseMsg = if (role == BusinessDatasetRole.POSITIVE) "$completedCount positive item(s) uploaded successfully." else "$completedCount negative example(s) uploaded successfully."
                    uploadStatusMessage = if (lastSubmissionId != null) "$baseMsg Last submission: $lastSubmissionId" else baseMsg
                    if (role == BusinessDatasetRole.POSITIVE) selectedPositiveMediaItems = emptyList() else selectedNegativeMediaItems = emptyList()
                } else {
                    uploadStatusMessage = if (completedCount > 0) "$completedCount item(s) uploaded successfully." else null
                    uploadErrorMessage = "$failedCount item(s) failed to upload. Please try again."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Landmark Details") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(displayedShortDescription.ifEmpty { "No description available." }, fontSize = 16.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .background(primaryColor.copy(alpha = 0.1f), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draftShortDescription = displayedShortDescription
                                saveErrorMessage = null
                                isEditingDescription = true
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit Description", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsSection {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Link, contentDescription = null, tint = primaryColor, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("WEBSITE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (displayedWebsiteUrl.isEmpty()) {
                                        Text("No website added yet.", fontSize = 14.sp, color = Color.Gray)
                                    } else {
                                        Text(displayedWebsiteUrl, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .background(primaryColor.copy(alpha = 0.1f), CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draftWebsiteUrl = displayedWebsiteUrl
                                        websiteUrlErrorMessage = null
                                        isEditingWebsiteUrl = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AddLink, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (displayedWebsiteUrl.isEmpty()) "Add Website" else "Edit Website", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Management Section
            item {
                SettingsSection(header = "Management") {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (displayedIsActive) Icons.Default.CheckCircle else Icons.Default.PauseCircle, contentDescription = null, tint = if (displayedIsActive) Color.Green else Color.Gray)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (displayedIsActive) "Active Landmark" else "Inactive Landmark", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = displayedIsActive, onCheckedChange = { updateManagementSetting(it, null) }, enabled = !isSavingManagement)
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 50.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalOffer, contentDescription = null, tint = if (displayedPromotionEnabled) Color(0xFFFFA500) else Color.Gray)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (displayedPromotionEnabled) "Promotions Enabled" else "Promotions Disabled", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (displayedPromotionEnabled) Color(0xFFFFA500) else Color.Gray, modifier = Modifier.weight(1f))
                        Switch(checked = displayedPromotionEnabled, onCheckedChange = { updateManagementSetting(null, it) }, enabled = !isSavingManagement)
                    }
                }
            }

            // Promotions Section
            item {
                SettingsSection(
                    header = "Promotions",
                    footer = if (displayedPromotionEnabled) "Promotions can be shown for this landmark when enabled and within their date range." else "Promotions are currently disabled for this landmark. You can still create and edit records here."
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Placeholder for editor context opening
                            // promotionEditorContext = BusinessPromotionEditorContext.CREATE
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, tint = primaryColor)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Add Promotion", fontWeight = FontWeight.Bold, color = primaryColor)
                    }
                    if (isLoadingPromotions) {
                        HorizontalDivider()
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Loading promotions...", color = Color.Gray)
                        }
                    } else if (promotions.isEmpty()) {
                        HorizontalDivider()
                        Text("No promotions have been added for this landmark yet.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                    } else {
                        promotions.forEach { promotion ->
                            HorizontalDivider()
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(promotion.name.ifEmpty { "Untitled Promotion" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        if (promotion.description.trim().isNotEmpty()) {
                                            Text(promotion.description, color = Color.Gray, fontSize = 14.sp)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(if (promotion.enabled) "Active" else "Inactive", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (promotion.enabled) Color(0xFFFFA500) else Color.Gray, modifier = Modifier.background(if (promotion.enabled) Color(0xFFFFA500).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                    Switch(
                                        checked = promotion.enabled,
                                        onCheckedChange = { /* Update promotion enabled logic here */ },
                                        enabled = !savingPromotionIds.contains(promotion.id)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { /* edit */ }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray.copy(alpha = 0.5f))) {
                                        Text("Edit", color = Color.Black)
                                    }
                                    Button(onClick = { promotionPendingDelete = promotion }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f))) {
                                        Text("Delete", color = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Location
            item {
                SettingsSection(header = "Location") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Latitude", color = Color.Gray, fontWeight = FontWeight.SemiBold)
                            Text(String.format("%.6f", landmark.latitude ?: 0.0), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Longitude", color = Color.Gray, fontWeight = FontWeight.SemiBold)
                            Text(String.format("%.6f", landmark.longitude ?: 0.0), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Media Uploads
            item {
                SettingsSection(
                    header = "Media Uploads",
                    footer = "Choose media first, confirm your selection in the photo picker, then submit when ready."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // BusinessMediaHistoryNavigationRow placeholder
                        Text("View Media History", color = primaryColor, fontWeight = FontWeight.Bold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        // Positive Picker
                        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !isUploadingMedia) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            positivePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Choose Positive Media", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(if (selectedPositiveMediaItems.isEmpty()) "Select photos or videos of this landmark." else "${selectedPositiveMediaItems.size} item(s) selected. Tap Submit when ready.", color = Color.Gray, fontSize = 13.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                        }

                        if (selectedPositiveMediaItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { uploadMedia(selectedPositiveMediaItems, BusinessDatasetRole.POSITIVE) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = primaryColor), enabled = !isUploadingMedia) {
                                    Text("Submit", color = Color.White)
                                }
                                Button(onClick = { selectedPositiveMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)), enabled = !isUploadingMedia) {
                                    Text("Clear", color = Color.Red)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        // Negative Picker
                        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !isUploadingMedia) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            negativePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RemoveCircle, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Choose Negative Examples", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(if (selectedNegativeMediaItems.isEmpty()) "Select nearby objects that are not this landmark." else "${selectedNegativeMediaItems.size} item(s) selected. Tap Submit when ready.", color = Color.Gray, fontSize = 13.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                        }

                        if (selectedNegativeMediaItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { uploadMedia(selectedNegativeMediaItems, BusinessDatasetRole.HARD_NEGATIVE) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)), enabled = !isUploadingMedia) {
                                    Text("Submit", color = Color.White)
                                }
                                Button(onClick = { selectedNegativeMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)), enabled = !isUploadingMedia) {
                                    Text("Clear", color = Color.Red)
                                }
                            }
                        }

                        // Upload Status Area
                        if (isUploadingMedia) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(if (activeUploadRole != null) "Uploading ${activeUploadRole?.name?.lowercase()}..." else "Uploading media...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (uploadProgressText != null) Text(uploadProgressText!!, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                        if (uploadStatusMessage != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(uploadStatusMessage!!, color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        if (uploadErrorMessage != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(uploadErrorMessage!!, color = Color(0xFFFFA500), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Danger Zone
            item {
                SettingsSection(header = "Danger Zone") {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            deleteConfirmationText = ""
                            deleteErrorMessage = null
                            isShowingDeleteLandmarkSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Landmark", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Modals
        if (isEditingDescription) {
            ModalBottomSheet(onDismissRequest = { isEditingDescription = false }, containerColor = Color(0xFFF2F2F7)) {
                // Description Editor Content
                Column(modifier = Modifier.padding(16.dp).fillMaxHeight(0.8f)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isEditingDescription = false }, enabled = !isSavingDescription) { Text("Cancel") }
                        TextButton(onClick = {
                            if (!isSavingDescription) {
                                val cleaned = draftShortDescription.trim()
                                if (cleaned.isEmpty()) saveErrorMessage = "Short description cannot be empty."
                                else {
                                    isSavingDescription = true
                                    saveErrorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val updated = service.updateShortDescription(landmark.landmarkId, cleaned)
                                            displayedShortDescription = updated.shortDescription ?: cleaned
                                            draftShortDescription = displayedShortDescription
                                            onLandmarkUpdated(updated)
                                            isEditingDescription = false
                                        } catch (e: Exception) {
                                            saveErrorMessage = e.localizedMessage
                                        } finally {
                                            isSavingDescription = false
                                        }
                                    }
                                }
                            }
                        }, enabled = !isSavingDescription && draftShortDescription.trim().isNotEmpty()) {
                            if (isSavingDescription) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            else Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("SHORT DESCRIPTION", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftShortDescription, onValueChange = { draftShortDescription = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        enabled = !isSavingDescription
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("This description is shown to users when LookSee identifies this landmark.", fontSize = 13.sp, color = Color.Gray)
                    if (saveErrorMessage != null) Text(saveErrorMessage!!, color = Color(0xFFFFA500), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        if (isShowingDeleteLandmarkSheet) {
            ModalBottomSheet(onDismissRequest = { isShowingDeleteLandmarkSheet = false }, containerColor = Color(0xFFF2F2F7)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("CONFIRM LANDMARK DELETION", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSection {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(landmark.landmarkId, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.Gray)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            OutlinedTextField(
                                value = deleteConfirmationText, onValueChange = { deleteConfirmationText = it },
                                placeholder = { Text("delete landmark") },
                                enabled = !isDeletingLandmark,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Text("To confirm, type exactly: delete landmark", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 16.dp))

                    val isValid = deleteConfirmationText.trim() == "delete landmark"
                    Button(
                        onClick = {
                            if (isValid) {
                                isDeletingLandmark = true
                                deleteErrorMessage = null
                                coroutineScope.launch {
                                    try {
                                        service.deleteLandmark(landmark.landmarkId, deleteConfirmationText)
                                        isShowingDeleteLandmarkSheet = false
                                        onLandmarkDeleted(landmark.landmarkId)
                                        onDismiss()
                                    } catch (e: Exception) {
                                        deleteErrorMessage = e.localizedMessage
                                    } finally {
                                        isDeletingLandmark = false
                                    }
                                }
                            } else {
                                deleteErrorMessage = "Type exactly: delete landmark"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isValid && !isDeletingLandmark) Color.Red else Color.Gray.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = isValid && !isDeletingLandmark
                    ) {
                        if (isDeletingLandmark) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        else Text("Confirm Delete Landmark", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (deleteErrorMessage != null) {
                        Text(deleteErrorMessage!!, color = Color(0xFFFFA500), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}