package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessMediaHistoryView(
    landmarkId: String,
    landmarkLabel: String
) {
    val viewModel = remember { BusinessMediaHistoryViewModel(landmarkId, landmarkLabel) }
    val items by viewModel.items.collectAsState()
    val isLoadingInitial by viewModel.isLoadingInitial.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val hasMoreItems = viewModel.hasMoreItems
    val landmarkTitle by viewModel.landmarkLabel.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    LaunchedEffect(viewModel.processingPollKey) {
        viewModel.pollProcessingItems()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload History") },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { viewModel.refresh() } }, enabled = !isRefreshing) {
                        if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        if (isLoadingInitial) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null && items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMessage!!, color = Color.Red, modifier = Modifier.padding(16.dp))
                    Button(onClick = { coroutineScope.launch { viewModel.retry() } }) {
                        Text("Retry")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(landmarkTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "${items.size} upload${if (items.size == 1) "" else "s"} loaded",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Uploads Section
                item {
                    Text("UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                }

                items(items) { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 1.dp
                    ) {
                        BusinessMediaHistoryRow(
                            item = item,
                            isRetrying = viewModel.isRetrying(item),
                            retryError = viewModel.retryError(item),
                            onRetry = { coroutineScope.launch { viewModel.retryProcessing(item) } }
                        )
                    }
                }

                // Load More Section
                if (hasMoreItems) {
                    item {
                        TextButton(
                            onClick = { coroutineScope.launch { viewModel.loadMore() } },
                            enabled = !isLoadingMore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isLoadingMore) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Load More", color = Color(0xFF007AFF))
                        }
                    }
                }

                if (errorMessage != null && items.isNotEmpty()) {
                    item {
                        Text(errorMessage!!, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        Button(onClick = { coroutineScope.launch { viewModel.retry() } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry Loading More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessMediaHistoryRow(
    item: BusinessMediaHistoryItem,
    isRetrying: Boolean,
    retryError: String?,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BusinessMediaThumbnail(item)

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.roleAndMediaTitle, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                    Text(item.uploadedBy.displayText, fontSize = 12.sp, color = Color.Gray)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                    Text(item.uploadedAtISO ?: "Unknown date", fontSize = 12.sp, color = Color.Gray)
                }
            }

            BusinessMediaStatusBadge(item)
        }

        if (item.submissionId.isNotEmpty()) {
            Text("ID: ${item.submissionId}", fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color.Gray)
        }

        if (item.displayFilename.isNotEmpty()) {
            Text("File: ${item.displayFilename}", fontSize = 11.sp, color = Color.Gray)
        }

        if (item.isProcessingDelayed) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF9E6), RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Processing is taking longer than expected.", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (retryError != null) {
                        Text(retryError, fontSize = 12.sp, color = Color.Red)
                    }
                }
                if (item.canRetryProcessing) {
                    IconButton(onClick = onRetry, enabled = !isRetrying) {
                        if (isRetrying) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessMediaThumbnail(item: BusinessMediaHistoryItem) {
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 72.dp)
            .background(Color(0xFFE5E5EA), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = item.thumbnailUrl
        if (thumb != null && thumb.isNotEmpty()) {
            // In a real app, use Coil or similar to load the URL
            // AsyncImage(model = thumb, contentDescription = null)
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        } else {
            Icon(if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun BusinessMediaStatusBadge(item: BusinessMediaHistoryItem) {
    val statusColor = when (item.lifecycleState) {
        BusinessMediaLifecycleState.READY -> Color(0xFF34C759)
        BusinessMediaLifecycleState.PROCESSING -> Color(0xFF007AFF)
        BusinessMediaLifecycleState.FAILED -> Color.Red
        BusinessMediaLifecycleState.UNKNOWN -> Color.Gray
    }

    val statusIcon = when (item.lifecycleState) {
        BusinessMediaLifecycleState.READY -> Icons.Default.CheckCircle
        BusinessMediaLifecycleState.PROCESSING -> Icons.Default.HourglassEmpty
        BusinessMediaLifecycleState.FAILED -> Icons.Default.Error
        BusinessMediaLifecycleState.UNKNOWN -> Icons.Default.QuestionMark
    }

    Row(
        modifier = Modifier.background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
        Text(item.displayStatus, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
