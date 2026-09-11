package looksee.angelll.com.uifiles

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.ui.theme.LookSeeCard
import looksee.angelll.com.ui.theme.LookSeeBlue
import looksee.angelll.com.viewmodels.AuthViewModel
import java.text.SimpleDateFormat
import java.util.*

private data class HistoryGroup(
    val title: String,
    val items: List<ScanHistoryItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryView(
    vm: AuthViewModel,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var selectedItem by remember { mutableStateOf<ScanHistoryItem?>(null) }

    LaunchedEffect(Unit) {
        vm.fetchScanHistory()
        isLoading = false
    }

    val historyGroups = remember(vm.scanHistory) {
        val groups = mutableListOf<HistoryGroup>()
        var currentTitle = ""
        var currentItems = mutableListOf<ScanHistoryItem>()

        for (item in vm.scanHistory) {
            val title = formatGroupTitle(item.scannedAt)
            if (title != currentTitle) {
                if (currentItems.isNotEmpty()) {
                    groups.add(HistoryGroup(currentTitle, currentItems.toList()))
                }
                currentTitle = title
                currentItems = mutableListOf(item)
            } else {
                currentItems.add(item)
            }
        }
        if (currentItems.isNotEmpty()) {
            groups.add(HistoryGroup(currentTitle, currentItems))
        }
        groups
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan History", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = LookSeeBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading History...", color = Color.Gray, fontSize = 14.sp)
                    }
                }
                vm.scanHistory.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.HistoryToggleOff,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No scans yet.",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Landmarks you scan will appear here.",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        historyGroups.forEach { group ->
                            item {
                                Text(
                                    text = group.title.uppercase(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                )
                            }

                            items(group.items, key = { it.scannedAt }) { item ->
                                HistoryRowItem(
                                    item = item,
                                    onClick = { selectedItem = item },
                                    onDelete = {
                                        coroutineScope.launch {
                                            vm.deleteScanHistory(item.scannedAt)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        HistoryDetailSheet(
            item = item,
            onDismiss = { selectedItem = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRowItem(
    item: ScanHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()

    if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
        LaunchedEffect(Unit) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) Color.Red else Color.Transparent
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(color),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart || dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 24.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                        Text("Delete", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        content = {
            LookSeeCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onClick() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding( vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Thumbnail or Initial Avatar
                    if (!item.imageUrl.isNullOrBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(item.imageUrl),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(LookSeeBlue.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.landmarkLabel.take(1).uppercase(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = LookSeeBlue
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.landmarkLabel.ifBlank { "Landmark" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = LookSeeBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = item.locationString.ifBlank { "Unknown Location" },
                                fontSize = 13.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = formatTimeOnly(item.scannedAt),
                            fontSize = 11.sp,
                            color = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailSheet(
    item: ScanHistoryItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val liveService = remember { LiveLandmarkInfoService(context) }
    var liveInfo by remember { mutableStateOf<LiveLandmarkInfoResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(item.landmarkId) {
        liveService.loadCachedMerchantProfile(item.landmarkId)
        try {
            liveInfo = liveService.fetchLiveInfo(item.landmarkId)
        } catch (_: Exception) {
            // Keep fallback
        } finally {
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!item.imageUrl.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(item.imageUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(LookSeeBlue.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.landmarkLabel.take(1).uppercase(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = LookSeeBlue
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.landmarkLabel.ifBlank { "Landmark" },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${formatFullDate(item.scannedAt)} • ${item.locationString}",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // Description
            Text(
                text = liveInfo?.shortDescription?.ifBlank { "No description is available for this landmark." }
                    ?: if (isLoading) "Loading details..." else "No description is available for this landmark.",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.9f),
                lineHeight = 22.sp
            )

            // Website Button
            val websiteUrl = liveInfo?.websiteUrl?.trim().orEmpty()
            if (websiteUrl.isNotEmpty()) {
                Surface(
                    onClick = {
                        var fullUrl = websiteUrl
                        if (!fullUrl.lowercase().startsWith("http")) fullUrl = "https://$fullUrl"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                        context.startActivity(intent)
                    },
                    color = Color(0xFF5A27D5),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Color.White)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Visit Website", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(websiteUrl, fontSize = 12.sp, color = Color.White.copy(0.7f), maxLines = 1)
                        }
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Promotion Card
            val activePromo = liveInfo?.activePromotion
            if (liveInfo?.isActive == true && activePromo != null && activePromo.name.isNotBlank() && activePromo.name != "No active promotion") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF402E1E), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(activePromo.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (activePromo.description.isNotBlank()) {
                        Text(activePromo.description, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                    if (!activePromo.imageUrl.isNullOrBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(activePromo.imageUrl),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }

            // Merchant Card
            val merchantName = liveInfo?.merchantName?.trim().orEmpty()
            if (merchantName.isNotEmpty()) {
                MerchantCard(
                    storeName = merchantName,
                    logoUrl = liveInfo?.merchantLogoUrl.orEmpty(),
                    bio = liveInfo?.merchantBio.orEmpty(),
                    phone = liveInfo?.merchantPhone.orEmpty(),
                    website = liveInfo?.merchantWebsite.orEmpty(),
                    address = liveInfo?.merchantAddress.orEmpty()
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun formatGroupTitle(isoString: String): String {
    val date = parseIsoDate(isoString) ?: return "Unknown Date"
    val cal = Calendar.getInstance()
    val now = Calendar.getInstance()

    cal.time = date
    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Today"
    }

    now.add(Calendar.DAY_OF_YEAR, -1)
    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Yesterday"
    }

    val displayFormatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
    return displayFormatter.format(date)
}

private fun formatTimeOnly(isoString: String): String {
    val date = parseIsoDate(isoString) ?: return isoString
    val displayFormatter = SimpleDateFormat("h:mm a", Locale.US)
    return displayFormatter.format(date)
}

private fun formatFullDate(isoString: String): String {
    val date = parseIsoDate(isoString) ?: return isoString
    val displayFormatter = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US)
    return displayFormatter.format(date)
}

private fun parseIsoDate(isoString: String): Date? {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(isoString.take(19))
    } catch (_: Exception) {
        null
    }
}
