package looksee.angelll.com.uifiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.R
import looksee.angelll.com.models.*
import looksee.angelll.com.services.*
import looksee.angelll.com.subscription.SubscriptionPlans
import looksee.angelll.com.ui.theme.*
import looksee.angelll.com.viewmodels.*
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale

class SettingsPresenter {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showUserProfileEditor by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AuthViewModel,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val presenter = remember { SettingsPresenter() }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)

    var showCancelAlert by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    val dynamicPlanTitle = remember(vm.hasActiveSubscription, vm.userEmail) {
        if (!vm.hasActiveSubscription) "Free Account"
        else if (prefs.getBoolean("isFreeTrial_${vm.userEmail}", false)) "14-Day Free Trial"
        else "Verified Subscriber"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = { 
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Menu", fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp) 
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    actions = { Spacer(Modifier.width(48.dp)) }, // Balance the back button
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 40.dp)
            ) {
                // 1. PROFILE HEADER
                LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!isFullyLoggedIn) {
                        Row(
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigate("login")
                            }.padding(vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = Color.Gray
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Guest User", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Sign in to sync your data", fontSize = 14.sp, color = Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                presenter.showUserProfileEditor = true
                            }.padding(vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp).clip(CircleShape).background(AppleBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (vm.profileImageUrl.isNotEmpty()) {
                                    RemoteImage(url = vm.profileImageUrl, modifier = Modifier.fillMaxSize())
                                } else {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(32.dp))
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = if (vm.username.isEmpty()) "Set Username" else "@${vm.username}",
                                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                                    )
                                    if (vm.hasActiveSubscription) {
                                        Icon(Icons.Default.Verified, contentDescription = "Verified", tint = AppleBlue, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(text = dynamicPlanTitle, fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFC7C7CC), modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // 2. BUSINESS MANAGEMENT
                LookSeeSectionHeader("Business Management")
                LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (isFullyLoggedIn && vm.hasActiveSubscription) {
                        if (prefs.getBoolean("isFreeTrial_${vm.userEmail}", false)) {
                            TrialWarningCard()
                            Spacer(Modifier.height(16.dp))
                        }
                        
                        LookSeeRow(
                            icon = Icons.Default.Business,
                            iconContainerColor = AppleBlue,
                            title = "Manage My Landmarks",
                            subtitle = "View the landmarks assigned to your account."
                        ) {
                            onNavigate("BusinessLandmarksView")
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                        LookSeeRow(
                            icon = Icons.Default.Token,
                            iconContainerColor = Color(0xFFFFA500),
                            title = "Tokens (${vm.tokenBalance})",
                            subtitle = "Buy tokens to update your inventory."
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            presenter.subscriptionStartingTab = 1
                            presenter.showSubscriptionFlow = true
                        }
                    } else {
                        LookSeeRow(
                            icon = Icons.Default.Lock,
                            iconContainerColor = Color.Gray,
                            title = "Business Tools Locked",
                            subtitle = "Subscribe to a plan to unlock landmarks and tokens."
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            presenter.subscriptionStartingTab = 0
                            presenter.showSubscriptionFlow = true
                        }
                    }
                }

                // 3. ACCOUNT
                if (isFullyLoggedIn) {
                    LookSeeSectionHeader("Account")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        if (vm.hasActiveSubscription) {
                            val businessSubtitle = if (vm.storeName.isEmpty()) "Update store name and phone number." else vm.storeName
                            LookSeeRow(
                                icon = Icons.Default.Storefront,
                                iconContainerColor = AppleBlue,
                                title = "Business Profile",
                                subtitle = businessSubtitle
                            ) {
                                onNavigate("BusinessProfileView")
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                        } else {
                            LookSeeRow(
                                icon = Icons.Default.Lock,
                                iconContainerColor = Color.Gray,
                                title = "Business Profile Locked",
                                subtitle = "Subscribe to edit your public store info."
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                presenter.subscriptionStartingTab = 0
                                presenter.showSubscriptionFlow = true
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                        }
                        LookSeeRow(
                            icon = Icons.Default.VpnKey,
                            iconContainerColor = Color.Gray,
                            title = "Account & Security",
                            subtitle = "Change your email or password."
                        ) {
                            onNavigate("AccountSecurityView")
                        }
                    }
                }

                // 4. MEMBERSHIP
                if (!vm.hasActiveSubscription || !isFullyLoggedIn) {
                    Spacer(Modifier.height(24.dp))
                    GuestPromoCard(presenter, isFullyLoggedIn, onNavigate)
                } else {
                    LookSeeSectionHeader("Membership")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Plan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(dynamicPlanTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleBlue)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Status", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Active", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        }
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    presenter.subscriptionStartingTab = 0
                                    presenter.showSubscriptionFlow = true
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue.copy(alpha = 0.1f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Manage Plan", color = AppleBlue, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showCancelAlert = true
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(2.dp, Color.Red.copy(alpha = 0.8f))
                            ) {
                                Text("Cancel", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 5. GENERAL SETTINGS
                LookSeeSectionHeader("General")
                LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    LookSeeRow(icon = Icons.Default.BugReport, iconContainerColor = Color.Red, title = "Report a Bug") {
                        onNavigate("ReportIssueView")
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    LookSeeRow(icon = Icons.AutoMirrored.Filled.Help, iconContainerColor = Color(0xFFFFA500), title = "Help & Support") {
                        onNavigate("Help")
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    LookSeeRow(icon = Icons.Default.PrivacyTip, iconContainerColor = Color(0xFF9C27B0), title = "Privacy Policy") {
                        onNavigate("PrivacyPolicy")
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    LookSeeRow(icon = Icons.Default.Description, iconContainerColor = Color(0xFF4CAF50), title = "Terms of Service") {
                        onNavigate("TermsOfService")
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    LookSeeRow(icon = Icons.Default.Settings, iconContainerColor = Color.Gray, title = "Settings & Preferences") {
                        onNavigate("DeepSettings")
                    }
                }
            }
        }

        // Cancellation Overlay
        if (isCancelling) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text("Canceling Plan...", color = Color.White, fontWeight = FontWeight.Bold)
                    }
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
            containerColor = CardBackground,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }

    if (presenter.showUserProfileEditor) {
        UserProfileEditSheet(vm) { presenter.showUserProfileEditor = false }
    }
    
    if (presenter.showSubscriptionFlow) {
        SubscriptionPlans(
            account = SubscriptionAccountState(
                isSignedIn = vm.isSignedIn,
                userId = vm.userId,
                userEmail = vm.userEmail,
                hasActiveSubscription = vm.hasActiveSubscription,
                stripeSubscriptionId = vm.stripeSubscriptionId,
                tokenBalance = vm.tokenBalance,
                activePlanCents = vm.activePlanCents,
                activePlanYears = vm.activePlanYears,
                isFreeTrial = prefs.getBoolean("isFreeTrial_${vm.userEmail}", false)
            ),
            onClose = { presenter.showSubscriptionFlow = false },
            onRequireSignUp = {
                presenter.showSubscriptionFlow = false
                onNavigate("guest_signup")
            },
            onAccountUpdated = { update ->
                vm.tokenBalance += update.addedTokens
                if (update.subscriptionActivated) vm.hasActiveSubscription = true
                if (update.planCents != null) vm.activePlanCents = update.planCents
                if (update.planYears != null) vm.activePlanYears = update.planYears
            },
            startingTab = if (presenter.subscriptionStartingTab == 1) SubscriptionTab.TOKENS else SubscriptionTab.PLAN
        )
    }
}

@Composable
fun TrialWarningCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFA500).copy(alpha = 0.1f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
        Column {
            Text("Free Trial Active", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Please subscribe before your 14-day trial ends to prevent your landmarks from being deactivated.",
                fontSize = 13.sp, color = Color.Gray
            )
        }
    }
}

@Composable
fun GuestPromoCard(presenter: SettingsPresenter, isFullyLoggedIn: Boolean, onNavigate: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF142659), Color(0xFF0D0D1F))))
            .border(1.dp, Brush.linearGradient(listOf(AppleBlue.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(AppleBlue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Stars, contentDescription = null, tint = Color.White)
                }
                Column {
                    Text("Join LookSee", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Upload landmarks and manage data. Free trail available.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        presenter.showSubscriptionFlow = true
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Sign up", fontWeight = FontWeight.Bold)
                }
                if (!isFullyLoggedIn) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigate("login")
                        },
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
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) logoBitmap = loadBitmapFromUri(context, uri)
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F1A)).clickable { focusManager.clearFocus() }) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
                }

                // Avatar Section
                Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Box(
                        modifier = Modifier.size(114.dp).clip(CircleShape).border(2.dp, Brush.linearGradient(listOf(AppleBlue, Color.Magenta)), CircleShape).background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoBitmap != null) {
                            Image(bitmap = logoBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else if (vm.profileImageUrl.isNotEmpty()) {
                            RemoteImage(url = vm.profileImageUrl, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(0.7f))
                        }
                    }
                    Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(AppleBlue).border(3.dp, Color(0xFF0F0F1A), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                // Username Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USERNAME", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    OutlinedTextField(
                        value = draftUsername,
                        onValueChange = { draftUsername = it.lowercase(Locale.ROOT).filter { char -> char.isLetterOrDigit() || char == '_' } },
                        leadingIcon = { Text("@", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppleBlue) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(0.05f),
                            unfocusedContainerColor = Color.White.copy(0.05f),
                            focusedBorderColor = AppleBlue,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage.isNotEmpty()) Text(errorMessage, color = Color.Red)

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSaving = true
                        coroutineScope.launch {
                            val base64 = logoBitmap?.let { resizeAndConvertToBase64(it) }
                            val result = vm.updateUserIdentity(newUsername = draftUsername, emailToSave = vm.userEmail, profileBase64 = base64)
                            isSaving = false
                            if (result.first) onDismiss() else errorMessage = result.second ?: "Error updating profile."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

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
    if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier = modifier.background(Color.DarkGray))
}

fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            @Suppress("DEPRECATION")
            android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) { null }
}

fun resizeAndConvertToBase64(image: Bitmap): String {
    val maxDimension = 400f
    val ratio = minOf(maxDimension / image.width, maxDimension / image.height)
    val resized = Bitmap.createScaledBitmap(image, (image.width * ratio).toInt(), (image.height * ratio).toInt(), true)
    val outputStream = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
}
