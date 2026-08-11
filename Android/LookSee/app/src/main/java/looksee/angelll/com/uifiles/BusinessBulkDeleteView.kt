package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessBulkLandmarkFailure
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.services.BusinessLandmarkService

data class BusinessBulkDeleteResult(
    val successfulLandmarkIds: Set<String>,
    val failedLandmarks: List<BusinessBulkLandmarkFailure>
) {
    val successfulCount: Int get() = successfulLandmarkIds.size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessBulkDeleteView(
    landmarks: List<BusinessLandmark>,
    onCompleted: (BusinessBulkDeleteResult) -> Unit,
    onDismiss: () -> Unit
) {
    var confirmationText by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var completedResult by remember { mutableStateOf<BusinessBulkDeleteResult?>(null) }

    val service = remember { BusinessLandmarkService() }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val requiredConfirmationText = "delete ${landmarks.size} landmark${if (landmarks.size == 1) "" else "s"}"
    val isConfirmationValid = confirmationText.trim() == requiredConfirmationText

    val deleteLandmarks: () -> Unit = {
        if (!isDeleting && isConfirmationValid) {
            isDeleting = true
            completedResult = null

            coroutineScope.launch {
                val successfulLandmarkIds = mutableSetOf<String>()
                val failedLandmarks = mutableListOf<BusinessBulkLandmarkFailure>()

                for ((index, landmark) in landmarks.withIndex()) {
                    progressText = "Landmark ${index + 1} of ${landmarks.size}: ${displayLabel(landmark)}"

                    try {
                        service.deleteLandmark(
                            landmarkId = landmark.landmarkId,
                            confirmation = "delete landmark"
                        )
                        successfulLandmarkIds.add(landmark.landmarkId)
                    } catch (e: Exception) {
                        failedLandmarks.add(
                            BusinessBulkLandmarkFailure(
                                landmarkId = landmark.landmarkId,
                                landmarkLabel = displayLabel(landmark),
                                message = e.localizedMessage ?: "Unknown error"
                            )
                        )
                    }
                }

                val result = BusinessBulkDeleteResult(successfulLandmarkIds, failedLandmarks)
                isDeleting = false
                progressText = null
                completedResult = result
                onCompleted(result)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delete Landmarks") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isDeleting) {
                        Text(if (completedResult == null) "Cancel" else "Close", color = Color(0xFF007AFF), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                },
                actions = {
                    if (completedResult != null) {
                        TextButton(onClick = onDismiss) {
                            Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                        }
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7) // iOS light gray background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section 1: Selected Landmarks
            item {
                SettingsSection(footer = "Deleting a landmark begins backend cleanup for its promotions, dataset files, and cluster mappings. This cannot be undone.") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${landmarks.size} landmark${if (landmarks.size == 1) "" else "s"}", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        landmarks.forEach { landmark ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(displayLabel(landmark), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(landmark.landmarkId, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            // Section 2: Confirmation Input
            if (completedResult == null) {
                item {
                    SettingsSection(header = "Confirmation", footer = "Type exactly: $requiredConfirmationText") {
                        OutlinedTextField(
                            value = confirmationText,
                            onValueChange = { confirmationText = it },
                            placeholder = { Text(requiredConfirmationText, color = Color.LightGray) },
                            enabled = !isDeleting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    }
                }

                // Section 3: Delete Button
                item {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            deleteLandmarks()
                        },
                        enabled = isConfirmationValid && !isDeleting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete ${landmarks.size} Landmark${if (landmarks.size == 1) "" else "s"}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        }
                    }
                }
            }

            // Section 4: Deleting Progress
            if (isDeleting) {
                item {
                    SettingsSection {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Deleting landmarks...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (progressText != null) {
                                    Text(progressText!!, fontSize = 13.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            // Section 5: Results
            if (completedResult != null) {
                item {
                    SettingsSection(header = "Result") {
                        val result = completedResult!!
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (result.failedLandmarks.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (result.failedLandmarks.isEmpty()) Color(0xFF34C759) else Color(0xFFFFA500)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Deleted ${result.successfulCount} of ${landmarks.size} landmarks", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    if (result.failedLandmarks.isNotEmpty()) {
                                        Text("Failed landmarks remain selected so you can retry them.", fontSize = 12.sp, color = Color.Gray)
                                    }
                                }
                            }

                            if (result.failedLandmarks.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                result.failedLandmarks.forEach { failure ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(if (failure.landmarkLabel.isEmpty()) failure.landmarkId else failure.landmarkLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(failure.message, fontSize = 12.sp, color = Color.Gray)
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

private fun displayLabel(landmark: BusinessLandmark): String {
    return if (landmark.label.isEmpty()) "Untitled Landmark" else landmark.label
}