package looksee.angelll.com.uifiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import io.sentry.Sentry
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*


private val backgroundColor = Color(0xFF14141F)
private val cardColor = Color(0xFF1F1F2E)
private val primaryColor = Color(0xFF387DFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportIssueView(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(ReportCategory.UI_BUG) }
    var severity by remember { mutableStateOf(ReportSeverity.MEDIUM) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var screenshotUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        screenshotUri = uri
    }

    val isValid = title.isNotBlank() && description.isNotBlank()

    val handleSend = {
        val deviceInfo = ReportDeviceInfo.current(context)
        val report = BugReport(
            category = category,
            severity = severity,
            title = title,
            description = description
        )
        
        val draft = MailReportService.buildDraft(report, vm.userEmail, deviceInfo)
        
        // 🚀 Uses your secure CloudFront endpoint to route this to Jira
        val payload = org.json.JSONObject().apply {
            put("email", vm.userEmail.ifEmpty { "unknown@user.com" })
            put("category", category.displayName)
            put("severity", severity.name)
            put("title", "[${category.displayName}] $title")
            put("description", draft.body)
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val url = java.net.URL("https://d11vl3v9w133rh.cloudfront.net/support/report")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                
                val responseCode = conn.responseCode
                println("✅ Bug report routed to Jira via CloudFront: $responseCode")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
                title = { Text("Report a Bug", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Category Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("What kind of issue?")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(180.dp) // Fixed height for the 2x2 grid
                ) {
                    items(ReportCategory.entries) { cat ->
                        CategoryChip(
                            option = cat,
                            isSelected = category == cat,
                            onClick = { category = cat }
                        )
                    }
                }
            }

            // Severity Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Severity")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReportSeverity.entries.forEach { sev ->
                        SeverityChip(
                            option = sev,
                            isSelected = severity == sev,
                            onClick = { severity = sev },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Details Section
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Title")
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Short summary of the issue", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = cardColor,
                            unfocusedContainerColor = cardColor,
                            disabledContainerColor = cardColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Description")
                    TextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("What happened? What did you expect instead?", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        minLines = 4,
                        maxLines = 10,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = cardColor,
                            unfocusedContainerColor = cardColor,
                            disabledContainerColor = cardColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }

            // Screenshot Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Screenshot")
                
                if (screenshotUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(cardColor)
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(screenshotUri),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        IconButton(
                            onClick = { screenshotUri = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(32.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Button(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor.copy(alpha = 0.1f),
                        contentColor = primaryColor
                    )
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (screenshotUri == null) "Attach a Screenshot" else "Replace Screenshot", fontWeight = FontWeight.Bold)
                }
            }

            // Submit Button
            Button(
                onClick = handleSend,
                enabled = isValid,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Submit Report", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Gray,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
fun CategoryChip(
    option: ReportCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (option) {
        ReportCategory.UI_BUG -> Icons.Default.WebAssetOff
        ReportCategory.DETECTION_BUG -> Icons.Default.CenterFocusStrong
        ReportCategory.UPLOAD_BUG -> Icons.Default.ArrowCircleUp
        ReportCategory.COPYRIGHT -> Icons.Default.Copyright
        ReportCategory.OTHER -> Icons.Default.QuestionMark
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) primaryColor else cardColor)
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = option.displayName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SeverityChip(
    option: ReportSeverity,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when (option) {
        ReportSeverity.LOW -> Color.Green
        ReportSeverity.MEDIUM -> Color.Yellow
        ReportSeverity.HIGH -> Color(0xFFFFA500)
        ReportSeverity.CRITICAL -> Color.Red
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (isSelected) color else color.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = option.displayName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Color.White else color
        )
    }
}



// --- Merged from BugReportModels.kt ---
enum class ReportCategory(
    val wireValue: String,
    val displayName: String,
    val jiraLabel: String,
) {
    UI_BUG("ui_bug", "UI Bug", "mobile-ui"),
    DETECTION_BUG("detection_bug", "Detection Bug", "detection"),
    UPLOAD_BUG("upload_bug", "Upload Bug", "upload-pipeline"),
    COPYRIGHT("copyright", "Copyright / DMCA", "copyright"),
    OTHER("other", "Other", "unclassified"),
}

enum class ReportSeverity(
    val wireValue: String,
    val displayName: String,
    val jiraPriority: String,
) {
    LOW("low", "Low", "Low"),
    MEDIUM("medium", "Medium", "Medium"),
    HIGH("high", "High", "High"),
    CRITICAL("critical", "Critical", "Highest"),
}

data class BugReport(
    val category: ReportCategory,
    val severity: ReportSeverity,
    val title: String,
    val description: String,
    val screenshotJpeg: ByteArray? = null,
)

data class ReportDeviceInfo(
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val deviceModel: String,
) {
    companion object {
        fun current(context: Context): ReportDeviceInfo {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            return ReportDeviceInfo(
                appVersion = packageInfo.versionName ?: "unknown",
                buildNumber = buildNumber,
                osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" },
                deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                    .map { it.trim() }
                    .filter(String::isNotEmpty)
                    .joinToString(" ")
                    .ifBlank { "unknown" },
            )
        }
    }
}

// --- Merged from MailReportService.kt ---
data class MailReportDraft(
    val recipients: List<String>,
    val subject: String,
    val body: String,
    val attachmentJpeg: ByteArray?,
    val attachmentFilename: String? = attachmentJpeg?.let { "screenshot.jpg" },
)

/**
 * Platform-neutral report-email builder. The UI layer owns launching Android's
 * email intent and granting a FileProvider URI when a screenshot is attached.
 */
object MailReportService {
    val recipients = listOf("Looksee.support@informationoutpost.com")

    fun subject(report: BugReport): String =
        "[${report.category.displayName} · ${report.severity.displayName}] ${report.title}"

    fun body(
        report: BugReport,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): String = """
        ${report.description}

        ---
        Category: ${report.category.displayName}
        Severity: ${report.severity.displayName}
        Reported by: ${userEmail.orEmpty()}
        App version: ${deviceInfo.appVersion} (${deviceInfo.buildNumber})
        OS: Android ${deviceInfo.osVersion}
        Device: ${deviceInfo.deviceModel}
    """.trimIndent()

    fun buildDraft(
        report: BugReport,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): MailReportDraft = MailReportDraft(
        recipients = recipients,
        subject = subject(report),
        body = body(report, userEmail, deviceInfo),
        attachmentJpeg = report.screenshotJpeg,
    )
}
