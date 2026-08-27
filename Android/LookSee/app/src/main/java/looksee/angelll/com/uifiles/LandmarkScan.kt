package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@Composable
fun LandmarkScan(
    onTap: () -> Unit,
    isDetecting: Boolean,
    onIsDetectingChange: (Boolean) -> Unit,
    isNavVisible: Boolean,
    isScannerActive: Boolean
) {
    var detectedLandmark by remember { mutableStateOf<ScannedLandmark?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview Placeholder
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Camera Scanning...", color = Color.White)
            ViewfinderCircle()
        }

        // Detected Landmark Card
        if (detectedLandmark != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(detectedLandmark!!.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(detectedLandmark!!.confidence, color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(detectedLandmark!!.description ?: "", fontSize = 14.sp, color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { /* Navigate to detail */ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                    ) {
                        Text("View Details")
                    }
                }
            }
        }
        
        // Scan Button / Toggle
        IconButton(
            onClick = { onIsDetectingChange(!isDetecting) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .size(56.dp)
                .background(if (isDetecting) Color(0xFF007AFF) else Color.Gray, CircleShape)
        ) {
            Icon(Icons.Default.CenterFocusStrong, contentDescription = "Toggle Scan", tint = Color.White)
        }
    }
}
