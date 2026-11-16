package looksee.angelll.com.uifiles

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import looksee.angelll.com.models.OfflineMediaManager as ArchiveManager
import looksee.angelll.com.models.AutoUploadManager as Uploader
import looksee.angelll.com.detection.NetworkMonitor as Monitor
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.ui.theme.AppleBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessLandmarksView(
    vm: AuthViewModel,
    onNavigate: (String, Any?) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val primaryBlue = looksee.angelll.com.ui.theme.LookSeeBlue
    val secondaryGrouped = Color(0xFF1C1C1E)

    val viewModel = remember { BusinessLandmarksViewModel() }
    val offlineManager = remember { ArchiveManager.shared(context) }
    val uploadManager = remember { Uploader.shared(context) }
    val networkMonitor = remember { Monitor.getInstance(context) }
    val promotionService = remember { BusinessPromotionService() }

    var searchText by remember { mutableStateOf("") }
    var promotionTitlesIndex by remember { mutableStateOf(PromotionTitlesIndex()) }
    var isIndexingPromotionTitles by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var selectedLandmarkIds by remember { mutableStateOf(emptySet<String>()) }
    var showBulkPromotionSheet by remember { mutableStateOf(false) }
    var showBulkDeleteSheet by remember { mutableStateOf(false) }

    val initialNullLandmark: BusinessLandmark? = null
    val initialNullDraft: ArchivedMedia? = null
    var landmarkNeedingMedia by remember { mutableStateOf(initialNullLandmark) }
    var redoRecordLandmark by remember { mutableStateOf(initialNullLandmark) }
    var draftToEdit by remember { mutableStateOf(initialNullDraft) }

    var hasLoadedOnce by remember { mutableStateOf(false) }

    val archivedItems by offlineManager.archivedItems.collectAsState()
    val isOnline by networkMonitor.isConnected.collectAsState()
    val autoUploadState by uploadManager.state.collectAsState()
    val currentlyUploadingId = autoUploadState.currentlyUploadingId
    val currentUploadProgress = autoUploadState.currentUploadProgress

    val landmarks by viewModel.landmarks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val processingTrackerState by BusinessProcessingTracker.state.collectAsState()

    val cleanedSearchText = searchText.trim()

    // Reconcile "Processing New Data" whenever landmarks list updates from backend
    LaunchedEffect(landmarks) {
        BusinessProcessingTracker.reconcileWithBackend(landmarks)
        val validIds = landmarks.map { it.landmarkId }.toSet()
        selectedLandmarkIds = selectedLandmarkIds.intersect(validIds)
    }

    // Index promotion titles in the background so search matches both labels and promotion titles
    LaunchedEffect(landmarks.map { it.landmarkId }) {
        val missingIds = landmarks.map { it.landmarkId }.filter { !promotionTitlesIndex.containsLandmark(it) }
        if (missingIds.isEmpty()) return@LaunchedEffect
        isIndexingPromotionTitles = true
        var updatedIndex = promotionTitlesIndex
        for (id in missingIds) {
            runCatching {
                val promos = promotionService.fetchPromotions(id).items
                val names = promos.mapNotNull { promo -> promo.name.trim().takeIf { it.isNotEmpty() } }
                updatedIndex = updatedIndex.withTitles(id, names)
            }
        }
        promotionTitlesIndex = updatedIndex
        isIndexingPromotionTitles = false
    }

    fun searchablePromotionTitles(landmark: BusinessLandmark): List<String> {
        val baseTitles = promotionTitlesIndex.titlesFor(landmark.landmarkId)
        val legacy = landmark.promotion?.trim() ?: ""
        return if (legacy.isNotEmpty() && baseTitles.none { it.equals(legacy, ignoreCase = true) }) {
            baseTitles + legacy
        } else {
            baseTitles
        }
    }

    fun matchedPromotionTitle(landmark: BusinessLandmark): String? {
        if (cleanedSearchText.isEmpty() || landmark.label.contains(cleanedSearchText, ignoreCase = true)) return null
        return searchablePromotionTitles(landmark).firstOrNull { it.contains(cleanedSearchText, ignoreCase = true) }
    }

    fun isProcessingNewData(landmark: BusinessLandmark): Boolean {
        return BusinessProcessingTracker.isLandmarkProcessingNewData(landmark, processingTrackerState)
    }

    val displayedLandmarks: List<BusinessLandmark> = remember(landmarks, cleanedSearchText, promotionTitlesIndex) {
        if (cleanedSearchText.isEmpty()) {
            landmarks
        } else {
            val labelMatches = landmarks.filter {
                it.label.contains(cleanedSearchText, ignoreCase = true)
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }

            val promoMatches = landmarks.filter { landmark ->
                !landmark.label.contains(cleanedSearchText, ignoreCase = true) &&
                        searchablePromotionTitles(landmark).any { title ->
                            title.contains(cleanedSearchText, ignoreCase = true)
                        }
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }

            labelMatches + promoMatches
        }
    }

    val pipelineStates = remember {
        setOf("NEEDS_MORE_MEDIA", "PREPARING_DATA", "PENDING_TRAINING", "TRAINING_MODEL", "OPTIMIZING_MODEL")
    }

    // Granular sections matching iOS ordering
    val processingNewDataLandmarks = displayedLandmarks.filter { isProcessingNewData(it) }
    val actionNeededLandmarks = displayedLandmarks.filter { it.status == "NEEDS_MORE_MEDIA" && !isProcessingNewData(it) }
    val preparingDataLandmarks = displayedLandmarks.filter { it.status == "PREPARING_DATA" && !isProcessingNewData(it) }
    val pendingTrainingLandmarks = displayedLandmarks.filter { it.status == "PENDING_TRAINING" && !isProcessingNewData(it) }
    val trainingModelLandmarks = displayedLandmarks.filter { it.status == "TRAINING_MODEL" && !isProcessingNewData(it) }
    val optimizingModelLandmarks = displayedLandmarks.filter { it.status == "OPTIMIZING_MODEL" && !isProcessingNewData(it) }
    val activeLandmarks = displayedLandmarks.filter {
        !pipelineStates.contains(it.status ?: "") && !it.isProcessing && !isProcessingNewData(it)
    }

    val visibleLandmarkIds = displayedLandmarks.map { it.landmarkId }.toSet()
    val selectedLandmarks = landmarks.filter { selectedLandmarkIds.contains(it.landmarkId) }
    val visibleSelectedCount = selectedLandmarkIds.intersect(visibleLandmarkIds).size
    val hiddenSelectedCount = selectedLandmarkIds.subtract(visibleLandmarkIds).size
    val selectionCountText = "${selectedLandmarks.size} landmark${if (selectedLandmarks.size == 1) "" else "s"} selected"

    fun activeLandmarkCountText(activeCount: Int): String {
        if (cleanedSearchText.isEmpty()) return "($activeCount)"
        val totalActive = landmarks.count {
            !pipelineStates.contains(it.status ?: "") && !it.isProcessing && !isProcessingNewData(it)
        }
        return "($activeCount of $totalActive)"
    }

    fun refreshLandmarksAndSearchIndex() {
        coroutineScope.launch {
            viewModel.refresh()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("CheckGlobalNotifications"))
        }
    }

    fun previewLandmarkPopup(landmark: BusinessLandmark) {
        VariableContainer.shared.presentNearbyLandmark(
            NearbyLandmark(
                landmarkId = landmark.landmarkId,
                label = landmark.label,
                shortDescription = landmark.shortDescription ?: "",
                latitude = landmark.latitude ?: 0.0,
                longitude = landmark.longitude ?: 0.0,
                promotionEnabled = landmark.promotionEnabled ?: false,
                promotion = landmark.promotion,
                createdBy = landmark.ownerUserId,
                websiteUrl = landmark.websiteUrl,
                isActive = landmark.isActive ?: true,
                isVisibleOnMap = true
            )
        )
    }

    // Dynamic auto-refresh: 15s when a landmark is in "Processing New Data", 90s otherwise
    LaunchedEffect(processingTrackerState.isNotEmpty) {
        while (true) {
            val sleepMillis = if (processingTrackerState.isNotEmpty) 15_000L else 90_000L
            delay(sleepMillis)
            if (!viewModel.isLoading.value) {
                refreshLandmarksAndSearchIndex()
            }
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
                title = { Text("My Landmarks", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    IconButton(onClick = { onNavigate("back", null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        Box {
                            IconButton(onClick = { showSelectionMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Selection actions", tint = primaryBlue)
                            }
                            DropdownMenu(
                                expanded = showSelectionMenu,
                                onDismissRequest = { showSelectionMenu = false },
                                containerColor = secondaryGrouped
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select Visible (${displayedLandmarks.size})", color = Color.White) },
                                    onClick = {
                                        showSelectionMenu = false
                                        selectedLandmarkIds = selectedLandmarkIds.union(visibleLandmarkIds)
                                    },
                                    enabled = displayedLandmarks.isNotEmpty()
                                )
                                DropdownMenuItem(
                                    text = { Text("Deselect Visible ($visibleSelectedCount)", color = Color.White) },
                                    onClick = {
                                        showSelectionMenu = false
                                        selectedLandmarkIds = selectedLandmarkIds.subtract(visibleLandmarkIds)
                                    },
                                    enabled = visibleSelectedCount > 0
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                DropdownMenuItem(
                                    text = { Text("Clear All Selection", color = Color(0xFFFF453A)) },
                                    onClick = {
                                        showSelectionMenu = false
                                        selectedLandmarkIds = emptySet()
                                    },
                                    enabled = selectedLandmarkIds.isNotEmpty()
                                )
                            }
                        }
                        TextButton(onClick = { isSelectionMode = false; selectedLandmarkIds = emptySet() }) {
                            Text("Done", color = primaryBlue, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        IconButton(onClick = { refreshLandmarksAndSearchIndex() }, enabled = !isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = primaryBlue)
                        }
                        TextButton(onClick = { isSelectionMode = true }, enabled = landmarks.isNotEmpty()) {
                            Text("Select", color = if (landmarks.isNotEmpty()) primaryBlue else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isSelectionMode) {
                Surface(color = secondaryGrouped.copy(alpha = 0.96f), shadowElevation = 8.dp) {
                    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(selectionCountText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    if (hiddenSelectedCount > 0) "$hiddenSelectedCount hidden by search"
                                    else "Selection stays active while searching",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { selectedLandmarkIds = emptySet() }, enabled = selectedLandmarkIds.isNotEmpty()) {
                                Text("Clear", color = if (selectedLandmarkIds.isNotEmpty()) Color(0xFFFF453A) else Color.Gray, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { showBulkPromotionSheet = true },
                                modifier = Modifier.weight(1f).height(46.dp),
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                            ) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Promotion", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Button(
                                onClick = { showBulkDeleteSheet = true },
                                modifier = Modifier.weight(1f).height(46.dp),
                                enabled = selectedLandmarkIds.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.12f), contentColor = Color.Red)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Delete", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
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
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
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
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.Gray)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = secondaryGrouped,
                            unfocusedContainerColor = secondaryGrouped,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }

                // Pending Uploads Card or Empty Queue Card
                if (archivedItems.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("PENDING UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
                            LookSeeCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    SyncBannerRow(
                                        isUploading = currentlyUploadingId != null,
                                        isOffline = !isOnline,
                                        itemCount = archivedItems.size,
                                        primaryColor = primaryBlue
                                    )
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                    archivedItems.forEachIndexed { index: Int, item: ArchivedMedia ->
                                        PendingRow(
                                            item = item,
                                            isUploading = currentlyUploadingId == item.id,
                                            progress = currentUploadProgress,
                                            primaryColor = primaryBlue,
                                            onNavigate = { draftToEdit = item },
                                            onDelete = { coroutineScope.launch { offlineManager.deleteArchive(item) } }
                                        )
                                        if (index < archivedItems.size - 1) {
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(start = 64.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (landmarks.isNotEmpty()) {
                    item { EmptyQueueCard() }
                }

                // 1. PROCESSING NEW DATA (Pinned to the very top)
                if (processingNewDataLandmarks.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("PROCESSING NEW DATA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64D2FF))
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF64D2FF), strokeWidth = 2.dp)
                        }
                    }
                    items(processingNewDataLandmarks, key = { "proc_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = true,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = { landmarkNeedingMedia = landmark }
                        )
                    }
                }

                // 2. NEEDS ATTENTION
                if (actionNeededLandmarks.isNotEmpty()) {
                    item {
                        Text("NEEDS ATTENTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF453A), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(actionNeededLandmarks, key = { "attn_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = { landmarkNeedingMedia = landmark }
                        )
                    }
                }

                // 3. PREPARING DATA
                if (preparingDataLandmarks.isNotEmpty()) {
                    item {
                        Text("PREPARING DATA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9F0A), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(preparingDataLandmarks, key = { "prep_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = {}
                        )
                    }
                }

                // 4. WAITING FOR TRAINING
                if (pendingTrainingLandmarks.isNotEmpty()) {
                    item {
                        Text("WAITING FOR TRAINING", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAF52DE), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(pendingTrainingLandmarks, key = { "pend_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = {}
                        )
                    }
                }

                // 5. IN TRAINING
                if (trainingModelLandmarks.isNotEmpty()) {
                    item {
                        Text("IN TRAINING", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD60A), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(trainingModelLandmarks, key = { "train_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = {}
                        )
                    }
                }

                // 6. OPTIMIZING FOR ANDROID
                if (optimizingModelLandmarks.isNotEmpty()) {
                    item {
                        Text("OPTIMIZING FOR ANDROID", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0A84FF), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    items(optimizingModelLandmarks, key = { "opt_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = {}
                        )
                    }
                }

                // 7. ACTIVE LANDMARKS HEADER + SELECTION SUMMARY
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ACTIVE LANDMARKS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF32D74B))
                            if (landmarks.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(activeLandmarkCountText(activeLandmarks.size), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                            Spacer(Modifier.weight(1f))
                            if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = primaryBlue, strokeWidth = 2.dp)
                            }
                        }

                        if (isIndexingPromotionTitles && cleanedSearchText.isNotEmpty()) {
                            Text(
                                "Checking promotion titles...",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }

                        if (isSelectionMode) {
                            SelectionSummaryCard(
                                selectionCountText = selectionCountText,
                                isEmpty = selectedLandmarkIds.isEmpty(),
                                hiddenSelectedCount = hiddenSelectedCount,
                                visibleSelectedCount = visibleSelectedCount,
                                hasSearchQuery = cleanedSearchText.isNotEmpty(),
                                primaryColor = primaryBlue
                            )
                        }
                    }
                }

                if (isLoading && landmarks.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(color = primaryBlue)
                            Text("Loading your landmarks...", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                } else if (landmarks.isEmpty()) {
                    item {
                        Text(
                            "No active business landmarks.",
                            fontSize = 15.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                } else if (displayedLandmarks.isEmpty()) {
                    item {
                        NoSearchResultsCard()
                    }
                } else {
                    items(activeLandmarks, key = { "active_${it.landmarkId}" }) { landmark: BusinessLandmark ->
                        BusinessLandmarkRowWrapper(
                            landmark = landmark,
                            isProcessingNewData = false,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedLandmarkIds.contains(landmark.landmarkId),
                            matchedPromotionTitle = matchedPromotionTitle(landmark),
                            onCardClick = {
                                if (isSelectionMode) {
                                    selectedLandmarkIds = if (selectedLandmarkIds.contains(landmark.landmarkId))
                                        selectedLandmarkIds - landmark.landmarkId else selectedLandmarkIds + landmark.landmarkId
                                } else {
                                    onNavigate("BusinessLandmarkDetailView", landmark)
                                }
                            },
                            onPreviewClick = { previewLandmarkPopup(landmark) },
                            onDetailsClick = {}
                        )
                    }
                }
            }
        }
    }

    val currentNeedingMedia = landmarkNeedingMedia
    if (currentNeedingMedia != null) {
        NeedsMoreMediaSheet(
            landmark = currentNeedingMedia,
            onDismiss = { landmarkNeedingMedia = null },
            onForceTrain = {
                coroutineScope.launch {
                    val success = vm.forceTrainLandmark(currentNeedingMedia.landmarkId)
                    if (success) { viewModel.refresh() }
                    landmarkNeedingMedia = null
                }
            },
            onAddMedia = { selectedLandmark ->
                landmarkNeedingMedia = null
                redoRecordLandmark = selectedLandmark
            }
        )
    }

    val currentDraftToEdit = draftToEdit
    if (currentDraftToEdit != null) {
        Dialog(
            onDismissRequest = { draftToEdit = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LandmarkRecordScreen(
                vm = vm,
                archivedMedia = currentDraftToEdit,
                onDismiss = { draftToEdit = null }
            )
        }
    }

    val currentRedoLandmark = redoRecordLandmark
    if (currentRedoLandmark != null) {
        val processed = currentRedoLandmark.cleanFrameCount ?: 0
        val required = currentRedoLandmark.requiredFrames ?: 1500
        val calcSecs = currentRedoLandmark.secondsNeeded ?: ceil(max(0, required - processed) / 30.0).toInt()

        Dialog(
            onDismissRequest = { redoRecordLandmark = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LandmarkRecordScreen(
                vm = vm,
                existingLandmarkId = currentRedoLandmark.landmarkId,
                existingLabel = currentRedoLandmark.label,
                existingDescription = currentRedoLandmark.shortDescription,
                existingSecondsNeeded = calcSecs.toDouble(),
                onDismiss = {
                    BusinessProcessingTracker.markProcessingNewData(currentRedoLandmark)
                    redoRecordLandmark = null
                    refreshLandmarksAndSearchIndex()
                }
            )
        }
    }

    if (showBulkPromotionSheet) {
        Dialog(onDismissRequest = { showBulkPromotionSheet = false }) {
            BusinessBulkPromotionEditor(
                selectedLandmarks,
                onCompleted = {
                    showBulkPromotionSheet = false
                    isSelectionMode = false
                    selectedLandmarkIds = emptySet()
                    refreshLandmarksAndSearchIndex()
                },
                onDismiss = { showBulkPromotionSheet = false }
            )
        }
    }
    if (showBulkDeleteSheet) {
        Dialog(onDismissRequest = { showBulkDeleteSheet = false }) {
            BusinessBulkDeleteView(
                selectedLandmarks,
                onCompleted = {
                    showBulkDeleteSheet = false
                    isSelectionMode = false
                    selectedLandmarkIds = emptySet()
                    refreshLandmarksAndSearchIndex()
                },
                onDismiss = { showBulkDeleteSheet = false }
            )
        }
    }
}

@Composable
private fun BusinessLandmarkRowWrapper(
    landmark: BusinessLandmark,
    isProcessingNewData: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    matchedPromotionTitle: String?,
    onCardClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    val needsMoreMedia = (landmark.status == "NEEDS_MORE_MEDIA") && !isProcessingNewData
    val selectionColor = AppleBlue
    val cardBg = if (isSelected) selectionColor.copy(alpha = 0.12f) else Color(0xFF1C1C1E)

    val badgeColor = when {
        isProcessingNewData -> Color(0xFF64D2FF)
        landmark.status == "NEEDS_MORE_MEDIA" -> Color(0xFFFF453A)
        landmark.status == "PREPARING_DATA" -> Color(0xFFFF9F0A)
        landmark.status == "PENDING_TRAINING" -> Color(0xFFAF52DE)
        landmark.status == "TRAINING_MODEL" -> Color(0xFFFFD60A)
        landmark.status == "OPTIMIZING_MODEL" -> Color(0xFF0A84FF)
        landmark.isActive == false -> Color.Gray
        else -> Color(0xFF32D74B)
    }

    val badgeText = when {
        isProcessingNewData -> "PROCESSING NEW DATA"
        landmark.status == "NEEDS_MORE_MEDIA" -> "NEEDS MEDIA"
        landmark.status in setOf("PREPARING_DATA", "PENDING_TRAINING", "TRAINING_MODEL", "OPTIMIZING_MODEL") ->
            landmark.displayStatus.uppercase()
        landmark.isActive == false -> "INACTIVE"
        else -> "ACTIVE"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .then(
                when {
                    needsMoreMedia -> Modifier.border(1.5.dp, Color(0xFFFF453A).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    isSelected -> Modifier.border(1.5.dp, selectionColor.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    else -> Modifier
                }
            )
            .clickable { onCardClick() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) selectionColor else Color.Gray,
                        modifier = Modifier.size(24.dp).padding(top = 2.dp)
                    )
                }

                // Left Info Column
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = landmark.label.ifEmpty { "Untitled Landmark" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = landmark.displayDescription,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!matchedPromotionTitle.isNullOrEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(13.dp))
                            Text(
                                text = "Matched promotion: $matchedPromotionTitle",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9F0A),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (landmark.promotionEnabled == true) {
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFFFF9F0A).copy(alpha = 0.15f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(11.dp))
                                Text("Promotions On", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9F0A))
                            }
                        }

                        if (landmark.latitude != null && landmark.longitude != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                                Text(
                                    String.format(Locale.US, "%.4f, %.4f", landmark.latitude, landmark.longitude),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }

                // Right Badge & Preview Button Column
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )

                    if (!isSelectionMode) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(selectionColor.copy(alpha = 0.12f))
                                .clickable { onPreviewClick() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = "Preview", tint = selectionColor, modifier = Modifier.size(14.dp))
                            Text("Preview", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = selectionColor)
                        }
                    }
                }
            }

            if (needsMoreMedia) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF453A).copy(alpha = 0.20f))
                        .clickable { onDetailsClick() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Flag, contentDescription = null, tint = Color(0xFFFF453A), modifier = Modifier.size(14.dp))
                        Text(
                            "Not enough video data to train",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF453A)
                        )
                    }
                    Text(
                        "Details",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF453A),
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionSummaryCard(
    selectionCountText: String,
    isEmpty: Boolean,
    hiddenSelectedCount: Int,
    visibleSelectedCount: Int,
    hasSearchQuery: Boolean,
    primaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(primaryColor.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .border(1.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isEmpty) Icons.Outlined.Circle else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = primaryColor,
            modifier = Modifier.size(22.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(selectionCountText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            val subtitle = when {
                hiddenSelectedCount > 0 -> "$hiddenSelectedCount selected landmark${if (hiddenSelectedCount == 1) "" else "s"} hidden by the current search"
                hasSearchQuery -> "$visibleSelectedCount selected in these search results"
                else -> "Search for more landmarks without losing this selection."
            }
            Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun NoSearchResultsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp))
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
        Text("No landmarks found", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Try a landmark label or promotion title.",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SyncBannerRow(isUploading: Boolean, isOffline: Boolean, itemCount: Int, primaryColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isUploading) primaryColor.copy(alpha = 0.05f) else Color.Transparent)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = if (isUploading) Icons.Default.CloudUpload else if (isOffline) Icons.Default.CloudOff else Icons.Default.PauseCircle,
            contentDescription = null,
            tint = if (isUploading) primaryColor else Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isUploading) "Syncing to Cloud..." else if (isOffline) "Waiting for Connection" else "Upload Queue",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "$itemCount item${if (itemCount == 1) "" else "s"} waiting to upload",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }
        if (isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PendingRow(
    item: ArchivedMedia,
    isUploading: Boolean,
    progress: Double,
    primaryColor: Color,
    onNavigate: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!isUploading) onNavigate() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (isUploading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Uploading...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = primaryColor
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(10.dp))
                    Text("Queued", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500))
                }
            }
        }

        if (!isUploading) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Red.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun EmptyQueueCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Green.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(22.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("All Caught Up!", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("No uploads waiting.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedsMoreMediaSheet(
    landmark: BusinessLandmark,
    onDismiss: () -> Unit,
    onForceTrain: () -> Unit,
    onAddMedia: (BusinessLandmark) -> Unit
) {
    var isForcingTrain by remember { mutableStateOf(false) }
    var showForceTrainAlert by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val processed = landmark.cleanFrameCount ?: 0
    val required = landmark.requiredFrames ?: 1500
    val seconds = landmark.secondsNeeded ?: ceil(max(0, required - processed) / 30.0).toInt().takeIf { it > 0 } ?: 50

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1C1C1E),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Red.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(36.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More Media Required", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "We couldn't extract enough unique frames of ${landmark.label} to train a reliable model.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }

            Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.35f), shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Frames Extracted", fontSize = 12.sp, color = Color.Gray)
                            Text(
                                "$processed / $required",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (processed < required) Color.Red else Color(0xFFFFA500)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AI Target Video", fontSize = 12.sp, color = Color.Gray)
                            Text("~$seconds Secs", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9F0A))
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    Text(
                        "We recommend capturing about $seconds more seconds of video, but any additional footage helps! Record between 1 and 120 seconds. Once uploaded, training will resume automatically.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        lineHeight = 18.sp
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                            onAddMedia(landmark)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isForcingTrain
                ) {
                    Text("Add Media Now", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                }

                Button(
                    onClick = { showForceTrainAlert = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red.copy(alpha = 0.15f),
                        contentColor = Color.Red
                    ),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isForcingTrain
                ) {
                    if (isForcingTrain) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Red, strokeWidth = 2.dp)
                    } else {
                        Text("Force Train Anyway", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showForceTrainAlert) {
        AlertDialog(
            onDismissRequest = { showForceTrainAlert = false },
            title = { Text("Force Train Landmark?", color = Color.White) },
            text = { Text("This landmark has less than the recommended $required frames. Detection reliability may be reduced. Are you sure you want to train it anyway?", color = Color.Gray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceTrainAlert = false
                        isForcingTrain = true
                        onForceTrain()
                    }
                ) {
                    Text("Train Anyway", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceTrainAlert = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}