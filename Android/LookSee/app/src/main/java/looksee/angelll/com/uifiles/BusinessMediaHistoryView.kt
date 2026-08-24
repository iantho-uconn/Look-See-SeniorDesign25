package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessMediaHistoryView(
    landmarkId: String,
    landmarkLabel: String
) {
    // This will show as red until we translate BusinessMediaHistoryViewModel.kt!
    val viewModel = remember { BusinessMediaHistoryViewModel(landmarkId, landmarkLabel) }
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
                title = { Text("Media History", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { coroutineScope.launch { viewModel.refresh() } },
                        enabled = !(viewModel.isRefreshing || viewModel.isLoadingInitial || viewModel.isLoadingMore)
                    ) {
                        if (viewModel.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh media history")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).padding(padding)) {
            when {
                viewModel.isLoadingInitial && viewModel.items.isEmpty() -> {
                    InitialLoadingView()
                }
                viewModel.items.isEmpty() -> {
                    EmptyOrErrorView(viewModel) { coroutineScope.launch { viewModel.retry() } }
                }
                else -> {
                    HistoryList(viewModel)
                }
            }
        }
    }
}

@Composable
private fun InitialLoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Loading media history...",
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun EmptyOrErrorView(viewModel: BusinessMediaHistoryViewModel, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (viewModel.errorMessage == null) Icons.Default.PhotoLibrary else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (viewModel.errorMessage == null) "No Upload History" else "Couldn’t Load History",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = viewModel.errorMessage ?: "New positive and negative uploads for this landmark will appear here.",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        if (viewModel.errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun ErrorMessageSection(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
            Text("Some history could not be loaded", color = Color(0xFFFFA500), fontWeight = FontWeight.Medium)
        }
        Text(message, fontSize = 12.sp, color = Color.Gray)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text("Retry")
        }
    }
}

@Composable
private fun HistoryList(viewModel: BusinessMediaHistoryViewModel) {
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(viewModel.landmarkLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    text = "${viewModel.items.size} upload${if (viewModel.items.size == 1) "" else "s"} loaded",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // Uploads Section
        item {
            Text("UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        }

        items(viewModel.items) { item ->
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
        if (viewModel.hasMoreItems) {
            item {
                TextButton(
                    onClick = { coroutineScope.launch { viewModel.loadMore() } },
                    enabled = !viewModel.isLoadingMore,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (viewModel.isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading more...")
                    } else {
                        Icon(Icons.Default.ArrowCircleDown, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Load More")
                    }
                }
            }
        }

        // Bottom Error Section
        if (viewModel.errorMessage != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 1.dp
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        ErrorMessageSection(viewModel.errorMessage!!) {
                            coroutineScope.launch { viewModel.retry() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessMediaHistoryRow(
    item: BusinessMediaHistoryItem,
    isRetrying: Boolean,
    retryError: String?,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        ThumbnailView(item)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = item.roleAndMediaTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            StatusBadge(item)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                Text(item.uploadedBy.displayText, fontSize = 14.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                val dateString = item.uploadDate?.let { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(it) } ?: "Date unavailable"
                Text(dateString, fontSize = 14.sp, color = Color.Gray)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Submission ID", fontSize = 10.sp, color = Color.Gray)
                SelectionContainer {
                    Text(
                        text = item.submissionId,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = item.displayFilename,
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.isProcessingDelayed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(14.dp))
                    Text("Processing longer than expected", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFA500))
                }
            }

            if (item.canRetryProcessing) {
                OutlinedButton(
                    onClick = onRetry,
                    enabled = !isRetrying,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (isRetrying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRetrying) "Requeueing..." else "Retry Processing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            val retryCount = item.retryCount ?: 0
            if (retryCount > 0) {
                Text("Processing retried $retryCount time${if (retryCount == 1) "" else "s"}", fontSize = 10.sp, color = Color.Gray)
            }

            if (retryError != null) {
                Text(retryError, fontSize = 10.sp, color = Color.Red)
            }
        }
    }
}

@Composable
private fun ThumbnailView(item: BusinessMediaHistoryItem) {
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Gray.copy(alpha = 0.12f))
    ) {
        if (item.thumbnailUrl != null) {
            SubcomposeAsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { ThumbnailPlaceholder(item, showProgress = true) },
                error = { ThumbnailPlaceholder(item, showProgress = false) }
            )
        } else {
            ThumbnailPlaceholder(item, showProgress = false)
        }

        if (item.isVideo) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(5.dp)
                    .size(12.dp)
            )
        }
    }
}

@Composable
private fun ThumbnailPlaceholder(item: BusinessMediaHistoryItem, showProgress: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StatusBadge(item: BusinessMediaHistoryItem) {
    val statusColor = when (item.lifecycleState) {
        LifecycleState.Ready -> Color(0xFF34C759)
        LifecycleState.Processing -> Color(0xFFFFA500)
        LifecycleState.Failed -> Color.Red
        else -> Color.Gray
    }

    val statusIcon = if (item.isProcessingDelayed) {
        Icons.Default.Error
    } else {
        when (item.lifecycleState) {
            LifecycleState.Ready -> Icons.Default.CheckCircle
            LifecycleState.Processing -> Icons.Default.Sync
            LifecycleState.Failed -> Icons.Default.Warning
            else -> Icons.Default.HelpOutline
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(statusColor.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = item.displayStatus,
            color = statusColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}