package looksee.angelll.com.uifiles

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter

@Composable
fun MultiPhotoCameraScreen(
    existingPhotos: List<CapturedNegativePhoto>, // Unresolved reference
    minimumPhotoCount: Int = 5,
    maximumPhotoCount: Int = 10,
    onDone: (List<CapturedNegativePhoto>) -> Unit,
    onDismiss: () -> Unit
) {
    // Unresolved reference: MultiPhotoCameraService
    val cameraService = remember {
        MultiPhotoCameraService(existingPhotos, maximumPhotoCount)
    }

    val hasMinimumPhotos = cameraService.capturedPhotos.size >= minimumPhotoCount
    val remainingRequiredPhotos = maxOf(minimumPhotoCount - cameraService.capturedPhotos.size, 0)

    LaunchedEffect(Unit) {
        cameraService.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraService.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview Placeholder (To be implemented with CameraX)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        cameraService.discardNewPhotos()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                    shape = CircleShape
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold, color = Color.White)
                }

                Text(
                    text = "${cameraService.capturedPhotos.size} / $maximumPhotoCount",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Instructions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("Capture Negative References", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Photograph the surrounding area, not the landmark itself.", fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))

                if (remainingRequiredPhotos > 0) {
                    Text("$remainingRequiredPhotos more required", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Yellow)
                } else {
                    Text("Minimum complete", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Green)
                }
            }

            // Thumbnail Strip
            if (cameraService.capturedPhotos.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(cameraService.capturedPhotos) { photo ->
                        Box(contentAlignment = Alignment.TopEnd) {
                            Image(
                                painter = rememberAsyncImagePainter(model = photo.fileUri),
                                contentDescription = "Thumbnail",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { cameraService.removePhoto(photo) },
                                modifier = Modifier
                                    .size(24.dp)
                                    .offset(x = 6.dp, y = (-6).dp)
                                    .background(Color.White, CircleShape)
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = "Remove", tint = Color.Red)
                            }
                        }
                    }
                }
            }

            // Bottom Controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(90.dp))

                    // Capture Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(76.dp)
                            .background(Color.White, CircleShape)
                            .padding(6.dp)
                            .border(3.dp, Color.Black.copy(alpha = 0.8f), CircleShape)
                    ) {
                        if (cameraService.isCapturing) {
                            CircularProgressIndicator(color = Color.Black)
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .clickable(enabled = cameraService.canCaptureAnotherPhoto) {
                                        cameraService.capturePhoto()
                                    }
                            )
                        }
                    }

                    // Done Button
                    Button(
                        onClick = {
                            onDone(cameraService.capturedPhotos)
                            onDismiss()
                        },
                        enabled = hasMinimumPhotos,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Blue,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.75f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .width(90.dp)
                            .height(52.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // Error Overlay
        if (cameraService.errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.WarningAmber, contentDescription = "Error", tint = Color.White, modifier = Modifier.size(42.dp))
                    Text("Camera Unavailable", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(cameraService.errorMessage!!, textAlign = TextAlign.Center, color = Color.White)
                    Button(
                        onClick = {
                            cameraService.discardNewPhotos()
                            onDismiss()
                        }
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}