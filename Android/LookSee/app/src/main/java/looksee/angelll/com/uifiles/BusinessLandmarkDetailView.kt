package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Renamed to avoid conflicts with Settings.kt
private val DetailPrimaryBlue = Color(0xFF387DFF)
private val DetailBackgroundGray = Color(0xFFF2F2F7)

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarkDetailView(
    landmark: BusinessLandmark,
    onLandmarkUpdated: (BusinessLandmark) -> Unit = {},
    onLandmarkDeleted: (String) -> Unit = {},
    onPromotionTitlesChanged: (String, List<String>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val service = remember { BusinessLandmarkService() }
    val promotionService = remember { BusinessPromotionService() }

    // State Variables
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

    var promotionEditorContext by remember { mutableStateOf<PromotionEditorState>(PromotionEditorState.Hidden) }
    var promotionPendingDelete by remember { mutableStateOf<BusinessPromotion?>(null) }
    val savingPromotionIds = remember { mutableStateListOf<String>() }

    var isShowingDeleteLandmarkSheet by remember { mutableStateOf(false) }
    var deleteConfirmationText by remember { mutableStateOf("") }
    var isDeletingLandmark by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }

    var showPositiveCamera by remember { mutableStateOf(false) }
    var showNegativeCamera by remember { mutableStateOf(false) }

    var selectedPositiveMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedNegativeMediaItems by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingPositiveVideoURLs by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingNegativeVideo by remember { mutableStateOf<CapturedNegativeVideo?>(null) }

    val maxSelectionCount = 10
    val positivePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxSelectionCount)) { uris ->
        selectedPositiveMediaItems = uris
    }
    val negativePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxSelectionCount)) { uris ->
        selectedNegativeMediaItems = uris
    }

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

    // Helpers
    fun updateManagementSetting(isActive: Boolean?, promotionEnabled: Boolean?) {
        if (isSavingManagement) return
        val prevActive = displayedIsActive
        val prevPromo = displayedPromotionEnabled

        if (isActive != null) displayedIsActive = isActive
        if (promotionEnabled != null) displayedPromotionEnabled = promotionEnabled

        isSavingManagement = true
        managementErrorMessage = null

        coroutineScope.launch {
            try {
                val updatedLandmark = service.updateLandmarkSettings(landmark.landmarkId, isActive, promotionEnabled)
                displayedIsActive = updatedLandmark.isActive ?: displayedIsActive
                displayedPromotionEnabled = updatedLandmark.promotionEnabled ?: displayedPromotionEnabled
                onLandmarkUpdated(updatedLandmark)
                isSavingManagement = false
            } catch (e: Exception) {
                displayedIsActive = prevActive
                displayedPromotionEnabled = prevPromo
                managementErrorMessage = e.localizedMessage
                isSavingManagement = false
            }
        }
    }

    fun loadPromotions() {
        isLoadingPromotions = true
        promotionErrorMessage = null
        coroutineScope.launch {
            try {
                val response = promotionService.fetchPromotions(landmark.landmarkId)
                promotions = response.items
                isLoadingPromotions = false
                val titles = promotions.map { it.name.trim() }.filter { it.isNotEmpty() }
                onPromotionTitlesChanged(landmark.landmarkId, titles)
            } catch (e: Exception) {
                promotionErrorMessage = e.localizedMessage
                isLoadingPromotions = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadPromotions()
    }

    Box(modifier = Modifier.fillMaxSize().background(DetailBackgroundGray)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // MARK: - Header
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = landmark.label.ifEmpty { "Untitled Landmark" },
                        fontSize = 26.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = displayedShortDescription.ifEmpty { "No description available." },
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.Gray
                    )

                    Button(
                        onClick = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            draftShortDescription = displayedShortDescription
                            saveErrorMessage = null
                            isEditingDescription = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DetailPrimaryBlue.copy(alpha = 0.1f), contentColor = DetailPrimaryBlue),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit Description", fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = DetailPrimaryBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            Text("WEBSITE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            if (displayedWebsiteUrl.trim().isEmpty()) {
                                Text("No website added yet.", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                            } else {
                                Text(displayedWebsiteUrl, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            draftWebsiteUrl = displayedWebsiteUrl
                            websiteUrlErrorMessage = null
                            isEditingWebsiteUrl = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DetailPrimaryBlue.copy(alpha = 0.1f), contentColor = DetailPrimaryBlue),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (displayedWebsiteUrl.trim().isEmpty()) "Add Website" else "Edit Website", fontWeight = FontWeight.Bold)
                    }

                    if (websiteUrlErrorMessage != null) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                            Text(websiteUrlErrorMessage!!, fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // MARK: - Management
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Management")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (displayedIsActive) Icons.Default.CheckCircle else Icons.Default.PauseCircle, contentDescription = null, tint = if (displayedIsActive) Color(0xFF34C759) else Color.Gray)
                            Spacer(Modifier.width(16.dp))
                            Text(if (displayedIsActive) "Active Landmark" else "Inactive Landmark", fontWeight = FontWeight.SemiBold, color = if (displayedIsActive) Color(0xFF34C759) else Color.Gray, modifier = Modifier.weight(1f))
                            Switch(
                                checked = displayedIsActive,
                                onCheckedChange = { updateManagementSetting(isActive = it, promotionEnabled = null) },
                                enabled = !isSavingManagement
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 50.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (displayedPromotionEnabled) Icons.Default.LocalOffer else Icons.Default.Label, contentDescription = null, tint = if (displayedPromotionEnabled) Color(0xFFFFA500) else Color.Gray)
                            Spacer(Modifier.width(16.dp))
                            Text(if (displayedPromotionEnabled) "Promotions Enabled" else "Promotions Disabled", fontWeight = FontWeight.SemiBold, color = if (displayedPromotionEnabled) Color(0xFFFFA500) else Color.Gray, modifier = Modifier.weight(1f))
                            Switch(
                                checked = displayedPromotionEnabled,
                                onCheckedChange = { updateManagementSetting(isActive = null, promotionEnabled = it) },
                                enabled = !isSavingManagement
                            )
                        }
                    }
                }
                if (isSavingManagement) {
                    Row(modifier = Modifier.padding(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DetailPrimaryBlue, strokeWidth = 2.dp)
                        Text("Saving settings...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }
                if (managementErrorMessage != null) {
                    Text(managementErrorMessage!!, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Red, modifier = Modifier.padding(horizontal = 36.dp))
                }
            }

            // MARK: - Promotions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Promotions")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                promotionEditorContext = PromotionEditorState.Create
                            }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, tint = DetailPrimaryBlue)
                            Spacer(Modifier.width(16.dp))
                            Text("Add Promotion", fontWeight = FontWeight.Bold, color = DetailPrimaryBlue)
                        }

                        if (isLoadingPromotions) {
                            HorizontalDivider()
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = DetailPrimaryBlue, strokeWidth = 2.dp)
                                Text("Loading promotions...", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            }
                        } else if (promotions.isEmpty()) {
                            HorizontalDivider()
                            Text("No promotions have been added for this landmark yet.", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray, modifier = Modifier.padding(16.dp))
                        } else {
                            promotions.forEach { promo ->
                                HorizontalDivider()
                                PromotionRow(
                                    promotion = promo,
                                    isSaving = savingPromotionIds.contains(promo.id),
                                    onEdit = { promotionEditorContext = PromotionEditorState.Edit(promo) },
                                    onDelete = { promotionPendingDelete = promo },
                                    onToggle = { enabled ->
                                        coroutineScope.launch {
                                            savingPromotionIds.add(promo.id)
                                            promotionErrorMessage = null
                                            try {
                                                val updated = promotionService.updatePromotion(landmark.landmarkId, promo.id, null, null, null, null, null, enabled)
                                                promotions = promotions.map { if (it.id == promo.id) updated else it }
                                            } catch (e: Exception) {
                                                promotionErrorMessage = e.localizedMessage
                                            }
                                            savingPromotionIds.remove(promo.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (displayedPromotionEnabled) "Promotions can be shown for this landmark when enabled and within their date range." else "Promotions are currently disabled for this landmark. You can still create and edit records here.",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray, modifier = Modifier.padding(horizontal = 36.dp)
                )

                if (promotionErrorMessage != null) {
                    Row(modifier = Modifier.padding(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(16.dp))
                        Text(promotionErrorMessage!!, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }
            }

            // MARK: - Location
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Location")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailRow("Latitude", String.format(Locale.getDefault(), "%.6f", landmark.latitude ?: 0.0))
                        HorizontalDivider()
                        DetailRow("Longitude", String.format(Locale.getDefault(), "%.6f", landmark.longitude ?: 0.0))
                    }
                }
            }

            // MARK: - Legacy Promotion
            val legacyPromo = landmark.promotion?.trim() ?: ""
            if (legacyPromo.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Legacy Promotion")
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(legacyPromo, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(20.dp))
                    }
                }
            }

            // MARK: - Media Uploads
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Media Uploads")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BusinessMediaHistoryNavigationRow(landmark.landmarkId, landmark.label)
                        HorizontalDivider()

                        UploadRow(title = "Record Positive Video", subtitle = if (pendingPositiveVideoURLs.isEmpty()) "Record new views of this landmark with the camera." else "A recording is ready to retry.", icon = Icons.Default.Videocam, color = DetailPrimaryBlue) {
                            pendingPositiveVideoURLs = emptyList()
                            uploadStatusMessage = null
                            showPositiveCamera = true
                        }

                        UploadRow(title = "Choose Positive Media", subtitle = if (selectedPositiveMediaItems.isEmpty()) "Select photos or videos of this landmark." else "${selectedPositiveMediaItems.size} items selected. Tap Submit when ready.", icon = Icons.Default.AddCircle, color = DetailPrimaryBlue) {
                            positivePhotoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }

                        if (selectedPositiveMediaItems.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { /* Implement Upload logic here */ }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DetailPrimaryBlue), shape = RoundedCornerShape(12.dp)) { Text("Submit", fontWeight = FontWeight.Bold) }
                                Button(onClick = { selectedPositiveMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red), shape = RoundedCornerShape(12.dp)) { Text("Clear", fontWeight = FontWeight.Bold) }
                            }
                        }

                        HorizontalDivider()

                        UploadRow(title = "Record Negative Video", subtitle = if (pendingNegativeVideo == null) "Record nearby objects without including the landmark." else "A negative recording is ready to retry.", icon = Icons.Default.VideocamOff, color = Color(0xFFFFA500)) {
                            pendingNegativeVideo = null
                            uploadStatusMessage = null
                            showNegativeCamera = true
                        }

                        UploadRow(title = "Choose Negative Examples", subtitle = if (selectedNegativeMediaItems.isEmpty()) "Select nearby objects that are not this landmark." else "${selectedNegativeMediaItems.size} items selected. Tap Submit when ready.", icon = Icons.Default.RemoveCircle, color = Color(0xFFFFA500)) {
                            negativePhotoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }

                        if (selectedNegativeMediaItems.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { /* Implement Upload logic here */ }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)), shape = RoundedCornerShape(12.dp)) { Text("Submit", fontWeight = FontWeight.Bold) }
                                Button(onClick = { selectedNegativeMediaItems = emptyList() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red), shape = RoundedCornerShape(12.dp)) { Text("Clear", fontWeight = FontWeight.Bold) }
                            }
                        }

                        // Upload Status Area (Restored from the missing Swift translation)
                        if (isUploadingMedia) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(color = DetailPrimaryBlue, modifier = Modifier.size(20.dp))
                                Column {
                                    Text(if (activeUploadRole != null) "Uploading ${activeUploadRole?.displayName?.lowercase(Locale.getDefault())}..." else "Uploading media...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    if (uploadProgressText != null) {
                                        Text(uploadProgressText!!, fontSize = 12.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }

                        if (uploadStatusMessage != null) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759))
                                Text(uploadStatusMessage!!, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
                            }
                        }

                        if (uploadErrorMessage != null) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                                Text(uploadErrorMessage!!, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500))
                            }
                        }
                    }
                }
                Text("Record new video with the camera or choose existing photos and videos from your library.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray, modifier = Modifier.padding(horizontal = 36.dp))
            }

            // MARK: - Danger Zone
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Danger Zone")
                Button(
                    onClick = {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        deleteConfirmationText = ""
                        deleteErrorMessage = null
                        isShowingDeleteLandmarkSheet = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Delete Landmark", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                Text("Deleting a landmark removes it from your account and starts backend cleanup for cluster mappings, dataset files, and promotions. This cannot be undone.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray, modifier = Modifier.padding(horizontal = 36.dp))
            }

            // MARK: - Identifiers
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Identifiers")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailRow("Landmark ID", landmark.landmarkId)
                        if (!landmark.ownerUserId.isNullOrEmpty()) { HorizontalDivider(); DetailRow("Owner User ID", landmark.ownerUserId) }
                        if (!landmark.userEmail.isNullOrEmpty()) { HorizontalDivider(); DetailRow("Owner Email", landmark.userEmail) }
                        if (!landmark.updatedAt.isNullOrEmpty()) { HorizontalDivider(); DetailRow("Updated At", landmark.updatedAt) }
                    }
                }
            }
        }

        // MARK: - Dialogs & Overlays

        if (isEditingDescription) {
            Dialog(onDismissRequest = { if (!isSavingDescription) isEditingDescription = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = { Text("Edit Description", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                            navigationIcon = { TextButton(onClick = { isEditingDescription = false }, enabled = !isSavingDescription) { Text("Cancel") } },
                            actions = {
                                TextButton(onClick = {
                                    val cleaned = draftShortDescription.trim()
                                    if (cleaned.isEmpty()) { saveErrorMessage = "Cannot be empty."; return@TextButton }
                                    isSavingDescription = true
                                    saveErrorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val updated = service.updateShortDescription(landmark.landmarkId, cleaned)
                                            displayedShortDescription = updated.shortDescription ?: cleaned
                                            onLandmarkUpdated(updated)
                                            isEditingDescription = false
                                        } catch (e: Exception) {
                                            saveErrorMessage = e.localizedMessage
                                        }
                                        isSavingDescription = false
                                    }
                                }, enabled = !isSavingDescription && draftShortDescription.trim().isNotEmpty()) {
                                    if (isSavingDescription) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SectionTitle("SHORT DESCRIPTION")
                            OutlinedTextField(
                                value = draftShortDescription,
                                onValueChange = { draftShortDescription = it },
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !isSavingDescription
                            )
                            Text("This description is shown to users when LookSee identifies this landmark.", fontSize = 13.sp, color = Color.Gray)
                            if (saveErrorMessage != null) Text(saveErrorMessage!!, color = Color.Red, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (isEditingWebsiteUrl) {
            Dialog(onDismissRequest = { if (!isSavingWebsiteUrl) isEditingWebsiteUrl = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember{MutableInteractionSource()}, indication = null) { focusManager.clearFocus() }) {
                        TopAppBar(
                            title = { Text("Edit Website", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                            navigationIcon = { TextButton(onClick = { isEditingWebsiteUrl = false }, enabled = !isSavingWebsiteUrl) { Text("Cancel") } },
                            actions = {
                                TextButton(onClick = {
                                    isSavingWebsiteUrl = true
                                    websiteUrlErrorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val updated = service.updateWebsiteUrl(landmark.landmarkId, draftWebsiteUrl.trim())
                                            displayedWebsiteUrl = updated.websiteUrl ?: draftWebsiteUrl.trim()
                                            onLandmarkUpdated(updated)
                                            isEditingWebsiteUrl = false
                                        } catch (e: Exception) {
                                            websiteUrlErrorMessage = e.localizedMessage
                                        }
                                        isSavingWebsiteUrl = false
                                    }
                                }, enabled = !isSavingWebsiteUrl) {
                                    if (isSavingWebsiteUrl) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SectionTitle("WEBSITE URL")
                            OutlinedTextField(
                                value = draftWebsiteUrl,
                                onValueChange = { draftWebsiteUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None),
                                enabled = !isSavingWebsiteUrl,
                                placeholder = { Text("example.com") }
                            )
                            Text("Users will be able to open this website from the landmark popup. Leave blank to clear.", fontSize = 13.sp, color = Color.Gray)
                            if (websiteUrlErrorMessage != null) Text(websiteUrlErrorMessage!!, color = Color.Red, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (isShowingDeleteLandmarkSheet) {
            Dialog(onDismissRequest = { if (!isDeletingLandmark) isShowingDeleteLandmarkSheet = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = { Text("Delete Landmark", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                            navigationIcon = { TextButton(onClick = { isShowingDeleteLandmarkSheet = false }, enabled = !isDeletingLandmark) { Text("Cancel") } }
                        )
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            SectionTitle("CONFIRM LANDMARK DELETION")
                            Surface(color = DetailBackgroundGray, shape = RoundedCornerShape(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(landmark.landmarkId, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    OutlinedTextField(
                                        value = deleteConfirmationText,
                                        onValueChange = { deleteConfirmationText = it },
                                        placeholder = { Text("delete landmark") },
                                        enabled = !isDeletingLandmark,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Text("To confirm, type exactly: delete landmark", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))

                            Button(
                                onClick = {
                                    if (deleteConfirmationText.trim() != "delete landmark") {
                                        deleteErrorMessage = "Type exactly: delete landmark"
                                        return@Button
                                    }
                                    isDeletingLandmark = true
                                    deleteErrorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            service.deleteLandmark(landmark.landmarkId, deleteConfirmationText)
                                            onLandmarkDeleted(landmark.landmarkId)
                                            onDismiss()
                                        } catch (e: Exception) {
                                            deleteErrorMessage = e.localizedMessage
                                            isDeletingLandmark = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, disabledContainerColor = Color.Gray.copy(alpha = 0.3f)),
                                enabled = deleteConfirmationText.trim() == "delete landmark" && !isDeletingLandmark,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                if (isDeletingLandmark) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White) else Text("Confirm Delete Landmark", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                            if (deleteErrorMessage != null) Text(deleteErrorMessage!!, color = Color.Red, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (promotionPendingDelete != null) {
            AlertDialog(
                onDismissRequest = { promotionPendingDelete = null },
                title = { Text("Delete Promotion?") },
                text = { Text("This promotion will be permanently removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        val promo = promotionPendingDelete!!
                        coroutineScope.launch {
                            savingPromotionIds.add(promo.id)
                            try {
                                promotionService.deletePromotion(landmark.landmarkId, promo.id)
                                promotions = promotions.filterNot { it.id == promo.id }
                                val titles = promotions.map { it.name.trim() }.filter { it.isNotEmpty() }
                                onPromotionTitlesChanged(landmark.landmarkId, titles)
                            } catch (e: Exception) {
                                promotionErrorMessage = e.localizedMessage
                            }
                            savingPromotionIds.remove(promo.id)
                            promotionPendingDelete = null
                        }
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { promotionPendingDelete = null }) { Text("Cancel") } }
            )
        }

        if (showPositiveCamera) { PositiveVideoCameraView(isActive = true, isNavVisible = false, completionButtonTitle = "Use Recorded Videos", onDone = { pendingPositiveVideoURLs = it; showPositiveCamera = false }, onCancel = { showPositiveCamera = false }) }
        if (showNegativeCamera) { NegativeVideoCameraView(onDone = { pendingNegativeVideo = it; showNegativeCamera = false }) }
    }
}

// MARK: - Reusable UI Components

@Composable
fun SectionTitle(title: String) {
    Text(title.uppercase(Locale.getDefault()), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 36.dp))
}

@Composable
fun DetailRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
        Spacer(Modifier.width(16.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

@Composable
fun UploadRow(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
    }
}

@Composable
fun PromotionRow(promotion: BusinessPromotion, isSaving: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(promotion.name.ifEmpty { "Untitled Promotion" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (promotion.description.trim().isNotEmpty()) Text(promotion.description, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                Text(
                    text = if (promotion.enabled) "Active" else "Inactive",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (promotion.enabled) Color(0xFFFFA500) else Color.Gray,
                    modifier = Modifier.padding(top = 4.dp).background(if (promotion.enabled) Color(0xFFFFA500).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Switch(checked = promotion.enabled, onCheckedChange = onToggle, enabled = !isSaving, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFA500), checkedTrackColor = Color(0xFFFFA500).copy(alpha = 0.5f)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onEdit, enabled = !isSaving, modifier = Modifier.weight(1f).height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.1f), contentColor = Color.Black), shape = RoundedCornerShape(10.dp)) { Text("Edit", fontWeight = FontWeight.Bold) }
            Button(onClick = onDelete, enabled = !isSaving, modifier = Modifier.weight(1f).height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f), contentColor = Color.Red), shape = RoundedCornerShape(10.dp)) { Text("Delete", fontWeight = FontWeight.Bold) }
        }
    }
}

// =========================================================================
// MOCKS FOR MISSING FILES
// =========================================================================

data class BusinessLandmark(
    val landmarkId: String, val label: String, val shortDescription: String?, val isActive: Boolean?,
    val promotionEnabled: Boolean?, val websiteUrl: String?, val promotion: String?, val latitude: Double?,
    val longitude: Double?, val ownerUserId: String?, val userEmail: String?, val updatedAt: String?
)

data class BusinessPromotion(val id: String, val name: String, val description: String, val imageUrl: String?, val startDate: String, val endDate: String, val enabled: Boolean)

class BusinessLandmarkService {
    suspend fun updateLandmarkSettings(id: String, active: Boolean?, promo: Boolean?): BusinessLandmark {
        delay(10L)
        return BusinessLandmark(id, "", null, active, promo, null, null, null, null, null, null, null)
    }
    suspend fun updateShortDescription(id: String, desc: String): BusinessLandmark {
        delay(10L)
        return BusinessLandmark(id, "", desc, null, null, null, null, null, null, null, null, null)
    }
    suspend fun updateWebsiteUrl(id: String, url: String): BusinessLandmark {
        delay(10L)
        return BusinessLandmark(id, "", null, null, null, url, null, null, null, null, null, null)
    }
    suspend fun deleteLandmark(id: String, text: String) { delay(10L) }
}

class BusinessPromotionService {
    suspend fun fetchPromotions(id: String): PromotionResponse {
        delay(10L)
        return PromotionResponse(emptyList())
    }
    suspend fun updatePromotion(lId: String, pId: String, n: String?, d: String?, i: String?, s: String?, e: String?, enabled: Boolean): BusinessPromotion {
        delay(10L)
        return BusinessPromotion(pId, "", "", null, "", "", enabled)
    }
    suspend fun deletePromotion(lId: String, pId: String) { delay(10L) }
}
data class PromotionResponse(val items: List<BusinessPromotion>)

sealed class PromotionEditorState { object Hidden : PromotionEditorState(); object Create : PromotionEditorState(); data class Edit(val promo: BusinessPromotion) : PromotionEditorState() }
enum class BusinessDatasetRole(val displayName: String, val filenameComponent: String) { Positive("Positive", "pos"), HardNegative("Hard Negative", "neg") }
data class CapturedNegativeVideo(val fileURL: Uri)

@Composable fun BusinessMediaHistoryNavigationRow(id: String, label: String) { Text("BusinessMediaHistoryNavigationRow Mock") }
@Composable fun PositiveVideoCameraView(isActive: Boolean, isNavVisible: Boolean, completionButtonTitle: String, onDone: (List<Uri>) -> Unit, onCancel: () -> Unit) {}
@Composable fun NegativeVideoCameraView(onDone: (CapturedNegativeVideo) -> Unit) {}