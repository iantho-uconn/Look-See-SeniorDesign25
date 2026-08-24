package looksee.angelll.com.uifiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportIssueView(
    vm: AuthViewModel,
    initialScreenshot: Bitmap? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Assuming these Enums map to the Swift equivalents
    var category by remember { mutableStateOf<ReportCategory?>(null) }
    var severity by remember { mutableStateOf<ReportSeverity?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var screenshot by remember { mutableStateOf(initialScreenshot) }

    var showNoMailAlert by remember { mutableStateOf(false) }
    var isResolvingIdentity by remember { mutableStateOf(false) }
    var verifiedReplyToEmail by remember { mutableStateOf<String?>(null) }

    val primaryColor = Color(0xFF387DFF) // 0.22, 0.49, 1.00
    val groupedBg = Color(0xFFF2F2F7)
    val secondaryGroupedBg = Color.White

    val isValid = title.trim().isNotEmpty() && description.trim().isNotEmpty() && category != null && severity != null

    val currentReport = remember(category, severity, title, description, screenshot) {
        // Expected ghost error until BugReport is added
        BugReport(
            category = category,
            severity = severity,
            title = title.trim(),
            description = description.trim(),
            screenshot = screenshot
        )
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it))
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                    }
                    withContext(Dispatchers.Main) {
                        screenshot = bitmap
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun submitReport() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        focusManager.clearFocus()

        if (isResolvingIdentity) return
        isResolvingIdentity = true

        coroutineScope.launch(Dispatchers.IO) {
            val verifiedEmail = try { AuthService.shared.fetchVerifiedEmail() } catch (_: Exception) { null }

            withContext(Dispatchers.Main) {
                verifiedReplyToEmail = verifiedEmail ?: vm.userEmail
                isResolvingIdentity = false

                if (MailReportService.canSendMail(context)) {
                    // Trigger Android Email Intent
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "message/rfc822"
                        putExtra(Intent.EXTRA_EMAIL, MailReportService.recipients.toTypedArray())
                        putExtra(Intent.EXTRA_SUBJECT, MailReportService.subject(currentReport))
                        putExtra(Intent.EXTRA_TEXT, MailReportService.body(currentReport, verifiedReplyToEmail ?: vm.userEmail))
                    }
                    try {
                        context.startActivity(Intent.createChooser(intent, "Send Report"))
                        onDismiss() // Close the sheet assuming intent was fired
                    } catch (_: Exception) {
                        showNoMailAlert = true
                    }
                } else {
                    showNoMailAlert = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report a Bug", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onDismiss()
                    }) {
                        Text("Cancel", fontSize = 16.sp, color = primaryColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedBg)
            )
        },
        containerColor = groupedBg,
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
            }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Category Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WHAT KIND OF ISSUE?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp).heightIn(max = 400.dp),
                    userScrollEnabled = false
                ) {
                    // Expected ghost error until ReportCategory is added
                    items(ReportCategory.entries.toTypedArray()) { option ->
                        val isSelected = category == option
                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                category = option
                            },
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) primaryColor else secondaryGroupedBg,
                                contentColor = if (isSelected) Color.White else Color.Black
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(option.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                Text(option.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }

            // Severity Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SEVERITY", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Expected ghost error until ReportSeverity is added
                    ReportSeverity.entries.forEach { option ->
                        val isSelected = severity == option
                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                severity = option
                            },
                            modifier = Modifier.weight(1f).height(45.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) option.color else option.color.copy(alpha = 0.12f),
                                contentColor = if (isSelected) Color.White else option.color
                            )
                        ) {
                            Text(option.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Details Section
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TITLE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Short summary of the issue", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = secondaryGroupedBg,
                            unfocusedContainerColor = secondaryGroupedBg,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DESCRIPTION", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    TextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("What happened? What did you expect instead?", color = Color.Gray) },
                        minLines = 4,
                        maxLines = 10,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = secondaryGroupedBg,
                            unfocusedContainerColor = secondaryGroupedBg,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
            }

            // Screenshot Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SCREENSHOT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))

                screenshot?.let { img ->
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.TopEnd) {
                        Image(
                            bitmap = img.asImageBitmap(),
                            contentDescription = "Screenshot Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                        )
                        IconButton(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                screenshot = null
                            },
                            modifier = Modifier.padding(10.dp).size(32.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Button(
                    onClick = { photoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor.copy(alpha = 0.10f), contentColor = primaryColor)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Text(if (screenshot == null) "Attach a Screenshot" else "Replace Screenshot", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Submit Button
            Button(
                onClick = { submitReport() },
                enabled = isValid && !isResolvingIdentity,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isResolvingIdentity) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = null, tint = Color.White)
                    }
                    Text("Submit Report", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    if (showNoMailAlert) {
        AlertDialog(
            onDismissRequest = { showNoMailAlert = false },
            title = { Text("Mail Not Set Up") },
            text = { Text("Add a Mail account in Settings to send reports from the app, or email us directly at ${MailReportService.recipients.firstOrNull() ?: ""}.") },
            confirmButton = { TextButton(onClick = { showNoMailAlert = false }) { Text("OK") } }
        )
    }
}

// MARK: - Report Button + Screenshot Capture
@Composable
fun ReportIssueButton() {
    var showReportSheet by remember { mutableStateOf(false) }
    var capturedScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    val view = LocalView.current

    Button(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            // Synchronously captures the UI drawing cache of the current screen using the modern KTX extension
            val bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            capturedScreenshot = bitmap
            showReportSheet = true
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Unspecified)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BugReport, contentDescription = null)
            Text("Report a Bug")
        }
    }

    if (showReportSheet) {
        // Red unresolved error is expected until AuthViewModel is provided
        ReportIssueView(
            vm = AuthViewModel(),
            initialScreenshot = capturedScreenshot,
            onDismiss = { showReportSheet = false }
        )
    }
}