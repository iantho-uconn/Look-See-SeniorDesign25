package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.viewmodels.AuthViewModel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class MapReportReason(val displayName: String, val icon: ImageVector, val color: Color) {
    INAPPROPRIATE("Inappropriate content", Icons.Default.Security, Color(0xFFFFA500)),
    OWNERSHIP("Landmark ownership issue", Icons.Default.PersonSearch, Color(0xFF800080)),
    COPYRIGHT("Copyright / DMCA", Icons.Default.Copyright, Color(0xFF007AFF)),
    OTHER("Other / Custom Issue", Icons.Default.TextSnippet, Color.Gray)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapReportSheet(
    landmarkId: String,
    landmarkLabel: String,
    reportedOwnerId: String,
    userEmail: String,
    onDismiss: () -> Unit
) {
    var selectedReason by remember { mutableStateOf<MapReportReason?>(null) }
    var customTitle by remember { mutableStateOf("") }
    var customExplanation by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var reportSuccess by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    
    val maxDescLength = 3000
    val maxTitleLength = 100
    val primaryColor = Color(0xFF387DFF) // AppleBlue equivalent
    
    val isFormValid = remember(selectedReason, customTitle, customExplanation) {
        val reason = selectedReason ?: return@remember false
        if (reason == MapReportReason.OTHER) {
            customTitle.trim().isNotEmpty() && customExplanation.trim().isNotEmpty()
        } else {
            true
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            if (reportSuccess) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(64.dp))
                    Text("Report Submitted", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Thank you for keeping LookSee clean. Our team will review this landmark shortly.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Report Landmark", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Why are you reporting '$landmarkLabel'?", fontSize = 15.sp, color = Color.Gray)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MapReportReason.entries.forEach { reason ->
                                val isSelected = selectedReason == reason
                                val bgColor = if (isSelected) primaryColor.copy(alpha = 0.15f) else Color(0xFF1C1C1E)
                                val borderColor = if (isSelected) primaryColor else Color.Transparent
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor, RoundedCornerShape(16.dp))
                                        .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedReason = reason
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(42.dp).background(reason.color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Icon(reason.icon, contentDescription = null, tint = reason.color, modifier = Modifier.size(24.dp))
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Text(reason.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    
                                    Box(modifier = Modifier.size(22.dp).border(2.dp, if (isSelected) primaryColor else Color.Gray, CircleShape), contentAlignment = Alignment.Center) {
                                        if (isSelected) {
                                            Box(modifier = Modifier.size(12.dp).background(primaryColor, CircleShape))
                                        }
                                    }
                                }
                            }
                        }

                        if (selectedReason != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (selectedReason == MapReportReason.OTHER) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("CUSTOM ISSUE TITLE (REQUIRED)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                        Text("${customTitle.length}/$maxTitleLength", fontSize = 11.sp, color = if (customTitle.length >= maxTitleLength) Color.Red else Color.Gray)
                                    }
                                    OutlinedTextField(
                                        value = customTitle,
                                        onValueChange = { if (it.length <= maxTitleLength) customTitle = it },
                                        placeholder = { Text("Short title of the issue", color = Color.DarkGray) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF2C2C2E), focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (selectedReason == MapReportReason.OTHER) "PLEASE DESCRIBE THE ISSUE (REQUIRED)" else "ADDITIONAL DETAILS (OPTIONAL)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    Text("${customExplanation.length}/$maxDescLength", fontSize = 11.sp, color = if (customExplanation.length >= maxDescLength) Color.Red else Color.Gray)
                                }
                                OutlinedTextField(
                                    value = customExplanation,
                                    onValueChange = { if (it.length <= maxDescLength) customExplanation = it },
                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF2C2C2E), unfocusedContainerColor = Color(0xFF2C2C2E), focusedBorderColor = if (customExplanation.length >= maxDescLength) Color.Red else Color.Transparent, unfocusedBorderColor = if (customExplanation.length >= maxDescLength) Color.Red else Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            Spacer(Modifier.height(40.dp))
                        }
                    }

                    if (selectedReason != null) {
                        Surface(color = Color(0xFF1C1C1E), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isSubmitting = true
                                    val safeExplanation = customExplanation.trim()
                                    val safeCustomTitle = customTitle.trim()
                                    val safeEmail = userEmail.ifEmpty { "unknown@user.com" }
                                    
                                    val finalReason = if (selectedReason == MapReportReason.OTHER && safeCustomTitle.isNotEmpty()) safeCustomTitle else selectedReason?.displayName ?: "Unknown"
                                    val finalCategory = if (selectedReason == MapReportReason.OTHER) "Custom Issue" else "Landmark Report"
                                    
                                    val fullDesc = "${if (safeExplanation.isEmpty()) "No additional details provided." else safeExplanation}\n\n---\nLandmark Name: $landmarkLabel\nLandmark ID: $landmarkId\nReported Owner ID: $reportedOwnerId"
                                    
                                    val payload = JSONObject().apply {
                                        put("email", safeEmail)
                                        put("category", finalCategory)
                                        put("severity", "High")
                                        put("title", "[$finalReason] $landmarkLabel")
                                        put("description", fullDesc)
                                    }
                                    
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                val url = URL("https://d11vl3v9w133rh.cloudfront.net/support/report")
                                                val conn = url.openConnection() as HttpURLConnection
                                                conn.requestMethod = "POST"
                                                conn.setRequestProperty("Content-Type", "application/json")
                                                conn.doOutput = true
                                                val bytes = payload.toString().toByteArray()
                                                conn.outputStream.write(bytes)
                                                if (conn.responseCode in 200..299) {
                                                    reportSuccess = true
                                                }
                                                conn.disconnect()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                            isSubmitting = false
                                        }
                                    }
                                },
                                enabled = isFormValid && !isSubmitting,
                                modifier = Modifier.fillMaxWidth().padding(24.dp).height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor, disabledContainerColor = Color.Gray.copy(alpha=0.4f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text("Submit Report", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
