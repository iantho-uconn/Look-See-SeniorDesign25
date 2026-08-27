package looksee.angelll.com.uifiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.models.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale

// Custom Colors matching your iOS theme
val PrimaryBlue = Color(0xFF387DFF)
val DarkBackground = Color(0xFF0F0F1A)
val SecondaryGrouped = Color(0xFF1C1C1E)

class SettingsPresenter {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showLoginSheet by mutableStateOf(false)
    var showSignUpSheet by mutableStateOf(false)
    var showUserProfileEditor by mutableStateOf(false)
    var justPurchased by mutableStateOf(false)
}

@Composable
fun SettingsScreen(
    vm: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val presenter = remember { SettingsPresenter() }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var showCancelAlert by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    LaunchedEffect(isFullyLoggedIn) {
        if (!presenter.justPurchased) {
            vm.checkSession()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. PROFILE HEADER
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = SecondaryGrouped,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (!isFullyLoggedIn) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(60.dp), tint = Color.Gray)
                        Column {
                            Text("Guest User", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Sign in to sync your data", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                presenter.showUserProfileEditor = true
                            }
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.DarkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            if (vm.profileImageUrl.isNotEmpty()) {
                                RemoteImage(
                                    url = vm.profileImageUrl,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (vm.username.isEmpty()) "Set Username" else "@${vm.username}",
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                                )
                                if (vm.hasActiveSubscription) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(
                                text = if (vm.hasActiveSubscription) "Verified Subscriber" else "Free Account",
                                fontSize = 14.sp,
                                color = if (vm.hasActiveSubscription) PrimaryBlue else Color.Gray,
                                fontWeight = if (vm.hasActiveSubscription) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFC7C7CC))
                    }
                }
            }

            // 2. BUSINESS MANAGEMENT
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "BUSINESS MANAGEMENT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )

                Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp)) {
                    Column {
                        if (isFullyLoggedIn && vm.hasActiveSubscription) {
                            SettingsRow(icon = Icons.Default.Business, iconBg = PrimaryBlue, title = "Manage My Landmarks", subtitle = "View the landmarks assigned to your account.") {
                                onNavigate("BusinessLandmarksView")
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                            SettingsRow(icon = Icons.Default.GeneratingTokens, iconBg = Color(0xFFFFA500), title = "Tokens (${vm.tokenBalance})", subtitle = "Buy tokens to update your inventory.") {
                                presenter.subscriptionStartingTab = 1
                                presenter.showSubscriptionFlow = true
                            }
                        } else {
                            SettingsRow(icon = Icons.Default.Lock, iconBg = Color.Gray, title = "Business Tools Locked", subtitle = "Subscribe to a plan to unlock landmarks.") {
                                presenter.subscriptionStartingTab = 0
                                presenter.showSubscriptionFlow = true
                            }
                        }
                    }
                }
            }

            // 3. ACCOUNT
            if (isFullyLoggedIn) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "ACCOUNT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )

                    Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp)) {
                        Column {
                            if (vm.hasActiveSubscription) {
                                val businessSubtitle = if (vm.storeName.isEmpty()) "Update store name and phone number." else vm.storeName
                                SettingsRow(icon = Icons.Default.Storefront, iconBg = PrimaryBlue, title = "Business Profile", subtitle = businessSubtitle) {
                                    onNavigate("BusinessProfileView")
                                }
                                HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                            }
                            SettingsRow(icon = Icons.Default.VpnKey, iconBg = Color.Gray, title = "Account & Security", subtitle = "Change your email or password.") {
                                onNavigate("AccountSecurityView")
                            }
                        }
                    }
                }
            }

            // 4. MEMBERSHIP
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "MEMBERSHIP",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )

                if (!vm.hasActiveSubscription || !isFullyLoggedIn) {
                    GuestPromoCard(presenter, isFullyLoggedIn)
                } else {
                    Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Current Plan", fontSize = 16.sp, color = Color.White)
                                Text("Verified Subscriber", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Status", fontSize = 16.sp, color = Color.White)
                                Text("Active", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { presenter.subscriptionStartingTab = 0; presenter.showSubscriptionFlow = true },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue.copy(alpha = 0.15f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Manage", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { showCancelAlert = true },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Cancel", color = Color.Red, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 5. OTHER SETTINGS
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Surface(color = SecondaryGrouped, shape = RoundedCornerShape(16.dp)) {
                    Column {
                        SettingsRow(icon = Icons.AutoMirrored.Filled.Help, iconBg = Color(0xFFFFA500), title = "Help & Support") {}
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                        SettingsRow(icon = Icons.Default.PrivacyTip, iconBg = Color(0xFF9C27B0), title = "Privacy Policy") {}
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                        SettingsRow(icon = Icons.Default.Description, iconBg = Color(0xFF4CAF50), title = "Terms of Service") {}
                    }
                }
            }

        }

        // Full Screen Loading Overlay
        if (isCancelling) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("Canceling Plan...", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showCancelAlert) {
        AlertDialog(
            onDismissRequest = { showCancelAlert = false },
            title = { Text("Cancel Subscription?") },
            text = { Text("Your business features will be disabled immediately.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelAlert = false
                    isCancelling = true
                    coroutineScope.launch {
                        vm.cancelSubscription()
                        isCancelling = false
                    }
                }) { Text("Cancel Plan", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAlert = false }) { Text("Keep Plan", color = Color.White) }
            },
            containerColor = SecondaryGrouped,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }

    if (presenter.showUserProfileEditor) {
        UserProfileEditSheet(vm) { presenter.showUserProfileEditor = false }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, iconBg: Color, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.DarkGray)
    }
}

@Composable
fun GuestPromoCard(presenter: SettingsPresenter, isFullyLoggedIn: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF142659), Color(0xFF0D0D1F))))
            .border(1.dp, Brush.linearGradient(listOf(PrimaryBlue.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(PrimaryBlue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color.White)
                }
                Column {
                    Text("Join LookSee", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Upload landmarks and manage data. Free trial available.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { presenter.showSubscriptionFlow = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Sign up", fontWeight = FontWeight.Bold)
                }
                if (!isFullyLoggedIn) {
                    Button(
                        onClick = { presenter.showLoginSheet = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Log In", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// MARK: - UserProfileEditSheet (Username & Avatar)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditSheet(vm: AuthViewModel, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var draftUsername by remember { mutableStateOf(vm.username) }
    var errorMessage by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    var showPhotoActionSheet by remember { mutableStateOf(false) }
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var logoRemoved by remember { mutableStateOf(false) }

    // Photo Pickers
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { logoBitmap = loadBitmapFromUri(context, uri); logoRemoved = false }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) { logoBitmap = bitmap; logoRemoved = false }
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Box(modifier = Modifier.fillMaxSize().background(DarkBackground).clickable { focusManager.clearFocus() }) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Spacer(Modifier.height(20.dp))

                // Avatar Section
                Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.clickable { showPhotoActionSheet = true }) {
                    Box(
                        modifier = Modifier.size(114.dp).clip(CircleShape).border(2.dp, Brush.linearGradient(listOf(PrimaryBlue, Color.Magenta)), CircleShape).background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoBitmap != null) {
                            Image(bitmap = logoBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else if (vm.profileImageUrl.isNotEmpty() && !logoRemoved) {
                            RemoteImage(url = vm.profileImageUrl, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(0.7f))
                        }
                    }
                    Box(
                        modifier = Modifier.size(36.dp).offset(x = 2.dp, y = 2.dp).clip(CircleShape).background(PrimaryBlue).border(3.dp, DarkBackground, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                // Username Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USERNAME", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    OutlinedTextField(
                        value = draftUsername,
                        onValueChange = { draftUsername = it.lowercase(Locale.ROOT).filter { char -> char.isLetterOrDigit() || char == '_' } },
                        leadingIcon = { Text("@", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(0.05f),
                            unfocusedContainerColor = Color.White.copy(0.05f),
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Usernames must be letters, numbers, and underscores only.", fontSize = 12.sp, color = Color.Gray)
                }

                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSaving = true
                        errorMessage = ""

                        coroutineScope.launch {
                            val base64String = logoBitmap?.let { resizeAndConvertToBase64(it) } ?: if(logoRemoved) "REMOVE" else null

                            val result = vm.updateUserIdentity(
                                newUsername = draftUsername,
                                emailToSave = vm.userEmail,
                                profileBase64 = if(base64String == "REMOVE") null else base64String
                            )

                            isSaving = false
                            if (result.first) onDismiss() else errorMessage = result.second ?: "Failed to update profile."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, disabledContainerColor = Color.DarkGray),
                    shape = RoundedCornerShape(16.dp),
                    enabled = draftUsername.isNotEmpty() && !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Photo Action Sheet (Bottom)
            AnimatedVisibility(
                visible = showPhotoActionSheet,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp).padding(bottom = 30.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1C1C24))
                ) {
                    Text("Change Profile Picture", color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                    HorizontalDivider(color = Color.White.copy(0.1f))
                    TextButton(onClick = { showPhotoActionSheet = false; cameraLauncher.launch(null) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("Take Photo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = Color.White.copy(0.1f))
                    TextButton(onClick = { showPhotoActionSheet = false; galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("Choose from Library", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (logoBitmap != null || vm.profileImageUrl.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(0.1f))
                        TextButton(onClick = { showPhotoActionSheet = false; logoBitmap = null; logoRemoved = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Text("Remove Photo", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Native Dependencies & Helpers

// 1. Dependency-Free Native Image Loader (Replaces Coil)
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                bitmap = BitmapFactory.decodeStream(stream)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}

// 2. Safe Bitmap Loader
fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            @Suppress("DEPRECATION")
            android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}

// 3. Image Resizer & Base64 Encoder
fun resizeAndConvertToBase64(image: Bitmap): String {
    val maxDimension = 400f
    val ratio = minOf(maxDimension / image.width, maxDimension / image.height)
    val width = (image.width * ratio).toInt()
    val height = (image.height * ratio).toInt()

    val resizedBitmap = Bitmap.createScaledBitmap(image, width, height, true)
    val outputStream = ByteArrayOutputStream()
    // Compression quality matching Swift's 0.6 (60%)
    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
}


// MARK: - External Dependencies Stubs (To prevent Unresolved Reference errors)
// You should replace or connect these to your actual Kotlin files later.
@Composable
fun DeepSettingsView(isFullyLoggedIn: Boolean, vm: AuthViewModel) { /* Stub */ }
@Composable
fun BusinessProfileView(vm: AuthViewModel) { /* Stub */ }
