package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import looksee.angelll.com.models.BusinessMediaHistoryItem
import looksee.angelll.com.viewmodels.BusinessMediaHistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessMediaHistoryView(
    viewModel: BusinessMediaHistoryViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media History") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { coroutineScope.launch { viewModel.refresh() } },
                        enabled = !viewModel.isRefreshing && !viewModel.isLoadingInitial && !viewModel.isLoadingMore
                    ) {
                        if (viewModel.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (viewModel.isLoadingInitial && viewModel.items.isEmpty()) {
                // Initial Loading
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading media history...", color = Color.Gray, fontSize = 14.sp)
                }
            } else if (viewModel.items.isEmpty()) {
                // Empty or Error View
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (viewModel.errorMessage == null) Icons.Default.ImageNotSupported else Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (viewModel.errorMessage == null) "No Upload History" else "Couldn't Load History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = viewModel.errorMessage ?: "New positive and negative uploads for this landmark will appear here.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    if (viewModel.errorMessage != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { coroutineScope.launch { viewModel.retry() } }) {
                            Text("Try Again")
                        }
                    }
                }
            } else {
                // History List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(viewModel.landmarkLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${viewModel.items.size} upload${if (viewModel.items.size == 1) "" else "s"} loaded", color = Color.Gray, fontSize = 13.sp)
                        }
                    }

                    item {
                        Text("UPLOADS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                    }

                    items(viewModel.items) { item ->
                        BusinessMediaHistoryRow(item)
                    }

                    if (viewModel.hasMoreItems) {
                        item {
                            TextButton(
                                onClick = { coroutineScope.launch { viewModel.loadMore() } },
                                enabled = !viewModel.isLoadingMore,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                if (viewModel.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Loading more...")
                                } else {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Load More")
                                }
                            }
                        }
                    }

                    if (viewModel.errorMessage != null) {
                        item {
                            SettingsSection {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Some history could not be loaded", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Text(viewModel.errorMessage!!, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                    Button(onClick = { coroutineScope.launch { viewModel.retry() } }, colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray)) {
                                        Text("Retry", color = Color.Black)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessMediaHistoryRow(item: BusinessMediaHistoryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top // 🚀 FIXED: Was crossAxisAlignment
    ) {
        // Thumbnail using Coil
        Box(
            modifier = Modifier
                .size(width = 88.dp, height: 72.dp)
            .background(Color.Gray.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
        ) {
        if (item.thumbnailUrl != null) {
            SubcomposeAsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(20.dp)) },
                error = { Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center)) }
            )
        } else {
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }

        if (item.isVideo) {
            Icon(
                Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(4.dp)
                    .size(12.dp)
            )
        }
    }

        Spacer(modifier = Modifier.width(14.dp))

        // Details
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.roleAndMediaTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)

                val statusColor = when (item.normalizedStatus.lowercase()) {
                    "ready", "complete", "completed" -> Color.Green
                    "processing", "upload pending", "initiated" -> Color(0xFFFFA500)
                    "failed", "error" -> Color.Red
                    else -> Color.Gray
                }

                Text(
                    text = item.normalizedStatus,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(item.uploadedBy.displayText, fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(item.uploadDate, fontSize = 13.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Submission ID", fontSize = 10.sp, color = Color.Gray)
            Text(item.submissionId, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(modifier = Modifier.height(2.dp))
            Text(item.displayFilename, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}