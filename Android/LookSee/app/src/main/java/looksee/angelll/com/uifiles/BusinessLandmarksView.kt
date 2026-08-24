package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.util.Locale

// 🚀 TEMPORARY FIX: This adds "status" to the BusinessLandmark class from your OTHER file
// so it compiles cleanly here. Delete this when you implement your real data classes!
val BusinessLandmark.status: String
    get() = "ACTIVE"

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarksView(
    onNavigateToDetail: (BusinessLandmark) -> Unit,
    onNavigateToRecord: (BusinessLandmark, ArchivedMedia?) -> Unit
) {
    // 🎨 Safely scoped colors
    val brandBlue = Color(0xFF387DFF)
    val brandOrange = Color(0xFFFFA500)
    val bgGray = Color(0xFFF2F2F7)

    val coroutineScope = rememberCoroutineScope()

    // ViewModels / Managers
    val viewModel = remember { BusinessLandmarksViewModel() }
    val offlineManager = remember { OfflineMediaManager.shared }
    val uploadManager = remember { AutoUploadManager.shared }
    val networkMonitor = remember { NetworkMonitor.shared }
    val promotionService = remember { BusinessPromotionService() }

    // Search and Promotion States
    var searchText by remember { mutableStateOf("") }
    val cleanedSearchText = searchText.trim()
    var promotionTitlesByLandmarkId by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var isIndexingPromotionTitles by remember { mutableStateOf(false) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    // Selection States
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedLandmarkIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkPromotionSelection by remember { mutableStateOf<List<BusinessLandmark>?>(null) }
    var bulkDeleteSelection by remember { mutableStateOf<List<BusinessLandmark>?>(null) }
    var landmarkNeedingMedia by remember { mutableStateOf<BusinessLandmark?>(null) }

    // Real-time State Flow collections
    val archivedItems by offlineManager.archivedItems.collectAsState(initial = emptyList())
    val currentlyUploadingId by uploadManager.currentlyUploadingId.collectAsState(initial = null)
    val currentUploadProgress by uploadManager.currentUploadProgress.collectAsState(initial = 0f)
    val isNetworkConnected by networkMonitor.isConnected.collectAsState(initial = true)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)
    val landmarks by viewModel.landmarks.collectAsState(initial = emptyList())

    // Advanced Search Filter Logic
    val displayedLandmarks by remember(landmarks, cleanedSearchText, promotionTitlesByLandmarkId) {
        derivedStateOf {
            if (cleanedSearchText.isEmpty()) return@derivedStateOf landmarks

            landmarks.mapNotNull { landmark ->
                val labelMatch = landmark.label.contains(cleanedSearchText, ignoreCase = true)
                if (labelMatch) return@mapNotNull Pair(landmark, 0)

                val titles = promotionTitlesByLandmarkId[landmark.landmarkId] ?: emptyList()
                val legacyPromo = landmark.promotion?.trim() ?: ""
                val searchableTitles = (titles + listOf(legacyPromo)).filter { it.isNotEmpty() }

                val promoMatch = searchableTitles.any { it.contains(cleanedSearchText, ignoreCase = true) }
                if (promoMatch) return@mapNotNull Pair(landmark, 1)

                null
            }.sortedWith(compareBy({ it.second }, { it.first.label.lowercase(Locale.getDefault()) }))
                .map { it.first }
        }
    }

    val visibleLandmarkIds = displayedLandmarks.map { it.landmarkId }.toSet()
    val visibleSelectedCount = selectedLandmarkIds.intersect(visibleLandmarkIds).size
    val hiddenSelectedCount = selectedLandmarkIds.subtract(visibleLandmarkIds).size
    val selectionCountText = "${selectedLandmarkIds.size} landmark${if (selectedLandmarkIds.size == 1) "" else "s"} selected"

    fun toggleSelection(id: String) {
        selectedLandmarkIds = if (selectedLandmarkIds.contains(id)) selectedLandmarkIds - id else selectedLandmarkIds + id
    }

    fun loadPromotionSearchIndex(forceReload: Boolean = false) {
        val validIds = landmarks.map { it.landmarkId }.toSet()
        val currentTitles = promotionTitlesByLandmarkId.filterKeys { validIds.contains(it) }.toMutableMap()

        if (landmarks.isEmpty()) {
            isIndexingPromotionTitles = false
            return
        }

        val landmarksToLoad = if (forceReload) landmarks else landmarks.filter { !currentTitles.containsKey(it.landmarkId) }
        if (landmarksToLoad.isEmpty()) {
            promotionTitlesByLandmarkId = currentTitles
            isIndexingPromotionTitles = false
            return
        }

        isIndexingPromotionTitles = true
        coroutineScope.launch {
            landmarksToLoad.forEach { landmark ->
                try {
                    val response = promotionService.fetchPromotions(landmark.landmarkId)
                    val titles = response.items.map { it.name.trim() }.filter { it.isNotEmpty() }
                    currentTitles[landmark.landmarkId] = titles
                } catch (_: Exception) {
                    if (!currentTitles.containsKey(landmark.landmarkId)) {
                        val legacy = landmark.promotion?.trim()
                        currentTitles[landmark.landmarkId] = if (legacy.isNullOrEmpty()) emptyList() else listOf(legacy)
                    }
                }
            }
            promotionTitlesByLandmarkId = currentTitles
            isIndexingPromotionTitles = false
        }
    }

    fun refreshLandmarksAndSearchIndex() {
        coroutineScope.launch {
            viewModel.refresh()
            loadPromotionSearchIndex(forceReload = true)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLoadedOnce) {
            hasLoadedOnce = true
            viewModel.loadLandmarks()
            loadPromotionSearchIndex()
        }
    }

    LaunchedEffect(landmarks) {
        val validIds = landmarks.map { it.landmarkId }.toSet()
        selectedLandmarkIds = selectedLandmarkIds.intersect(validIds)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("My Landmarks", fontWeight = FontWeight.Bold) },
                    actions = {
                        if (isSelectionMode) {
                            TextButton(onClick = {
                                isSelectionMode = false
                                selectedLandmarkIds = emptySet()
                            }) { Text("Done", fontWeight = FontWeight.Bold) }
                        } else {
                            IconButton(onClick = { refreshLandmarksAndSearchIndex() }, enabled = !isLoading) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            TextButton(
                                onClick = { isSelectionMode = true },
                                enabled = landmarks.isNotEmpty()
                            ) { Text("Select", fontWeight = FontWeight.Bold) }
                        }
                    }
                )
                // Search Bar
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search labels or promotion titles") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        unfocusedBorderColor = Color.LightGray,
                        focusedBorderColor = brandBlue
                    )
                )
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                Surface(color = Color.White.copy(alpha = 0.95f), shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(selectionCountText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(if (hiddenSelectedCount > 0) "$hiddenSelectedCount hidden by search" else "Selection stays active while searching", fontSize = 12.sp, color = Color.Gray)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selectedLandmarkIds = emptySet() }, enabled = selectedLandmarkIds.isNotEmpty()) { Text("Clear", color = Color.Red, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { bulkPromotionSelection = landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) } },
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = brandBlue),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Add Promotion", fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = { bulkDeleteSelection = landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) } },
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.12f), contentColor = Color.Red),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Delete", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(bgGray).padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // ACTIVE LANDMARKS HEADER
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ACTIVE LANDMARKS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            if (landmarks.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(if (cleanedSearchText.isEmpty()) "(${landmarks.size})" else "(${displayedLandmarks.size} of ${landmarks.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                            Spacer(Modifier.weight(1f))
                            if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = brandBlue, strokeWidth = 2.dp)
                            }
                        }

                        if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                            Text("Checking promotion titles...", fontSize = 12.sp, color = Color.Gray)
                        }

                        // Selection Summary Card
                        if (isSelectionMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth().background(brandBlue.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (selectedLandmarkIds.isEmpty()) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle, contentDescription = null, tint = brandBlue, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(selectionCountText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    val subText = if (hiddenSelectedCount > 0) "$hiddenSelectedCount selected hidden by current search" else if (cleanedSearchText.isNotEmpty()) "$visibleSelectedCount selected in these results" else "Search without losing selection."
                                    Text(subText, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }

                // CONTENT
                if (isLoading && landmarks.isEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(color = brandBlue)
                            Text("Loading your landmarks...", fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                        }
                    }
                } else if (landmarks.isEmpty()) {
                    item { Text("No active business landmarks.", fontSize = 15.sp, color = Color.Gray, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp)) }
                } else if (displayedLandmarks.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(vertical = 28.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                            Text("No landmarks found", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Try a landmark label or promotion title.", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                } else {
                    val actionNeeded = displayedLandmarks.filter { it.status == "NEEDS_MORE_MEDIA" }
                    val healthy = displayedLandmarks.filter { it.status != "NEEDS_MORE_MEDIA" }

                    if (actionNeeded.isNotEmpty()) {
                        item { Text("NEEDS ATTENTION", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Red, modifier = Modifier.padding(start = 4.dp)) }
                        items(actionNeeded) { lm ->
                            BusinessLandmarkRow(
                                landmark = lm,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedLandmarkIds.contains(lm.landmarkId),
                                brandBlue = brandBlue,
                                brandOrange = brandOrange,
                                onToggleSelect = { toggleSelection(lm.landmarkId) },
                                onClick = { onNavigateToDetail(lm) },
                                onNeedsMediaClick = { landmarkNeedingMedia = lm }
                            )
                        }
                    }

                    if (healthy.isNotEmpty()) {
                        if (actionNeeded.isNotEmpty()) {
                            item { Text("ACTIVE LANDMARKS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, top = 16.dp)) }
                        }
                        items(healthy) { lm ->
                            BusinessLandmarkRow(
                                landmark = lm,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedLandmarkIds.contains(lm.landmarkId),
                                brandBlue = brandBlue,
                                brandOrange = brandOrange,
                                onToggleSelect = { toggleSelection(lm.landmarkId) },
                                onClick = { onNavigateToDetail(lm) },
                                onNeedsMediaClick = { landmarkNeedingMedia = lm }
                            )
                        }
                    }
                }

                // PENDING UPLOADS
                item {
                    if (archivedItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("PENDING UPLOADS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                            Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp) {
                                Column {
                                    // Sync Banner
                                    val isUploading = currentlyUploadingId != null
                                    val isOffline = !isNetworkConnected
                                    Row(modifier = Modifier.fillMaxWidth().background(if (isUploading) brandBlue.copy(alpha = 0.05f) else Color.White).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (isUploading) Icons.Default.CloudUpload else if (isOffline) Icons.Default.CloudOff else Icons.Default.PauseCircle, contentDescription = null, tint = if (isUploading) brandBlue else Color.Gray, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(if (isUploading) "Syncing to Cloud..." else if (isOffline) "Waiting for Connection" else "Queue Processing...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                            Text("${archivedItems.size} items waiting to upload", fontSize = 13.sp, color = Color.Gray)
                                        }
                                        if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = brandBlue, strokeWidth = 2.dp)
                                    }
                                    HorizontalDivider()

                                    // Upload Items
                                    archivedItems.forEachIndexed { index, item ->
                                        val itemUploading = currentlyUploadingId == item.id
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable(enabled = !itemUploading) { onNavigateToRecord(landmarks.firstOrNull { it.landmarkId == item.landmarkId } ?: BusinessLandmark(item.landmarkId, "Unknown", null, null, null, null, null, null, null, null, null, null), item) }.padding(horizontal = 20.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.size(48.dp).background(Color(0xFFF2F2F7), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                                Icon(if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = Color.Gray)
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                if (itemUploading) {
                                                    Text("Uploading...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = brandBlue)
                                                    LinearProgressIndicator(
                                                        progress = { currentUploadProgress },
                                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                                        color = brandBlue,
                                                        trackColor = brandBlue.copy(alpha = 0.2f)
                                                    )
                                                } else {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(10.dp), tint = brandOrange)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Queued", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = brandOrange)
                                                    }
                                                }
                                            }
                                            if (!itemUploading) {
                                                IconButton(onClick = { offlineManager.deleteArchive(item) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.background(Color.Red.copy(alpha = 0.1f), CircleShape).padding(6.dp).size(16.dp))
                                                }
                                            }
                                        }
                                        if (index < archivedItems.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        // Empty Queue
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 2.dp) {
                            Column(modifier = Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(modifier = Modifier.size(70.dp).background(Color.Green.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(32.dp))
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("All Caught Up!", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("There is no media waiting in the queue.\nEverything is securely synced to LookSee.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Modals & Bottom Sheets
        if (landmarkNeedingMedia != null) {
            val lm = landmarkNeedingMedia!!
            Dialog(onDismissRequest = { landmarkNeedingMedia = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.size(80.dp).background(Color.Red.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(36.dp))
                        }
                        Text("More Media Required", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("We couldn't extract enough unique frames of ${lm.label} to train a reliable model.", fontSize = 15.sp, textAlign = TextAlign.Center, color = Color.Gray)

                        HorizontalDivider()

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text("Frames Extracted", fontSize = 12.sp, color = Color.Gray)
                                Text("0 / 1800", fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Target Video Needed", fontSize = 12.sp, color = Color.Gray)
                                Text("~30 Seconds", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                            }
                        }

                        HorizontalDivider()
                        Text("Capture about 30 more seconds of video capturing your landmark. Once uploaded, training will resume automatically. This will not cost a token.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)

                        Button(
                            onClick = {
                                val clickedLm = landmarkNeedingMedia
                                landmarkNeedingMedia = null
                                if (clickedLm != null) onNavigateToRecord(clickedLm, null)
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Add Media Now", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// MARK: - Reusable Row Components

@Composable
fun BusinessLandmarkRow(
    landmark: BusinessLandmark,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    brandBlue: Color,
    brandOrange: Color,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onNeedsMediaClick: () -> Unit
) {
    val needsMoreMedia = landmark.status == "NEEDS_MORE_MEDIA"

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { if (isSelectionMode) onToggleSelect() else onClick() },
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) brandBlue.copy(alpha = 0.1f) else Color.White,
        border = if (needsMoreMedia) androidx.compose.foundation.BorderStroke(1.5.dp, Color.Red.copy(alpha = 0.6f)) else if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, brandBlue.copy(alpha = 0.6f)) else null,
        shadowElevation = if (needsMoreMedia) 4.dp else 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) brandBlue else Color.Gray,
                        modifier = Modifier.size(24.dp).padding(top = 2.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        if (needsMoreMedia) {
                            Text("ACTION NEEDED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Red, modifier = Modifier.background(Color.Red.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
                        } else {
                            val isActive = landmark.isActive ?: true
                            Text(if (isActive) "ACTIVE" else "INACTIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isActive) Color(0xFF34C759) else Color.Gray, modifier = Modifier.background(if (isActive) Color(0xFF34C759).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }

                    if (!landmark.shortDescription.isNullOrEmpty()) {
                        Text(landmark.shortDescription, fontSize = 14.sp, color = Color.Gray, maxLines = 2)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (landmark.promotionEnabled == true) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(brandOrange.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = brandOrange, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Promotions On", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = brandOrange)
                            }
                        }

                        if (landmark.latitude != null && landmark.longitude != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(String.format(Locale.getDefault(), "%.4f, %.4f", landmark.latitude, landmark.longitude), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            if (needsMoreMedia) {
                Button(
                    onClick = { onNeedsMediaClick() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f), contentColor = Color.Red),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Not enough video data to train", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("Details", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// =========================================================================
// MOCKS FOR MISSING FILES (Keeps compilation working)
// =========================================================================

class BusinessLandmarksViewModel {
    val isLoading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val landmarks = kotlinx.coroutines.flow.MutableStateFlow<List<BusinessLandmark>>(emptyList())
    fun loadLandmarks() {}
    fun refresh() {}
}
class OfflineMediaManager { val archivedItems = kotlinx.coroutines.flow.MutableStateFlow<List<ArchivedMedia>>(emptyList()); fun deleteArchive(_m: ArchivedMedia) {}; companion object { val shared = OfflineMediaManager() } }
class AutoUploadManager { val currentlyUploadingId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null); val currentUploadProgress = kotlinx.coroutines.flow.MutableStateFlow(0f); companion object { val shared = AutoUploadManager() } }
class NetworkMonitor { val isConnected = kotlinx.coroutines.flow.MutableStateFlow(true); companion object { val shared = NetworkMonitor() } }