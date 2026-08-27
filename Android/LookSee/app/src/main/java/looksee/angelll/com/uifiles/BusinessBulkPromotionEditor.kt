package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessBulkLandmarkFailure
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.models.BusinessLandmarkService
import looksee.angelll.com.models.BusinessPromotionService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class BusinessBulkPromotionResult(
    val promotionName: String,
    val successfulLandmarkIds: Set<String>,
    val failedLandmarks: List<BusinessBulkLandmarkFailure>,
    val updatedLandmarks: List<BusinessLandmark>
) {
    val successfulCount: Int get() = successfulLandmarkIds.size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessBulkPromotionEditor(
    landmarks: List<BusinessLandmark>,
    onCompleted: (BusinessBulkPromotionResult) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }

    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf(LocalDate.now().plusDays(30)) }

    var enabled by remember { mutableStateOf(true) }
    var enablePromotionsOnLandmarks by remember { mutableStateOf(true) }

    var isSaving by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var completedResult by remember { mutableStateOf<BusinessBulkPromotionResult?>(null) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val promotionService = remember { BusinessPromotionService() }
    val landmarkService = remember { BusinessLandmarkService() }
    val coroutineScope = rememberCoroutineScope()

    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    val savePromotion: () -> Unit = {
        if (!isSaving) {
            val cleanedName = name.trim()
            val cleanedDescription = description.trim()
            val cleanedImageUrl = imageUrl.trim()

            if (cleanedName.isEmpty()) {
                errorMessage = "Promotion name is required."
            } else if (endDate.isBefore(startDate)) {
                errorMessage = "End date cannot be before the start date."
            } else {
                isSaving = true
                errorMessage = null
                completedResult = null

                coroutineScope.launch {
                    val successfulLandmarkIds = mutableSetOf<String>()
                    val failedLandmarks = mutableListOf<BusinessBulkLandmarkFailure>()
                    val updatedLandmarksById = mutableMapOf<String, BusinessLandmark>()

                    for ((index, landmark) in landmarks.withIndex()) {
                        progressText = "Landmark ${index + 1} of ${landmarks.size}: ${displayLabel(landmark)}"

                        try {
                            if (enablePromotionsOnLandmarks) {
                                val updatedLandmark = landmarkService.updateLandmarkSettings(
                                    landmarkId = landmark.landmarkId,
                                    isActive = null,
                                    promotionEnabled = true
                                )
                                updatedLandmarksById[landmark.landmarkId] = updatedLandmark
                            }

                            promotionService.createPromotion(
                                landmarkId = landmark.landmarkId,
                                name = cleanedName,
                                description = cleanedDescription,
                                imageUrl = cleanedImageUrl,
                                startDate = startDate.format(dateFormatter),
                                endDate = endDate.format(dateFormatter),
                                enabled = enabled
                            )

                            successfulLandmarkIds.add(landmark.landmarkId)
                        } catch (e: Exception) {
                            failedLandmarks.add(
                                BusinessBulkLandmarkFailure(
                                    landmarkId = landmark.landmarkId,
                                    landmarkLabel = displayLabel(landmark),
                                    error = e.localizedMessage ?: "Unknown error"
                                )
                            )
                        }
                    }

                    val result = BusinessBulkPromotionResult(
                        promotionName = cleanedName,
                        successfulLandmarkIds = successfulLandmarkIds,
                        failedLandmarks = failedLandmarks,
                        updatedLandmarks = updatedLandmarksById.values.toList()
                    )

                    isSaving = false
                    progressText = null
                    completedResult = result
                    onCompleted(result)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Promotion") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Text(if (completedResult == null) "Cancel" else "Close", color = Color(0xFF007AFF), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                },
                actions = {
                    if (completedResult == null) {
                        TextButton(
                            onClick = savePromotion,
                            enabled = !isSaving && landmarks.isNotEmpty() && name.trim().isNotEmpty()
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            else Text("Apply", fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                        }
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Selected Landmarks
            item {
                SettingsSection(
                    header = "Selected Landmarks",
                    footer = "Selection is captured when this sheet opens, so changing the search behind it cannot change this operation."
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${landmarks.size} landmark${if (landmarks.size == 1) "" else "s"}", fontWeight = FontWeight.Bold)
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

            // Promotion Details
            item {
                SettingsSection(
                    header = "Promotion Details",
                    footer = "A separate promotion record will be created for every selected landmark."
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        placeholder = { Text("Promotion name") },
                        enabled = !isSaving && completedResult == null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        placeholder = { Text("Promotion description") },
                        enabled = !isSaving && completedResult == null,
                        minLines = 3, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = imageUrl, onValueChange = { imageUrl = it },
                        placeholder = { Text("Image URL (optional)") },
                        enabled = !isSaving && completedResult == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Promotion enabled", fontSize = 16.sp)
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !isSaving && completedResult == null)
                    }
                }
            }

            // Landmark Settings
            item {
                SettingsSection(
                    header = "Landmark Settings",
                    footer = "A promotion only appears in the scan popup when the promotion record and the landmark's Promotions Enabled setting are both on."
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Turn on promotions for selected landmarks", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(checked = enablePromotionsOnLandmarks, onCheckedChange = { enablePromotionsOnLandmarks = it }, enabled = !isSaving && completedResult == null)
                    }
                }
            }

            // Dates
            item {
                SettingsSection(header = "Dates") {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving && completedResult == null) { showStartDatePicker = true }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Start Date", fontSize = 16.sp)
                        Text(startDate.format(dateFormatter), color = Color.Gray)
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving && completedResult == null) { showEndDatePicker = true }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("End Date", fontSize = 16.sp)
                        Text(endDate.format(dateFormatter), color = Color.Gray)
                    }
                }
            }

            // Progress/Error/Result Areas
            if (isSaving) {
                item {
                    SettingsSection {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Applying promotion...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (progressText != null) Text(progressText!!, fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            if (completedResult != null) {
                item {
                    val result = completedResult!!
                    SettingsSection(header = "Result") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (result.failedLandmarks.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (result.failedLandmarks.isEmpty()) Color(0xFF34C759) else Color(0xFFFFA500)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Applied to ${result.successfulCount} of ${landmarks.size} landmarks", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                                        Text(failure.error, fontSize = 12.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                item {
                    SettingsSection {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(errorMessage!!, fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }

        // Native Android Date Picker Dialogs
        if (showStartDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { showStartDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let {
                            startDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        }
                        showStartDatePicker = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { showStartDatePicker = false }) { Text("Cancel") } }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showEndDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { showEndDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let {
                            endDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        }
                        showEndDatePicker = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { showEndDatePicker = false }) { Text("Cancel") } }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

private fun displayLabel(landmark: BusinessLandmark): String {
    return if (landmark.label.isEmpty()) "Untitled Landmark" else landmark.label
}