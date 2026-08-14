package looksee.angelll.com.uifiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.ArchivedMedia
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.services.AutoUploadManager
import looksee.angelll.com.services.BusinessPromotionService
import looksee.angelll.com.services.NetworkMonitor
import looksee.angelll.com.services.OfflineMediaManager
import looksee.angelll.com.viewmodels.BusinessLandmarksViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarksView(
    viewModel: BusinessLandmarksViewModel // Pass your ViewModel here
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val archivedItems by OfflineMediaManager.archivedItems.collectAsState()
    val isUploading by AutoUploadManager.isUploading.collectAsState()
    val currentlyUploadingId by AutoUploadManager.currentlyUploadingId.collectAsState()
    val uploadProgress by AutoUploadManager.currentUploadProgress.collectAsState()
    val isConnected by NetworkMonitor.isConnected.collectAsState(initial = true)

    var draftToEdit by remember { mutableStateOf<ArchivedMedia?>(null) }
    var searchText by remember { mutableStateOf("") }

    val promotionTitlesByLandmarkId = remember { mutableStateMapOf<String, List<String>>() }
    var isIndexingPromotionTitles by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedLandmarkIds by remember { mutableStateOf(emptySet<String>()) }
    var bulkPromotionSelection by remember { mutableStateOf<List<BusinessLandmark>?>(null) }
    var bulkDeleteSelection by remember { mutableStateOf<List<BusinessLandmark>?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val promotionService = remember { BusinessPromotionService() }
    val primaryColor = Color(0xFF387DFF)

    // Derived Data
    val cleanedSearchText = searchText.trim()
    val visibleLandmarkIds = remember(searchText, viewModel.landmarks, promotionTitlesByLandmarkId) {
        if (cleanedSearchText.isEmpty()) {
            viewModel.landmarks.map { it.landmarkId }.toSet()
        } else {
            viewModel.landmarks.filter { landmark ->
                landmark.label.contains(cleanedSearchText, ignoreCase = true) ||
                        (promotionTitlesByLandmarkId[landmark.landmarkId]?.any { it.contains(cleanedSearchText, ignoreCase = true) } == true)
            }.map { it.landmarkId }.toSet()
        }
    }

    val displayedLandmarks = remember(searchText, viewModel.landmarks, promotionTitlesByLandmarkId) {
        if (cleanedSearchText.isEmpty()) {
            viewModel.landmarks
        } else {
            viewModel.landmarks.mapNotNull { landmark ->
                if (landmark.label.contains(cleanedSearchText, ignoreCase = true)) {
                    landmark to 0
                } else if (promotionTitlesByLandmarkId[landmark.landmarkId]?.any { it.contains(cleanedSearchText, ignoreCase = true) } == true) {
                    landmark to 1
                } else null
            }.sortedWith(compareBy({ it.second }, { it.first.label.lowercase() })).map { it.first }
        }
    }

    val visibleSelectedCount = selectedLandmarkIds.intersect(visibleLandmarkIds).size
    val hiddenSelectedCount = selectedLandmarkIds.subtract(visibleLandmarkIds).size

    // Methods
    val loadPromotionSearchIndex: suspend (Boolean) -> Unit = { forceReload ->
        val currentLandmarks = viewModel.landmarks
        val validIds = currentLandmarks.map { it.landmarkId }.toSet()

        // Clean up deleted ones
        promotionTitlesByLandmarkId.keys.retainAll(validIds)

        val landmarksToLoad = if (forceReload) currentLandmarks else currentLandmarks.filter { !promotionTitlesByLandmarkId.containsKey(it.landmarkId) }

        if (landmarksToLoad.isNotEmpty()) {
            isIndexingPromotionTitles = true
            for (landmark in landmarksToLoad) {
                try {
                    val response = promotionService.fetchPromotions(landmark.landmarkId)
                    val titles = response.items.map { it.name.trim() }.filter { it.isNotEmpty() }
                    promotionTitlesByLandmarkId[landmark.landmarkId] = titles
                } catch (e: Exception) {
                    if (!promotionTitlesByLandmarkId.containsKey(landmark.landmarkId)) {
                        val legacy = landmark.promotion?.trim()
                        promotionTitlesByLandmarkId[landmark.landmarkId] = if (legacy.isNullOrEmpty()) emptyList() else listOf(legacy)
                    }
                }
            }
            isIndexingPromotionTitles = false
        }
    }

    val refreshLandmarksAndSearchIndex: () -> Unit = {
        coroutineScope.launch {
            viewModel.refresh()
            loadPromotionSearchIndex(true)
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.landmarks.isEmpty()) {
            viewModel.loadLandmarks()
        }
        loadPromotionSearchIndex(false)
    }

    LaunchedEffect(viewModel.landmarks) {
        val validIds = viewModel.landmarks.map { it.landmarkId }.toSet()
        selectedLandmarkIds = selectedLandmarkIds.intersect(validIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Landmarks") },
                actions = {
                    if (isSelectionMode) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = "Menu", tint = primaryColor)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select Visible (${displayedLandmarks.size})") },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedLandmarkIds = selectedLandmarkIds.union(visibleLandmarkIds)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                                    enabled = displayedLandmarks.isNotEmpty()
                                )
                                DropdownMenuItem(
                                    text = { Text("Deselect Visible ($visibleSelectedCount)") },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedLandmarkIds = selectedLandmarkIds.subtract(visibleLandmarkIds)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null) },
                                    enabled = visibleSelectedCount > 0
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Clear All Selection", color = Color.Red) },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedLandmarkIds = emptySet()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Cancel, contentDescription = null, tint = Color.Red) },
                                    enabled = selectedLandmarkIds.isNotEmpty()
                                )
                            }
                        }
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isSelectionMode = false
                            selectedLandmarkIds = emptySet()
                        }) {
                            Text("Done", fontWeight = FontWeight.Bold, color = primaryColor)
                        }
                    } else {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            refreshLandmarksAndSearchIndex()
                        }, enabled = !viewModel.isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = primaryColor)
                        }
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isSelectionMode = true
                        }, enabled = viewModel.landmarks.isNotEmpty()) {
                            Text("Select", fontWeight = FontWeight.Bold, color = primaryColor)
                        }
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2F2F7).copy(alpha = 0.95f))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${selectedLandmarkIds.size} landmark${if (selectedLandmarkIds.size == 1) "" else "s"} selected", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(if (hiddenSelectedCount > 0) "$hiddenSelectedCount hidden by search" else "Selection stays active while searching", fontSize = 12.sp, color = Color.Gray)
                        }
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedLandmarkIds = emptySet()
                        }, enabled = selectedLandmarkIds.isNotEmpty()) {
                            Text("Clear", fontWeight = FontWeight.Bold, color = Color.Red)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                bulkPromotionSelection = viewModel.landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) }
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(14.dp),
                            enabled = selectedLandmarkIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Promotion", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                bulkDeleteSelection = viewModel.landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) }
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.12f), disabledContainerColor = Color.Gray.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(14.dp),
                            enabled = selectedLandmarkIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = if (selectedLandmarkIds.isNotEmpty()) Color.Red else Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete", fontWeight = FontWeight.Bold, color = if (selectedLandmarkIds.isNotEmpty()) Color.Red else Color.Gray)
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Search Bar
            item {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search labels or promotion titles") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Cancel, contentDescription = "Clear", tint = Color.Gray)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = primaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }

            // Active Landmarks Section
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ACTIVE LANDMARKS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        if (viewModel.landmarks.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (cleanedSearchText.isEmpty()) "(${viewModel.landmarks.size})" else "(${displayedLandmarks.size} of ${viewModel.landmarks.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = primaryColor, strokeWidth = 2.dp)
                        }
                    }

                    if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                        Text("Checking promotion titles...", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isSelectionMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                                .border(1.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (selectedLandmarkIds.isEmpty()) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("${selectedLandmarkIds.size} landmark${if (selectedLandmarkIds.size == 1) "" else "s"} selected", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (hiddenSelectedCount > 0) {
                                    Text("$hiddenSelectedCount selected hidden by current search", fontSize = 12.sp, color = Color.Gray)
                                } else if (cleanedSearchText.isNotEmpty()) {
                                    Text("$visibleSelectedCount selected in these search results", fontSize = 12.sp, color = Color.Gray)
                                } else {
                                    Text("Search for more landmarks without losing this selection.", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (viewModel.isLoading && viewModel.landmarks.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = primaryColor)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Loading your landmarks...", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else if (viewModel.landmarks.isEmpty()) {
                        Text("No active business landmarks.", fontSize = 15.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    } else if (displayedLandmarks.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No landmarks found", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("Try a landmark label or promotion title.", color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // Landmark List
            items(displayedLandmarks) { landmark ->
                val isSelected = selectedLandmarkIds.contains(landmark.landmarkId)
                val matchedPromo = if (cleanedSearchText.isNotEmpty() && !landmark.label.contains(cleanedSearchText, ignoreCase = true)) {
                    promotionTitlesByLandmarkId[landmark.landmarkId]?.firstOrNull { it.contains(cleanedSearchText, ignoreCase = true) }
                } else null

                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .background(if (isSelected) primaryColor.copy(alpha = 0.1f) else Color.White, RoundedCornerShape(20.dp))
                        .border(1.5f, if (isSelected) primaryColor.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            if (isSelectionMode) {
                                haptic.performHapticFeedback(HapticFeedbackType.LightImpact)
                                if (isSelected) selectedLandmarkIds -= landmark.landmarkId else selectedLandmarkIds += landmark.landmarkId
                            } else {
                                // Navigate to Detail View
                            }
                        }
                        .padding(20.dp)
                ) {
                    Row(alignment = Alignment.Top) {
                        if (isSelectionMode) {
                            Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) primaryColor else Color.LightGray, modifier = Modifier.size(24.dp).padding(top = 2.dp))
                            Spacer(modifier = Modifier.width(14.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (landmark.isActive == false) "INACTIVE" else "ACTIVE",
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = if (landmark.isActive == false) Color.Gray else Color.Green,
                                    modifier = Modifier.background(if (landmark.isActive == false) Color.Gray.copy(alpha = 0.15f) else Color.Green.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text((landmark.shortDescription?.trim()?.ifEmpty { "No description available." } ?: "No description available."), color = Color.Gray, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                            if (matchedPromo != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Matched promotion: $matchedPromo", color = Color(0xFFFFA500), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (landmark.promotionEnabled == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFFFFA500).copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Promotions On", color = Color(0xFFFFA500), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (landmark.latitude != null && landmark.longitude != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(String.format(java.util.Locale.US, "%.4f, %.4f", landmark.latitude, landmark.longitude), color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Pending Uploads Section
            if (archivedItems.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("PENDING UPLOADS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))) {
                            // Sync Banner
                            Row(modifier = Modifier.fillMaxWidth().background(if (isUploading) primaryColor.copy(alpha = 0.05f) else Color.Transparent).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isUploading) Icons.Default.CloudUpload else if (!isConnected) Icons.Default.CloudOff else Icons.Default.PauseCircle, contentDescription = null, tint = if (isUploading) primaryColor else Color.Gray, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (isUploading) "Syncing to Cloud..." else if (!isConnected) "Waiting for Connection" else "Queue Processing...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("${archivedItems.size} items waiting to upload", color = Color.Gray, fontSize = 13.sp)
                                }
                                if (isUploading) CircularProgressIndicator(color = primaryColor, modifier = Modifier.size(20.dp))
                            }
                            HorizontalDivider()

                            archivedItems.forEachIndexed { index, item ->
                                val itemIsUploading = currentlyUploadingId == item.id
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !itemIsUploading) { draftToEdit = item }.padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(48.dp).background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Icon(if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        if (itemIsUploading) {
                                            Text("Uploading...", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            LinearProgressIndicator(progress = { uploadProgress.toFloat() }, color = primaryColor, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp).clip(CircleShape))
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(10.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Queued", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    if (!itemIsUploading) {
                                        IconButton(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.MediumImpact)
                                            OfflineMediaManager.deleteArchive(context, item)
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.background(Color.Red.copy(alpha = 0.1f), CircleShape).padding(8.dp).size(16.dp))
                                        }
                                    }
                                }
                                if (index < archivedItems.size - 1) HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.size(70.dp).background(Color.Green.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("All Caught Up!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.", color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // Modals
        if (bulkPromotionSelection != null) {
            ModalBottomSheet(onDismissRequest = { bulkPromotionSelection = null }, containerColor = Color(0xFFF2F2F7)) {
                BusinessBulkPromotionEditor(
                    landmarks = bulkPromotionSelection!!,
                    onCompleted = { result ->
                        coroutineScope.launch {
                            viewModel.replaceLandmarks(result.updatedLandmarks)
                            for (landmarkId in result.successfulLandmarkIds) {
                                val titles = promotionTitlesByLandmarkId[landmarkId]?.toMutableList() ?: mutableListOf()
                                if (!titles.any { it.equals(result.promotionName, ignoreCase = true) }) {
                                    titles.add(result.promotionName)
                                }
                                promotionTitlesByLandmarkId[landmarkId] = titles
                            }
                            val failedIds = result.failedLandmarks.map { it.landmarkId }.toSet()
                            selectedLandmarkIds = failedIds
                            if (failedIds.isEmpty()) isSelectionMode = false
                        }
                    },
                    onDismiss = { bulkPromotionSelection = null }
                )
            }
        }

        if (bulkDeleteSelection != null) {
            ModalBottomSheet(onDismissRequest = { bulkDeleteSelection = null }, containerColor = Color(0xFFF2F2F7)) {
                BusinessBulkDeleteView(
                    landmarks = bulkDeleteSelection!!,
                    onCompleted = { result ->
                        coroutineScope.launch {
                            viewModel.removeLandmarks(result.successfulLandmarkIds)
                            for (landmarkId in result.successfulLandmarkIds) {
                                promotionTitlesByLandmarkId.remove(landmarkId)
                            }
                            val failedIds = result.failedLandmarks.map { it.landmarkId }.toSet()
                            selectedLandmarkIds = failedIds
                            if (failedIds.isEmpty()) isSelectionMode = false
                        }
                    },
                    onDismiss = { bulkDeleteSelection = null }
                )
            }
        }
    }
}