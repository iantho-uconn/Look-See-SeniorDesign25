package looksee.angelll.com.uifiles

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportIssueView(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(ReportCategory.OTHER) }
    var severity by remember { mutableStateOf(ReportSeverity.LOW) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var screenshotUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        screenshotUri = uri
    }

    val handleSend = {
        val deviceInfo = ReportDeviceInfo.current(context)
        val report = BugReport(
            category = category,
            severity = severity,
            title = title,
            description = description
        )
        
        val draft = MailReportService.buildDraft(report, vm.userEmail, deviceInfo)
        
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, draft.recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            screenshotUri?.let {
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        
        context.startActivity(Intent.createChooser(intent, "Send Bug Report"))
        onDismiss()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report an Issue") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick = handleSend,
                        enabled = title.isNotEmpty() && description.isNotEmpty()
                    ) {
                        Text("Send", fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(header = "Category") {
                ReportCategory.entries.forEach { cat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { category = cat }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = category == cat, onClick = { category = cat })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(cat.displayName)
                    }
                    if (cat != ReportCategory.entries.last()) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            SettingsSection(header = "Severity") {
                ReportSeverity.entries.forEach { sev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { severity = sev }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = severity == sev, onClick = { severity = sev })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(sev.displayName)
                    }
                    if (sev != ReportSeverity.entries.last()) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            SettingsSection(header = "Details") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Summary") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("Describe the issue...") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    minLines = 5
                )
            }

            SettingsSection(header = "Attachment") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { photoPickerLauncher.launch("image/*") }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFF007AFF))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(if (screenshotUri == null) "Attach Screenshot" else "Change Screenshot")
                    Spacer(modifier = Modifier.weight(1f))
                    if (screenshotUri != null) Icon(Icons.Default.Check, tint = Color(0xFF34C759), contentDescription = null)
                }
            }
        }
    }
}
