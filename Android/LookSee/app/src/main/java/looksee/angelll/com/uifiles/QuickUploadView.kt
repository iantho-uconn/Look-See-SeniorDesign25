package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@Composable
fun QuickUploadView(
    landmark: NearbyLandmark,
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uploadService = remember { UploadService(context) }
    
    var isUploading by remember { mutableStateOf(false) }
    var uploadStage by remember { mutableStateOf(PositiveUploadStage.IDLE) }
    var progress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("") }

    val handleUpload = {
        coroutineScope.launch {
            isUploading = true
            uploadStage = PositiveUploadStage.VALIDATING
            statusMessage = "Preparing upload..."
            
            try {
                // Mocking a file upload for now, or use real logic
                uploadStage = PositiveUploadStage.UPLOADING_MEDIA
                statusMessage = "Uploading media to ${landmark.label}..."
                
                for (i in 1..10) {
                    kotlinx.coroutines.delay(200)
                    progress = i / 10f
                }
                
                uploadStage = PositiveUploadStage.COMPLETE
                statusMessage = "Upload successful! You earned 1 token."
                vm.fetchUserUsageStats() // Refresh tokens
                
                kotlinx.coroutines.delay(1500)
                onDismiss()
            } catch (e: Exception) {
                uploadStage = PositiveUploadStage.FAILED
                statusMessage = "Upload failed: ${e.localizedMessage}"
            } finally {
                isUploading = false
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                tint = Color(0xFF007AFF),
                modifier = Modifier.size(48.dp)
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Quick Upload", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(landmark.label, color = Color.Gray, fontSize = 14.sp)
            }

            if (uploadStage != PositiveUploadStage.IDLE) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color(0xFF007AFF),
                        trackColor = Color.LightGray.copy(alpha = 0.3f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Text(statusMessage, fontSize = 13.sp, color = if (uploadStage == PositiveUploadStage.FAILED) Color.Red else Color.Gray)
                }
            } else {
                Text(
                    "Upload a quick photo or video of this landmark to help the community and earn rewards.",
                    fontSize = 14.sp,
                    color = Color.DarkGray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !isUploading
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { handleUpload() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !isUploading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                ) {
                    Text("Upload Now")
                }
            }
            
            if (vm.tokenBalance < 5) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2F2F7), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Low token balance. Upload to earn more!", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}
