package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@Composable
fun MultiPhotoCameraView(
    onPhotosCaptured: (List<looksee.angelll.com.models.CapturedNegativePhoto>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val service = remember { MultiPhotoCameraService(context) }
    
    val capturedPhotos by service.capturedPhotos.collectAsState()
    val isCapturing by service.isCapturing.collectAsState()
    val errorMessage by service.errorMessage.collectAsState()
    
    val minimumPhotoCount = 5
    val maximumPhotoCount = 10
    val canCapture = capturedPhotos.size < maximumPhotoCount && !isCapturing
    val hasMinimumPhotos = capturedPhotos.size >= minimumPhotoCount
    val remainingRequired = maxOf(0, minimumPhotoCount - capturedPhotos.size)

    Scaffold(
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Camera Preview Placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera Preview", color = Color.White)
                ViewfinderCircle()
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
                
                Surface(
                    color = Color.Black.copy(0.5f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "${capturedPhotos.size} / $maximumPhotoCount",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }

                Spacer(modifier = Modifier.width(44.dp))
            }

            // Bottom UI
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Instructions Card
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                        .background(Color.Black.copy(0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Capture Negative References", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Photograph the surrounding area, not the landmark itself.", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    
                    if (remainingRequired > 0) {
                        Text("$remainingRequired more required", color = Color.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Minimum complete", color = Color.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Thumbnail Strip
                if (capturedPhotos.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(0.45f)).padding(vertical = 8.dp)) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(capturedPhotos) { photo ->
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Gray)
                                ) {
                                    // In a real app, use Coil to show the photo
                                    IconButton(
                                        onClick = { service.removePhoto(photo) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 6.dp, y = (-6).dp)
                                            .size(24.dp)
                                            .background(Color.Red, CircleShape)
                                            .border(1.dp, Color.White, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(0.65f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(90.dp))

                    // Shutter Button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .background(if (canCapture) Color.White else Color.White.copy(0.45f), CircleShape)
                            .padding(6.dp)
                            .border(3.dp, Color.Black.copy(0.8f), CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = canCapture) { service.capturePhoto() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(30.dp))
                        }
                    }

                    // Done Button
                    Button(
                        onClick = { onPhotosCaptured(capturedPhotos); onDismiss() },
                        enabled = hasMinimumPhotos,
                        modifier = Modifier
                            .width(90.dp)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasMinimumPhotos) Color(0xFF387DFF) else Color.Gray.copy(0.75f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                
                if (errorMessage != null) {
                    Text(errorMessage!!, color = Color.Red, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
