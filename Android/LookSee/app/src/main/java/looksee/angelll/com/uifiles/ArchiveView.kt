package looksee.angelll.com.uifiles

import androidx.compose.runtime.collectAsState
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.models.ArchivedMedia
import looksee.angelll.com.services.AutoUploadManager
import looksee.angelll.com.services.OfflineMediaManager
import looksee.angelll.com.viewmodels.AuthViewModel

// We extract your color palette constants
private val backgroundColor = Color(0xFF14141F)
private val cardColor = Color(0xFF1F1F2E)
private val primaryColor = Color(0xFF387DFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveView(vm: AuthViewModel) {
    val context = LocalContext.current
    val archivedItems by OfflineMediaManager.archivedItems.collectAsState()
    val isUploading by AutoUploadManager.isUploading.collectAsState()

    var showInfoSheet by remember { mutableStateOf(false) }
    var selectedMedia by remember { mutableStateOf<ArchivedMedia?>(null) }
    var editingMedia by remember { mutableStateOf<ArchivedMedia?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isUploading, archivedItems.size) {
        if (!isUploading && archivedItems.isNotEmpty()) {
            AutoUploadManager.startProcessing(context, vm)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload Queue", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor),
                navigationIcon = {
                    IconButton(onClick = {
                        if (isUploading) AutoUploadManager.stopProcessing()
                        else coroutineScope.launch { AutoUploadManager.startProcessing(context, vm) }
                    }) {
                        Icon(
                            imageVector = if (isUploading) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            contentDescription = "Toggle Upload",
                            tint = if (isUploading) Color(0xFFFFA500) else Color.Green,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoSheet = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Info", tint = Color.White)
                    }
                }
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(modifier = Modifier.fillMaxSize()) {
                StatusHeader(isUploading = isUploading, count = archivedItems.size)

                if (archivedItems.isEmpty()) {
                    EmptyStateView()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(archivedItems) { media ->
                            QueueItemCard(
                                media = media,
                                onClick = { selectedMedia = media }
                            )
                        }
                    }
                }
            }
        }

        // Info Sheet
        if (showInfoSheet) {
            ModalBottomSheet(
                onDismissRequest = { showInfoSheet = false },
                containerColor = Color(0xFF1C1C29)
            ) {
                QueueInfoSheet(onDismiss = { showInfoSheet = false })
            }
        }

        // Details Sheet
        if (selectedMedia != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedMedia = null },
                containerColor = Color(0xFF1C1C29)
            ) {
                QueueDetailSheet(
                    media = selectedMedia!!,
                    onDismiss = { selectedMedia = null },
                    onEdit = {
                        val mediaToEdit = selectedMedia
                        selectedMedia = null
                        coroutineScope.launch {
                            delay(300) // DispatchQueue.main.asyncAfter equivalent
                            if (isUploading) AutoUploadManager.stopProcessing()
                            editingMedia = mediaToEdit
                        }
                    }
                )
            }
        }

        // Full Screen Edit Cover (LandmarkRecord)
        if (editingMedia != null) {
            // Note: Replace LandmarkRecord with your actual composable when we translate it
            // LandmarkRecord(archivedMedia = editingMedia, onDismiss = { editingMedia = null })
        }
    }
}

@Composable
fun StatusHeader(isUploading: Boolean, count: Int) {
    Column(modifier = Modifier.fillMaxWidth().background(cardColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isUploading) Color.Blue.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUploading) Icons.Default.CloudUpload else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (isUploading) Color.Blue else Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isUploading) "Syncing to Cloud..." else "Queue Paused",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Text(
                    text = "$count items waiting to upload",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            if (isUploading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp).padding(end = 8.dp))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.05f))
    }
}

@Composable
fun QueueItemCard(media: ArchivedMedia, onClick: () -> Unit) {
    val context = LocalContext.current
    val currentlyUploadingId by AutoUploadManager.currentlyUploadingId.collectAsState()
    val progress by AutoUploadManager.currentUploadProgress.collectAsState()
    val isCurrentlyUploading = currentlyUploadingId == media.id

    val borderColor = if (isCurrentlyUploading) Color.Blue.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (media.isVideo) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White)
            } else {
                val file = OfflineMediaManager.getFileURL(context, media)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.savedLabel ?: "Untitled Landmark",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (isCurrentlyUploading) {
                Text("Uploading...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Blue)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = progress.toFloat(),
                    color = Color.Blue,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFFFA500), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queued", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA500))
                }
            }
        }

        // Delete Button
        if (!isCurrentlyUploading) {
            IconButton(onClick = { OfflineMediaManager.deleteArchive(context, media) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
fun EmptyStateView() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(cardColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(44.dp))
        }
        Text("All Caught Up!", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "There is no media waiting in the queue.\nEverything is securely synced to LookSee.",
            fontSize = 14.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
    }
}

@Composable
fun QueueInfoSheet(onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.Blue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SyncAlt, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(34.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("How the Queue Works", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(24.dp))

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            InfoRow(Icons.Default.WifiOff, "Offline Ready", "If you lose connection while recording, your landmarks are securely saved here automatically.")
            InfoRow(Icons.Default.CloudUpload, "Background Sync", "The app actively watches your connection and uploads queued media in the background as soon as service returns.")
            InfoRow(Icons.Default.BatteryChargingFull, "Safe Storage", "Media stays on your device until it is verified by the LookSee cloud, preventing data loss.")
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(crossAxisAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(desc, fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun QueueDetailSheet(media: ArchivedMedia, onDismiss: () -> Unit, onEdit: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        DetailRow("Label", media.savedLabel ?: "No Label", isTitle = true)
        Spacer(modifier = Modifier.height(24.dp))
        DetailRow("Description", media.savedDescription ?: "No Description")
        Spacer(modifier = Modifier.height(24.dp))
        DetailRow("Location Coordinates", "${media.latitude}, ${media.longitude}")

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onEdit,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Edit Landmark", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, isTitle: Boolean = false) {
    Column {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            fontSize = if (isTitle) 20.sp else 16.sp,
            fontWeight = if (isTitle) FontWeight.Bold else FontWeight.Normal,
            color = Color.White
        )
    }
}