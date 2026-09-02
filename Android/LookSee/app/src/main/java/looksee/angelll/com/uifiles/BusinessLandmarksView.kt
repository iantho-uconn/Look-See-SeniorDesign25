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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.LookSeeCard
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

    val PrimaryBlue = looksee.angelll.com.ui.theme.LookSeeBlue
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
    val processingLandmarks = displayedLandmarks.filter { it.isProcessing }
    val activeLandmarks = displayedLandmarks.filter { it.status != "NEEDS_MORE_MEDIA" && !it.isProcessing }

    val visibleLandmarkIds = displayedLandmarks.map { it.landmarkId }.toSet()
    val selectedLandmarks = landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) }
    val visibleSelectedCount = selectedLandmarkIds.intersect(visibleLandmarkIds).size
    val hiddenSelectedCount = selectedLandmarkIds.subtract(visibleLandmarkIds).size
    val selectionCountText = "${selectedLandmarks.size} landmark${if (selectedLandmarks.size == 1) "" else "s"} selected"

    fun activeLandmarkCountText(count: Int): String {
        return if (cleanedSearchText.isNotEmpty()) "($count)" else "($count of ${landmarks.count { it.status != "NEEDS_MORE_MEDIA" && !it.isProcessing }})"
    }

    fun refreshLandmarksAndSearchIndex() {
        coroutineScope.launch {
            viewModel.refresh()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("CheckGlobalNotifications"))
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLoadedOnce) {
            hasLoadedOnce = true
            viewModel.loadLandmarks()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("CheckGlobalNotifications"))
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text("My Landmarks", fontWeight = FontWeight.Bold, color = Color.White) 
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    IconButton(onClick = { onNavigate("back", null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        TextButton(onClick = { isSelectionMode = false; selectedLandmarkIds = emptySet() }) { 
                            Text("Done", color = PrimaryBlue, fontWeight = FontWeight.Bold) 
                        }
                    } else {
                        IconButton(onClick = { refreshLandmarksAndSearchIndex() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = PrimaryBlue)
                        }
                        TextButton(onClick = { isSelectionMode = true }, enabled = landmarks.isNotEmpty()) {
                            Text("Select", color = if (landmarks.isNotEmpty()) PrimaryBlue else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                },
            )
        },

        bottomBar = {
            if (isSelectionMode) {
                Surface(color = SecondaryGrouped.copy(alpha = 0.95f), shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(selectionCountText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(if (hiddenSelectedCount > 0) "$hiddenSelectedCount hidden" else "Ready for bulk actions", color = Color.Gray, fontSize = 12.sp)
                            }
                            TextButton(onClick = { selectedLandmarkIds = emptySet() }) {
                                Text("Clear", color = if (selectedLandmarkIds.isNotEmpty()) Color.Red else Color.Gray, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { showBulkPromotionSheet = true }, modifier = Modifier.weight(1f).height(48.dp), enabled = selectedLandmarkIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) { Text("Promotion", fontWeight = FontWeight.Bold) }
                            Button(onClick = { showBulkDeleteSheet = true }, modifier = Modifier.weight(1f).height(48.dp), enabled = selectedLandmarkIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.12f), contentColor = Color.Red)) { Text("Delete", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refreshLandmarksAndSearchIndex() },
            modifier = Modifier.fillMaxSize().padding(paddingValues).clickable { focusManager.clearFocus() }
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // Pending Uploads (Integrated Upload Queue)
                if (archivedItems.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("PENDING UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
                            LookSeeCard {
                                archivedItems.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier.clickable { onNavigate("LandmarkRecord", item) }.padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Box(modifier = Modifier.size(48.dp).background(Color.White.copy(0.05f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                            Icon(if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            if (currentlyUploadingId == item.id) {
                                                LinearProgressIndicator(progress = { currentUploadProgress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp), color = PrimaryBlue)
                                            } else {
                                                Text("Queued", fontSize = 12.sp, color = Color(0xFFFFA500))
                                            }
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                    if (index < archivedItems.size - 1) HorizontalDivider(color = Color.White.copy(0.05f), modifier = Modifier.padding(start = 64.dp))
                                }
                            }
                        }
                    }
                } else if (landmarks.isNotEmpty()) {
                    item { EmptyQueueCard() }
                }

                // Search Bar
                item {
                    OutlinedTextField(
                        value = searchText, onValueChange = { searchText = it },
                        placeholder = { Text("Search labels", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = SecondaryGrouped, unfocusedContainerColor = SecondaryGrouped, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }

                // Action Needed Section
                if (actionNeededLandmarks.isNotEmpty()) {
                    item { Text("NEEDS ATTENTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF453A), modifier = Modifier.padding(horizontal = 20.dp)) }
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

                // Processing & Training Section
                if (processingLandmarks.isNotEmpty()) {
                    item { Text("PROCESSING & TRAINING", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500), modifier = Modifier.padding(horizontal = 20.dp)) }
                    items(processingLandmarks) { landmark ->
                        BusinessLandmarkRowWrapper(landmark, isSelectionMode, selectedLandmarkIds.contains(landmark.landmarkId), null, onNavigate) {
                            if (isSelectionMode) {
                                val current = selectedLandmarkIds.toMutableSet()
                                if (current.contains(landmark.landmarkId)) current.remove(landmark.landmarkId) else current.add(landmark.landmarkId)
                                selectedLandmarkIds = current
                            }
                        }
                    }
                }

                // Active Section
                item { Text("ACTIVE LANDMARKS ${activeLandmarkCountText(activeLandmarks.size)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp)) }
                items(activeLandmarks) { landmark ->
                    BusinessLandmarkRowWrapper(landmark, isSelectionMode, selectedLandmarkIds.contains(landmark.landmarkId), null, onNavigate) {
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
    val SecondaryGrouped = Color(0xFF1C1C1E)

    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp).clickable {
        if (isSelectionMode || needsMoreMedia) onClick(landmark)
        else onNavigate("BusinessLandmarkDetailView", landmark)
    }) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (isSelectionMode) {
                Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle, contentDescription = null, tint = looksee.angelll.com.ui.theme.LookSeeBlue, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(landmark.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    
                    val badgeColor = when (landmark.status) {
                        "NEEDS_MORE_MEDIA" -> Color(0xFFFF453A)
                        "PREPARING_DATA" -> Color(0xFFFF9F0A)
                        "TRAINING_MODEL" -> Color(0xFFFFD60A)
                        "OPTIMIZING_MODEL" -> Color(0xFF64D2FF)
                        else -> if (landmark.isActive == false) Color.Gray else Color(0xFF32D74B)
                    }
                    val badgeBg = when (landmark.status) {
                        "NEEDS_MORE_MEDIA" -> Color(0xFF2C0E0E)
                        "PREPARING_DATA" -> Color(0xFF2C1E0E)
                        "TRAINING_MODEL" -> Color(0xFF2C280E)
                        "OPTIMIZING_MODEL" -> Color(0xFF0E222C)
                        else -> if (landmark.isActive == false) Color(0xFF1C1C1E) else Color(0xFF0E2C14)
                    }

                    Text(
                        landmark.displayStatus.uppercase(),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier
                            .background(badgeBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(landmark.displayDescription, fontSize = 14.sp, color = Color.Gray, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                        Text("${landmark.cleanFrameCount ?: 0} Frames", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun EmptyQueueCard() {
    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(70.dp).background(Color(0xFF32D74B).copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = Color(0xFF32D74B),
                    modifier = Modifier.size(32.dp)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "All Caught Up!",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "There is no media waiting in the queue.\nEverything is securely synced to LookSee.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsMoreMediaSheet(landmark: BusinessLandmark, onDismiss: () -> Unit, onAddMedia: (BusinessLandmark) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1E)) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(modifier = Modifier.size(80.dp).background(Color.Red.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(36.dp))
            }
            Text("More Media Required", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("We couldn't extract enough unique frames of ${landmark.label} to train a reliable model.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
            Button(
                onClick = { onAddMedia(landmark) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Add Media Now", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
