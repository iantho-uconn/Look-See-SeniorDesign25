package looksee.angelll.com.uifiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.models.BusinessPromotion
import looksee.angelll.com.models.BusinessPromotionEditorContext
import looksee.angelll.com.models.BusinessPromotionService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessPromotionEditor(
    landmark: BusinessLandmark,
    context: BusinessPromotionEditorContext,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val existing = context.existingPromotion
    val defaultStartDate = LocalDate.now()
    val defaultEndDate = LocalDate.now().plusDays(30)
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun parseDateSafe(dateString: String?, fallback: LocalDate): LocalDate {
        if (dateString.isNullOrBlank()) return fallback
        return try { LocalDate.parse(dateString, dateFormatter) } catch (e: Exception) { fallback }
    }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var imageUrl by remember { mutableStateOf(existing?.imageUrl ?: "") }

    var startDate by remember { mutableStateOf(parseDateSafe(existing?.startDate, defaultStartDate)) }
    var endDate by remember { mutableStateOf(parseDateSafe(existing?.endDate, defaultEndDate)) }

    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val service = remember { BusinessPromotionService() }
    val coroutineScope = rememberCoroutineScope()

    val savePromotion: () -> Unit = {
        if (!isSaving) {
            val cleanedName = name.trim()
            val cleanedDescription = description.trim()
            val cleanedImageUrl = imageUrl.trim()

            if (cleanedName.isEmpty()) {
                errorMessage = "Promotion name is required."
            } else {
                isSaving = true
                errorMessage = null

                coroutineScope.launch {
                    try {
                        when (context) {
                            is BusinessPromotionEditorContext.Create -> {
                                service.createPromotion(
                                    landmarkId = landmark.landmarkId,
                                    name = cleanedName,
                                    description = cleanedDescription,
                                    imageUrl = cleanedImageUrl,
                                    startDate = startDate.format(dateFormatter),
                                    endDate = endDate.format(dateFormatter),
                                    enabled = enabled
                                )
                            }
                            is BusinessPromotionEditorContext.Edit -> {
                                service.updatePromotion(
                                    landmarkId = landmark.landmarkId,
                                    promotionId = context.promotion.id,
                                    name = cleanedName,
                                    description = cleanedDescription,
                                    imageUrl = cleanedImageUrl,
                                    startDate = startDate.format(dateFormatter),
                                    endDate = endDate.format(dateFormatter),
                                    enabled = enabled
                                )
                            }
                        }
                        isSaving = false
                        onSaved()
                        onDismiss()
                    } catch (e: Exception) {
                        errorMessage = e.localizedMessage
                        isSaving = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.navigationTitle) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Text("Cancel", color = Color(0xFF007AFF), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                },
                actions = {
                    TextButton(
                        onClick = savePromotion,
                        enabled = !isSaving && name.trim().isNotEmpty()
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Text(context.saveButtonTitle, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
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
            item {
                SettingsSection(header = "Landmark") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(landmark.label.ifEmpty { "Untitled Landmark" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(landmark.landmarkId, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            item {
                SettingsSection(
                    header = "Promotion Details",
                    footer = "This promotion can be shown for this landmark when both the promotion and the landmark's Promotions Enabled setting are on."
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        placeholder = { Text("Promotion name") },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        placeholder = { Text("Promotion description") },
                        enabled = !isSaving,
                        minLines = 3, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = imageUrl, onValueChange = { imageUrl = it },
                        placeholder = { Text("Image URL (optional)") },
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enabled", fontSize = 16.sp)
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !isSaving)
                    }
                }
            }

            item {
                SettingsSection(header = "Dates") {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving) { showStartDatePicker = true }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Start Date", fontSize = 16.sp)
                        Text(startDate.format(dateFormatter), color = Color.Gray)
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving) { showEndDatePicker = true }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("End Date", fontSize = 16.sp)
                        Text(endDate.format(dateFormatter), color = Color.Gray)
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

        // Native Android Date Pickers
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