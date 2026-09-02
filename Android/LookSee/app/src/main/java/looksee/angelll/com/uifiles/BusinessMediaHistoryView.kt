package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*
import coil.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    Row(
        modifier = Modifier
            .padding(14.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        BusinessMediaThumbnail(item)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = item.roleAndMediaTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 22.sp
            )

            BusinessMediaStatusBadge(item)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                Text(item.uploadedBy.displayText, fontSize = 13.sp, color = Color.Gray)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                val dateText = remember(item.uploadInstant) {
                    item.uploadInstant?.let {
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                            .withZone(ZoneId.systemDefault())
                            .format(it)
                    } ?: item.uploadedAtISO ?: "Date unavailable"
                }
                Text(dateText, fontSize = 13.sp, color = Color.Gray)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Submission ID", fontSize = 11.sp, color = Color.Gray)
                Text(
                    text = item.submissionId,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = Color.White,
                    maxLines = 1
                )
            }

            if (item.displayFilename.isNotEmpty()) {
                Text(
                    text = item.displayFilename,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }

            if (item.isProcessingDelayed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(14.dp))
                    Text(
                        "Processing longer than expected",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFA500)
                    )
                }
            }

            if (item.canRetryProcessing) {
                Button(
                    onClick = onRetry,
                    enabled = !isRetrying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isRetrying) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Text(if (isRetrying) "Requeueing..." else "Retry Processing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (item.retryCount != null && item.retryCount > 0) {
                Text(
                    "Processing retried ${item.retryCount} time${if (item.retryCount == 1) "" else "s"}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            if (retryError != null) {
                Text(retryError, fontSize = 11.sp, color = Color.Red)
            }
        }
    }
}

@Composable
fun BusinessMediaThumbnail(item: BusinessMediaHistoryItem) {
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = item.thumbnailUrl
        if (!thumb.isNullOrEmpty()) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
 else {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
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
