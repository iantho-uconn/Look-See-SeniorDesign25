package looksee.angelll.com.uifiles

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import looksee.angelll.com.models.OfflineMediaManager as ArchiveManager
import looksee.angelll.com.models.AutoUploadManager as Uploader
import looksee.angelll.com.detection.NetworkMonitor as Monitor
import looksee.angelll.com.models.BusinessLandmarksViewModel
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.models.BusinessPromotionService
import looksee.angelll.com.models.NearbyLandmark
import looksee.angelll.com.models.BusinessPromotion
import looksee.angelll.com.models.ArchivedMedia
import looksee.angelll.com.models.AutoUploadState
import looksee.angelll.com.models.AutoUploadItemProgress
import looksee.angelll.com.models.BusinessLandmarkDataSource
import looksee.angelll.com.models.BusinessLandmarkService
import looksee.angelll.com.models.BusinessPromotionListResponse
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarksView(
    vm: AuthViewModel,
    onNavigate: (String, Any?) -> Unit // Route string, optional payload (e.g., landmarkId)
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Colors moved inside to prevent cross-file package collisions
    val PrimaryBlue = Color(0xFF387DFF)
    val SecondaryGrouped = Color(0xFF1C1C1E)

    // ViewModels & Managers
    val viewModel = remember { BusinessLandmarksViewModel() }
    val offlineManager = remember { ArchiveManager.shared(context) }
    val uploadManager = remember { Uploader.shared(context) }
    val networkMonitor = remember { Monitor.getInstance(context) }

    // State
    var searchText by remember { mutableStateOf("") }
    var promotionTitlesByLandmarkId by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var isIndexingPromotionTitles by remember { mutableStateOf(false) }

    // Selection Mode
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedLandmarkIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkPromotionSheet by remember { mutableStateOf(false) }
    var showBulkDeleteSheet by remember { mutableStateOf(false) }

    var landmarkNeedingMedia by remember { mutableStateOf<BusinessLandmark?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    val archivedItems by offlineManager.archivedItems.collectAsState()
    val isOnline by networkMonitor.isConnected.collectAsState()
    val autoUploadState by uploadManager.state.collectAsState()
    val currentlyUploadingId = autoUploadState.currentlyUploadingId
    val currentUploadProgress = autoUploadState.currentUploadProgress

    val landmarks by viewModel.landmarks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val promotionService = remember { BusinessPromotionService() }

    // Computed Properties
    val cleanedSearchText = searchText.trim()

    val displayedLandmarks = remember(landmarks, cleanedSearchText, promotionTitlesByLandmarkId) {
        if (cleanedSearchText.isEmpty()) return@remember landmarks

        landmarks.mapNotNull { landmark ->
            if (landmark.label.contains(cleanedSearchText, ignoreCase = true)) Pair(landmark, 0)
            else if (promotionTitlesByLandmarkId[landmark.landmarkId].orEmpty().any { it.contains(cleanedSearchText, ignoreCase = true) }) Pair(landmark, 1)
            else null
        }.sortedWith(compareBy({ it.second }, { it.first.label })).map { it.first }
    }

    val actionNeededLandmarks = displayedLandmarks.filter { it.status == "NEEDS_MORE_MEDIA" }
    val healthyLandmarks = displayedLandmarks.filter { it.status != "NEEDS_MORE_MEDIA" }

    val visibleLandmarkIds = displayedLandmarks.map { it.landmarkId }.toSet()
    val selectedLandmarks = landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) }
    val visibleSelectedCount = selectedLandmarkIds.intersect(visibleLandmarkIds).size
    val hiddenSelectedCount = selectedLandmarkIds.subtract(visibleLandmarkIds).size
    val selectionCountText = "${selectedLandmarks.size} landmark${if (selectedLandmarks.size == 1) "" else "s"} selected"

    fun healthyLandmarkCountText(count: Int): String {
        return if (cleanedSearchText.isNotEmpty()) "($count)" else "($count of ${landmarks.count { it.status != "NEEDS_MORE_MEDIA" }})"
    }

    // Actions
    fun loadPromotionSearchIndex(forceReload: Boolean = false) {
        coroutineScope.launch {
            val validLandmarkIds = landmarks.map { it.landmarkId }.toSet()
            promotionTitlesByLandmarkId = promotionTitlesByLandmarkId.filterKeys { it in validLandmarkIds }

            val landmarksToLoad = if (forceReload) landmarks else landmarks.filter { promotionTitlesByLandmarkId[it.landmarkId] == null }
            if (landmarksToLoad.isEmpty()) return@launch

            isIndexingPromotionTitles = true
            for (landmark in landmarksToLoad) {
                try {
                    val response = promotionService.fetchPromotions(landmark.landmarkId)
                    val titles = response.items.map { it.name.trim() }.filter { it.isNotEmpty() }
                    promotionTitlesByLandmarkId = promotionTitlesByLandmarkId.toMutableMap().apply { put(landmark.landmarkId, titles) }
                } catch (e: Exception) {
                    if (promotionTitlesByLandmarkId[landmark.landmarkId] == null) {
                        val legacy = landmark.promotion?.trim()
                        val titles = if (legacy.isNullOrEmpty()) emptyList() else listOf(legacy)
                        promotionTitlesByLandmarkId = promotionTitlesByLandmarkId.toMutableMap().apply { put(landmark.landmarkId, titles) }
                    }
                }
            }
            isIndexingPromotionTitles = false
        }
    }

    fun refreshLandmarksAndSearchIndex() {
        coroutineScope.launch {
            viewModel.refresh()
            loadPromotionSearchIndex(forceReload = true)
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("CheckGlobalNotifications"))
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLoadedOnce) {
            hasLoadedOnce = true
            viewModel.loadLandmarks()
            loadPromotionSearchIndex()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("CheckGlobalNotifications"))
        }
    }

    LaunchedEffect(landmarks) {
        val validIds = landmarks.map { it.landmarkId }.toSet()
        selectedLandmarkIds = selectedLandmarkIds.intersect(validIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Landmarks", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, actionIconContentColor = PrimaryBlue),
                actions = {
                    if (isSelectionMode) {
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isSelectionMode = false
                            selectedLandmarkIds = emptySet()
                        }) { Text("Done", color = PrimaryBlue, fontWeight = FontWeight.Bold) }
                    } else {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            refreshLandmarksAndSearchIndex()
                        }, enabled = !isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isSelectionMode = true
                        }, enabled = landmarks.isNotEmpty()) {
                            Text("Select", color = if (landmarks.isNotEmpty()) PrimaryBlue else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        },

        bottomBar = {
            if (isSelectionMode) {
                Surface(color = SecondaryGrouped.copy(alpha = 0.95f), shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(selectionCountText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(if (hiddenSelectedCount > 0) "$hiddenSelectedCount hidden by search" else "Selection stays active while searching", color = Color.Gray, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLandmarkIds = emptySet()
                            }, enabled = selectedLandmarkIds.isNotEmpty()) {
                                Text("Clear", color = if (selectedLandmarkIds.isNotEmpty()) Color.Red else Color.Gray, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showBulkPromotionSheet = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, disabledContainerColor = PrimaryBlue.copy(alpha = 0.45f))
                            ) { Text("Add Promotion", fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showBulkDeleteSheet = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.12f), contentColor = Color.Red, disabledContainerColor = Color.Red.copy(alpha = 0.05f), disabledContentColor = Color.Red.copy(alpha = 0.45f))
                            ) { Text("Delete", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refreshLandmarksAndSearchIndex() },
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(paddingValues).clickable { focusManager.clearFocus() }
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // Search Bar
                item {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text("Search labels or promotion titles", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = ""; focusManager.clearFocus() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SecondaryGrouped,
                            unfocusedContainerColor = SecondaryGrouped,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Selection Header Actions
                if (isSelectionMode) {
                    item {
                        Surface(color = PrimaryBlue.copy(alpha = 0.1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if(selectedLandmarkIds.isEmpty()) Icons.Outlined.Circle else Icons.Default.CheckCircle, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                                    Column {
                                        Text(selectionCountText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        if (hiddenSelectedCount > 0) Text("$hiddenSelectedCount selected landmark(s) hidden by current search", fontSize = 12.sp, color = Color.Gray)
                                        else if (cleanedSearchText.isNotEmpty()) Text("$visibleSelectedCount selected in these search results", fontSize = 12.sp, color = Color.Gray)
                                        else Text("Search for more landmarks without losing this selection.", fontSize = 12.sp, color = Color.Gray)
                                    }
                                }

                                var showMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreHoriz, contentDescription = "Menu", tint = PrimaryBlue) }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SecondaryGrouped)) {
                                        DropdownMenuItem(
                                            text = { Text("Select Visible (${displayedLandmarks.size})", color = Color.White) },
                                            onClick = { selectedLandmarkIds = selectedLandmarkIds.union(visibleLandmarkIds); showMenu = false },
                                            enabled = displayedLandmarks.isNotEmpty()
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Deselect Visible ($visibleSelectedCount)", color = Color.White) },
                                            onClick = { selectedLandmarkIds = selectedLandmarkIds.subtract(visibleLandmarkIds); showMenu = false },
                                            enabled = visibleSelectedCount > 0
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clear All Selection", color = Color.Red) },
                                            onClick = { selectedLandmarkIds = emptySet(); showMenu = false },
                                            enabled = selectedLandmarkIds.isNotEmpty()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pending Uploads
                if (archivedItems.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("PENDING UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
                            Surface(color = SecondaryGrouped, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Row(modifier = Modifier.background(if (currentlyUploadingId != null) PrimaryBlue.copy(0.05f) else Color.Transparent).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Icon(if (currentlyUploadingId != null) Icons.Default.CloudUpload else if (!isOnline) Icons.Default.CloudOff else Icons.Default.PauseCircle, contentDescription = null, tint = if (currentlyUploadingId != null) PrimaryBlue else Color.Gray, modifier = Modifier.size(24.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(if (currentlyUploadingId != null) "Syncing to Cloud..." else if (!isOnline) "Waiting for Connection" else "Queue Processing...", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("${archivedItems.size} items waiting to upload", fontSize = 13.sp, color = Color.Gray)
                                        }
                                        if (currentlyUploadingId != null) CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                    HorizontalDivider(color = Color.DarkGray)

                                    archivedItems.forEachIndexed { index, item ->
                                        Row(modifier = Modifier.clickable(enabled = currentlyUploadingId != item.id) {
                                            onNavigate("LandmarkRecord", item)
                                        }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Box(modifier = Modifier.size(48.dp).background(Color.DarkGray, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                                Icon(if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                if (currentlyUploadingId == item.id) {
                                                    Text("Uploading...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                                                    LinearProgressIndicator(progress = { currentUploadProgress.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = PrimaryBlue, trackColor = Color.DarkGray)
                                                } else {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(10.dp))
                                                        Text("Queued", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500))
                                                    }
                                                }
                                            }
                                            if (currentlyUploadingId != item.id) {
                                                IconButton(onClick = { coroutineScope.launch { offlineManager.deleteArchive(item) } }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.background(Color.Red.copy(0.1f), CircleShape).padding(8.dp))
                                                }
                                            }
                                        }
                                        if (index < archivedItems.size - 1) HorizontalDivider(modifier = Modifier.padding(start = 84.dp), color = Color.DarkGray)
                                    }
                                }
                            }
                        }
                    }
                } else if (landmarks.isNotEmpty() && !isSelectionMode && cleanedSearchText.isEmpty()) {
                    item {
                        Surface(color = SecondaryGrouped, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(modifier = Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(modifier = Modifier.size(70.dp).background(Color.Green.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(32.dp))
                                }
                                Text("All Caught Up!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                // Needs Attention
                if (actionNeededLandmarks.isNotEmpty()) {
                    item { Text("NEEDS ATTENTION", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Red, modifier = Modifier.padding(horizontal = 20.dp)) }
                    items(actionNeededLandmarks) { landmark ->
                        BusinessLandmarkRowWrapper(landmark, isSelectionMode, selectedLandmarkIds.contains(landmark.landmarkId), null, onNavigate) {
                            if (isSelectionMode) {
                                val current = selectedLandmarkIds.toMutableSet()
                                if (current.contains(landmark.landmarkId)) current.remove(landmark.landmarkId) else current.add(landmark.landmarkId)
                                selectedLandmarkIds = current
                            } else {
                                landmarkNeedingMedia = landmark
                            }
                        }
                    }
                }

                // Active Landmarks
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ACTIVE LANDMARKS ${healthyLandmarkCountText(healthyLandmarks.size)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }

                if (isLoading && landmarks.isEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(color = PrimaryBlue)
                            Text("Loading your landmarks...", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                } else if (landmarks.isEmpty()) {
                    item { Text("No active business landmarks.", fontSize = 15.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp)) }
                } else if (displayedLandmarks.isEmpty()) {
                    item {
                        Surface(color = SecondaryGrouped, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                                Text("No landmarks found", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Try a landmark label or promotion title.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    items(healthyLandmarks) { landmark ->
                        val matchedPromo = if (cleanedSearchText.isNotEmpty() && !landmark.label.contains(cleanedSearchText, true)) {
                            promotionTitlesByLandmarkId[landmark.landmarkId]?.firstOrNull { it.contains(cleanedSearchText, true) }
                        } else null

                        BusinessLandmarkRowWrapper(landmark, isSelectionMode, selectedLandmarkIds.contains(landmark.landmarkId), matchedPromo, onNavigate) {
                            if (isSelectionMode) {
                                val current = selectedLandmarkIds.toMutableSet()
                                if (current.contains(landmark.landmarkId)) current.remove(landmark.landmarkId) else current.add(landmark.landmarkId)
                                selectedLandmarkIds = current
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheets
    if (landmarkNeedingMedia != null) {
        NeedsMoreMediaSheet(landmark = landmarkNeedingMedia!!, onDismiss = { landmarkNeedingMedia = null }) {
            landmarkNeedingMedia = null
            val intent = Intent("TriggerRedoRecord").apply {
                putExtra("id", it.landmarkId)
                putExtra("label", it.label)
                putExtra("description", it.shortDescription ?: "")
                putExtra("secondsNeeded", it.secondsNeeded ?: 30.0)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    if (showBulkPromotionSheet) { Dialog(onDismissRequest = { showBulkPromotionSheet = false }) { BusinessBulkPromotionEditor(selectedLandmarks, onCompleted = { showBulkPromotionSheet = false }, onDismiss = { showBulkPromotionSheet = false }) } }
    if (showBulkDeleteSheet) { Dialog(onDismissRequest = { showBulkDeleteSheet = false }) { BusinessBulkDeleteView(selectedLandmarks, onCompleted = { showBulkDeleteSheet = false }, onDismiss = { showBulkDeleteSheet = false }) } }
}

@Composable
private fun BusinessLandmarkRowWrapper(
    landmark: BusinessLandmark,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    matchedPromotionTitle: String?,
    onNavigate: (String, Any?) -> Unit,
    onClick: (BusinessLandmark) -> Unit
) {
    val needsMoreMedia = landmark.status == "NEEDS_MORE_MEDIA"
    val PrimaryBlue = Color(0xFF387DFF)
    val SecondaryGrouped = Color(0xFF1C1C1E)

    Surface(
        color = if (isSelected) PrimaryBlue.copy(alpha = 0.1f) else SecondaryGrouped,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (needsMoreMedia) Color.Red.copy(0.6f) else if (isSelected) PrimaryBlue.copy(0.6f) else Color.Transparent),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable {
            if (isSelectionMode || needsMoreMedia) onClick(landmark)
            else onNavigate("BusinessLandmarkDetailView", landmark)
        }
    ) {
        Column {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isSelectionMode) {
                    Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle, contentDescription = null, tint = if (isSelected) PrimaryBlue else Color.Gray, modifier = Modifier.size(24.dp).padding(top = 1.dp))
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        if (needsMoreMedia) {
                            Text("ACTION NEEDED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Red, modifier = Modifier.background(Color.Red.copy(0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))
                        } else {
                            val statusColor = if (landmark.isActive == false) Color.Gray else Color.Green
                            Text(landmark.displayStatus.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor, modifier = Modifier.background(statusColor.copy(0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }

                    Text(landmark.displayDescription, fontSize = 14.sp, color = Color.Gray, maxLines = 2)

                    if (matchedPromotionTitle != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(12.dp))
                            Text("Matched promotion: $matchedPromotionTitle", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500), maxLines = 1)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (landmark.promotionEnabled == true) {
                            Row(modifier = Modifier.background(Color(0xFFFFA500).copy(0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(11.dp))
                                Text("Promotions On", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500))
                            }
                        }

                        if (landmark.latitude != null && landmark.longitude != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(12.dp))
                                Text(String.format("%.4f, %.4f", landmark.latitude, landmark.longitude), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
            if (needsMoreMedia) {
                Row(modifier = Modifier.fillMaxWidth().background(Color.Red.copy(0.2f)).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Text("Not enough video data to train", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                    Text("Details", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsMoreMediaSheet(landmark: BusinessLandmark, onDismiss: () -> Unit, onAddMedia: (BusinessLandmark) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1E)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(modifier = Modifier.size(80.dp).background(Color.Red.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(36.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More Media Required", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("We couldn't extract enough unique frames of ${landmark.label} to train a reliable model.", fontSize = 15.sp, color = Color.Gray, textAlign = TextAlign.Center)
            }

            Surface(color = Color.Black.copy(0.3f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val processed = landmark.cleanFrameCount ?: 0
                    val required = landmark.requiredFrames ?: 1800
                    val seconds = landmark.secondsNeeded ?: 30.0

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Frames Extracted", fontSize = 12.sp, color = Color.Gray)
                            Text("$processed / $required", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Target Video", fontSize = 12.sp, color = Color.Gray)
                            Text("~${seconds.toInt()} Secs", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                        }
                    }
                    HorizontalDivider(color = Color.DarkGray)
                    Text("Capture about ${seconds.toInt()} more seconds of video capturing your landmark. Once uploaded, training will resume automatically. This will not cost a token.", fontSize = 14.sp, color = Color.Gray)
                }
            }

            Button(
                onClick = { onAddMedia(landmark) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Add Media Now", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }

            Spacer(Modifier.height(32.dp))
        }
    }
}