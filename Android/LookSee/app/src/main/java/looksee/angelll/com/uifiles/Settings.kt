package looksee.angelll.com.uifiles

import looksee.angelll.com.subscription.SubscriptionAccountState
import looksee.angelll.com.subscription.SubscriptionTab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.services.*
import looksee.angelll.com.subscription.SubscriptionPlans
import looksee.angelll.com.ui.theme.*
import looksee.angelll.com.viewmodels.*

class SettingsPresenter {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showLoginSheet by mutableStateOf(false)
    var showSignUpSheet by mutableStateOf(false)
    var showUserProfileEditor by mutableStateOf(false)
    var signupStartsAsBusiness by mutableStateOf(false)

    var resumeCheckoutAction by mutableStateOf<String?>(null)
    var savedAddOnIndex by mutableIntStateOf(0)
    var savedTokenCount by mutableIntStateOf(0)
    var savedTokenCents by mutableIntStateOf(0)
    var justPurchased by mutableStateOf(false)

    var showGlobalNegativeCamera by mutableStateOf(false)
    var isReloadingModels by mutableStateOf(false)
    var showReloadSuccess by mutableStateOf(false)
    var isUploadingGlobalNegative by mutableStateOf(false)
}

fun normalizeCloudFrontUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return ""
    return rawUrl
        .replace("looksee-app-uploads.s3.us-east-1.amazonaws.com", "d1fjsmhmay9z6x.cloudfront.net")
        .replace("looksee-app-uploads.s3.amazonaws.com", "d1fjsmhmay9z6x.cloudfront.net")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AuthViewModel,
    authState: AuthState? = null,
    startWithSubscriptions: Boolean = false,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val presenter = remember {
        SettingsPresenter().apply {
            if (startWithSubscriptions) {
                showSubscriptionFlow = true
                subscriptionStartingTab = 0
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val prefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)

    var showCancelScreen by remember { mutableStateOf(false) }
    var showSignOutAlert by remember { mutableStateOf(false) }
    var isOpeningPrivacyOptions by remember { mutableStateOf(false) }
    var showPrivacyOptionsError by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    val dynamicPlanTitle = remember(vm.hasActiveSubscription, vm.userEmail) {
        if (!vm.hasActiveSubscription) "Free Account"
        else if (prefs.getBoolean("isFreeTrial_${vm.userEmail}", false)) "14-Day Free Trial"
        else "Verified Subscriber"
    }

    // Sync user session / details on appear (matches iOS .task)
    LaunchedEffect(isFullyLoggedIn) {
        if (!presenter.justPurchased) {
            if (!isFullyLoggedIn) {
                try { vm.checkSession() } catch (_: Exception) {}
            } else {
                try {
                    vm.fetchUserDetails()
                    vm.fetchUserUsageStats()
                } catch (_: Exception) {}
            }
        }
    }

    // Auto-resume subscription flow after a guest finishes signing in/up
    LaunchedEffect(isFullyLoggedIn) {
        if (isFullyLoggedIn && presenter.resumeCheckoutAction != null) {
            presenter.showLoginSheet = false
            presenter.showSignUpSheet = false
            delay(800)
            presenter.showSubscriptionFlow = true
        }
    }

    // Dismiss Settings if user signed out from DeepSettings
    LaunchedEffect(authState) {
        authState?.didSignOut?.collect { signedOut ->
            if (signedOut) {
                onDismiss()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
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
                    actions = { Spacer(Modifier.width(48.dp)) },
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
                            modifier = Modifier
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onNavigate("login")
                                }
                                .padding(vertical = 8.dp)
                                .fillMaxWidth(),
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
                                Text("Browsing anonymously", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    presenter.showUserProfileEditor = true
                                }
                                .padding(vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(AppleBlue.copy(alpha = 0.15f)),
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
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
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
                if (isFullyLoggedIn && vm.hasActiveSubscription) {
                    LookSeeSectionHeader("Business Management")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                            icon = Icons.Default.History,
                            iconContainerColor = AppleBlue,
                            title = "Scan History",
                            subtitle = "View your scanned landmarks."
                        ) {
                            onNavigate("HistoryView")
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                        LookSeeRow(
                            icon = Icons.Default.BarChart,
                            iconContainerColor = Color(0xFF5A27D5),
                            title = "Analytics",
                            subtitle = "Track daily views and engagement."
                        ) {
                            onNavigate("BusinessAnalyticsView")
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
                    }
                } else {
                    LookSeeSectionHeader("Business Management")
                    LookSeeCard(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                            Text(
                                if (vm.hasActiveSubscription) "Active" else "Inactive",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (vm.hasActiveSubscription) Color(0xFF4CAF50) else Color.Red
                            )
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
                                Text(
                                    if (!vm.hasActiveSubscription) "Sign Up" else "Manage Plan",
                                    color = AppleBlue,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showCancelScreen = true
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
                    LookSeeRow(icon = Icons.Default.BugReport, iconContainerColor = AppleBlue, title = "Report a Bug") {
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

                    val consentManager = remember { AdConsentManager(context) }
                    if (consentManager.isPrivacyOptionsRequired) {
                        LookSeeRow(
                            icon = Icons.Default.Tune,
                            iconContainerColor = Color(0xFF3F51B5),
                            title = "Privacy Options",
                            subtitle = if (isOpeningPrivacyOptions) "Opening privacy options…" else "Manage your ad privacy choices."
                        ) {
                            if (!isOpeningPrivacyOptions) {
                                isOpeningPrivacyOptions = true
                                showPrivacyOptionsError = false
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    consentManager.showPrivacyOptions(activity)
                                    isOpeningPrivacyOptions = false
                                } else {
                                    isOpeningPrivacyOptions = false
                                    showPrivacyOptionsError = true
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    }

                    LookSeeRow(icon = Icons.Default.Description, iconContainerColor = Color(0xFF4CAF50), title = "Terms of Service") {
                        onNavigate("TermsOfService")
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp), color = Color.White.copy(alpha = 0.1f))
                    LookSeeRow(
                        icon = Icons.Default.Settings,
                        iconContainerColor = Color.Gray,
                        title = "Settings & Preferences"
                    ) {
                        onNavigate("DeepSettings")
                    }
                }

                Text(
                    text = "Copyright © 1999-2026 Information Outpost, LLC.  All rights reserved.",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp, bottom = 40.dp)
                )
            }
        }

        if (showPrivacyOptionsError) {
            AlertDialog(
                onDismissRequest = { showPrivacyOptionsError = false },
                title = { Text("Privacy Options Unavailable", color = Color.White) },
                text = { Text("We couldn't open your ad privacy options. Please check your connection and try again.", color = Color.LightGray) },
                confirmButton = {
                    TextButton(onClick = { showPrivacyOptionsError = false }) { Text("OK", color = AppleBlue) }
                },
                containerColor = Color(0xFF1C1C1E)
            )
        }

        if (showSignOutAlert) {
            AlertDialog(
                onDismissRequest = { showSignOutAlert = false },
                title = { Text("Sign Out", color = Color.White) },
                text = { Text("Are you sure you want to sign out?", color = Color.LightGray) },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutAlert = false
                        vm.signOut()
                    }) { Text("Sign Out", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutAlert = false }) { Text("Cancel", color = Color(0xFF007AFF)) }
                },
                containerColor = Color(0xFF1C1C1E)
            )
        }

        if (presenter.showGlobalNegativeCamera) {
            NegativeVideoCameraView(
                uiTargetDuration = 10,
                minTotalTimeLimit = 2,
                onDone = { video ->
                    coroutineScope.launch {
                        presenter.isUploadingGlobalNegative = true
                        try {
                            BusinessLandmarkService().uploadGlobalNegativeVideo(video.file)
                        } catch (_: Exception) {
                        } finally {
                            presenter.isUploadingGlobalNegative = false
                            video.deleteLocalFile()
                            presenter.showGlobalNegativeCamera = false
                        }
                    }
                },
                onDismiss = { presenter.showGlobalNegativeCamera = false }
            )

            if (presenter.isUploadingGlobalNegative) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    LookSeeCard {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text("Uploading Global Negative...", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Please keep app open", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Full-Screen Safe Subscription Cancellation (Replaces destructive account deletion)
    if (showCancelScreen) {
        SubscriptionCancellationScreen(
            vm = vm,
            authState = authState,
            onDismiss = { showCancelScreen = false }
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
                presenter.signupStartsAsBusiness = true
                presenter.resumeCheckoutAction = "subscription"
                onNavigate("guest_signup")
            },
            onAccountUpdated = { update ->
                presenter.justPurchased = true
                presenter.resumeCheckoutAction = null
                vm.tokenBalance += update.addedTokens
                if (update.subscriptionActivated) vm.hasActiveSubscription = true
                if (update.planCents != null) vm.activePlanCents = update.planCents
                if (update.planYears != null) vm.activePlanYears = update.planYears
            },
            startingTab = if (presenter.subscriptionStartingTab == 1) SubscriptionTab.TOKENS else SubscriptionTab.PLAN
        )
    }
}

// --- Full-Screen Subscription Cancellation Screen (Ported from iOS Settings.swift) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionCancellationScreen(
    vm: AuthViewModel,
    authState: AuthState? = null,
    onDismiss: () -> Unit
) {
    var acknowledgement by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    var cancellationError by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val canConfirm = acknowledgement == "I understand" && !isCancelling && vm.isSignedIn

    LaunchedEffect(Unit) {
        try { vm.fetchUserUsageStats() } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = { if (!isCancelling) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCancelling,
            dismissOnClickOutside = !isCancelling
        )
    ) {
        Scaffold(
            containerColor = Color(0xFF0F0F1A),
            topBar = {
                TopAppBar(
                    title = { Text("Subscription", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                    navigationIcon = {
                        TextButton(onClick = onDismiss, enabled = !isCancelling) {
                            Text("Close", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Icon(
                    imageVector = if (completed) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = if (completed) Color(0xFF4CAF50) else Color(0xFFFFA500)
                )

                Text(
                    text = if (completed) "Renewal update confirmed" else "Before you cancel",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                val expiresAtSeconds = 0L

                if (completed) {
                    val cancelMsg = ""
                    if (cancelMsg.isNotEmpty()) {
                        Text(cancelMsg, color = Color.White, fontSize = 15.sp)
                    }
                    Text(
                        "You remain signed in. Your account will use the verified-user layout when paid access ends.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 15.sp
                    )
                    if (expiresAtSeconds > 0L) {
                        CancellationDateRow("Access ends", expiresAtSeconds)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    }
                } else {
                    Text(
                        "Canceling stops automatic renewal. You keep your current access through its expiration date.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 15.sp
                    )

                    if (expiresAtSeconds > 0L) {
                        CancellationDateRow("Access ends", expiresAtSeconds)
                        CancellationDateRow("Landmark exclusion begins", expiresAtSeconds + (15L * 86400L))
                        CancellationDateRow("Permanent deletion becomes eligible", expiresAtSeconds + (30L * 86400L))
                    } else {
                        Text(
                            "The schedule below starts when your paid access expires, not when you press this button. Your expiration date will be shown when billing confirms it.",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }

                    CancellationConsequenceRow(
                        "Your account stays",
                        "You stay signed in and can continue using the verified free account. Business features end when your paid access expires."
                    )
                    CancellationConsequenceRow(
                        "Tokens",
                        "Unused subscription tokens expire with their allowance period and do not return after a gap in paid access. Separately purchased tokens do not expire; they stay on your account but require an active paid plan to use. Trial tokens follow their trial expiration."
                    )
                    CancellationConsequenceRow(
                        "First 14 days after expiry",
                        "Your landmarks enter the subscription grace period, with expiration reminders."
                    )
                    CancellationConsequenceRow(
                        "15 days after expiry",
                        "Your landmarks are scheduled for exclusion from the app and models."
                    )
                    CancellationConsequenceRow(
                        "30 days after expiry",
                        "Your landmarks and uploaded media become eligible for permanent deletion. Do not rely on a delay in processing to recover them."
                    )
                    CancellationConsequenceRow(
                        "If you return later",
                        "Resubscribing before permanent deletion can stop subscription-related cleanup. It does not undo a separate deletion request. Once landmarks are permanently deleted, they cannot be restored: you must upload and create them again using tokens."
                    )

                    Text("Type I understand to confirm", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)

                    OutlinedTextField(
                        value = acknowledgement,
                        onValueChange = { acknowledgement = it },
                        placeholder = { Text("I understand", color = Color.Gray) },
                        enabled = !isCancelling,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedBorderColor = AppleBlue,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    if (cancellationError.isNotEmpty()) {
                        Text(cancellationError, color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            isCancelling = true
                            cancellationError = ""
                            coroutineScope.launch {
                                val success = try {
                                    vm.cancelSubscription()
                                } catch (e: Exception) {
                                    cancellationError = e.localizedMessage ?: "Failed to cancel subscription."
                                    false
                                }
                                isCancelling = false
                                if (success) {
                                    completed = true
                                } else if (cancellationError.isEmpty()) {
                                    val vmErr = ""
                                    cancellationError = vmErr.ifEmpty { "Could not confirm cancellation. Please try again." }
                                }
                            }
                        },
                        enabled = canConfirm,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            disabledContainerColor = Color.Red.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCancelling) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                if (isCancelling) "Confirming…" else "Confirm cancellation",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        enabled = !isCancelling,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Keep my plan", color = AppleBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CancellationConsequenceRow(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(detail, fontSize = 14.sp, color = Color.Gray, lineHeight = 20.sp)
    }
}

@Composable
private fun CancellationDateRow(title: String, timestampSecondsOrMs: Long) {
    val millis = if (timestampSecondsOrMs < 10_000_000_000L) timestampSecondsOrMs * 1000L else timestampSecondsOrMs
    val formatted = remember(millis) {
        SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(millis))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(formatted, fontSize = 14.sp, color = Color.LightGray)
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
                fontSize = 13.sp,
                color = Color.Gray
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
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF142659), Color(0xFF0D0D1F))
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(AppleBlue.copy(alpha = 0.5f), Color.Transparent)),
                RoundedCornerShape(24.dp)
            )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, CircleShape, ambientColor = AppleBlue, spotColor = AppleBlue)
                        .clip(CircleShape)
                        .background(AppleBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👑", fontSize = 20.sp, color = Color.White)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isFullyLoggedIn) "Upgrade to Business" else "Join LookSee",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        if (isFullyLoggedIn) "Unlock landmark management, uploads, promotions, and tokens."
                        else "Create a free account to save your profile and future progress.",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isFullyLoggedIn) {
                            presenter.subscriptionStartingTab = 0
                            presenter.showSubscriptionFlow = true
                        } else {
                            presenter.signupStartsAsBusiness = false
                            onNavigate("signup")
                        }
                    },
                    modifier = Modifier.weight(1.3f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (isFullyLoggedIn) "View Business Plans" else "Create Free Account",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
                if (!isFullyLoggedIn) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigate("login")
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Log In", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
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
    var showPhotoActionSheet by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) logoBitmap = loadBitmapFromUri(context, uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) logoBitmap = bitmap
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F1A))
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (!isSaving) onDismiss() }, enabled = !isSaving) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                    }
                    Text("Edit Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(64.dp))
                }

                // 1. Avatar Section with Camera Action Sheet Trigger
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showPhotoActionSheet = true
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(114.dp)
                                .clip(CircleShape)
                                .border(2.dp, Brush.linearGradient(listOf(AppleBlue, Color(0xFF9C27B0))), CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(AppleBlue.copy(alpha = 0.3f), Color(0xFF9C27B0).copy(alpha = 0.2f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (logoBitmap != null) {
                                Image(
                                    bitmap = logoBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(108.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (vm.profileImageUrl.isNotEmpty()) {
                                RemoteImage(
                                    url = vm.profileImageUrl,
                                    modifier = Modifier.size(108.dp).clip(CircleShape)
                                )
                            } else {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White.copy(0.7f))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AppleBlue)
                                .border(3.dp, Color(0xFF0F0F1A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    TextButton(onClick = { showPhotoActionSheet = true }) {
                        Text("Edit picture", color = AppleBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 2. Username Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USERNAME", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                    OutlinedTextField(
                        value = draftUsername,
                        onValueChange = {
                            draftUsername = it.lowercase(Locale.ROOT).filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '_' }
                        },
                        leadingIcon = { Text("@", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppleBlue) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(0.05f),
                            unfocusedContainerColor = Color.White.copy(0.05f),
                            focusedBorderColor = AppleBlue,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Usernames must be letters, numbers, and underscores only.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Text(errorMessage, color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 3. Save Button
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSaving = true
                        errorMessage = ""
                        coroutineScope.launch {
                            val base64 = logoBitmap?.let { resizeAndConvertToBase64(it) }
                            val result = vm.updateUserIdentity(
                                newUsername = draftUsername,
                                emailToSave = vm.userEmail,
                                profileBase64 = base64
                            )
                            isSaving = false
                            if (result.first) onDismiss() else errorMessage = result.second ?: "Failed to update profile."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppleBlue,
                        disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    enabled = draftUsername.isNotEmpty() && !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                }
            }

            // Bottom Sheet Action Menu (Matches iOS showPhotoActionSheet)
            if (showPhotoActionSheet) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .zIndex(100f)
                        .clickable { showPhotoActionSheet = false },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 30.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1F1F29))
                            .clickable(enabled = false) {},
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Change Profile Picture",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        TextButton(
                            onClick = {
                                showPhotoActionSheet = false
                                cameraLauncher.launch()
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Take Photo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        TextButton(
                            onClick = {
                                showPhotoActionSheet = false
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Choose from Library", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                        if (logoBitmap != null || vm.profileImageUrl.isNotEmpty()) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            TextButton(
                                onClick = {
                                    showPhotoActionSheet = false
                                    logoBitmap = null
                                    vm.profileImageUrl = ""
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("Remove Photo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val cloudFrontUrl = remember(url) { normalizeCloudFrontUrl(url) }
    AsyncImage(
        model = cloudFrontUrl,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale
    )
}

fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            @Suppress("DEPRECATION")
            android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (_: Exception) { null }
}

fun resizeAndConvertToBase64(image: Bitmap): String {
    val maxDimension = 400f
    val ratio = minOf(maxDimension / image.width, maxDimension / image.height, 1f)
    val resized = Bitmap.createScaledBitmap(
        image,
        (image.width * ratio).toInt().coerceAtLeast(1),
        (image.height * ratio).toInt().coerceAtLeast(1),
        true
    )
    val outputStream = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
}

// --- Merged from DeepSettings.kt ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSettingsView(
    vm: AuthViewModel,
    authState: AuthState,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val modelSelector = remember { ModelSelector.shared(context) }
    val activeRelease by modelSelector.activeRelease.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showAlertSignOut by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var showReloadSuccess by remember { mutableStateOf(false) }

    var showGlobalNegativeCamera by remember { mutableStateOf(false) }
    var isUploadingGlobalNegative by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("APP LANGUAGE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

                Surface(
                    onClick = {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    color = Color(0xFF1C1C1E),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("App Language", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("System Settings", color = Color.Gray, fontSize = 15.sp)
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            val adminEmails = listOf(
                "angelgabriel2828@icloud.com",
                "angelgabriel0846@gmail.com",
                "nisargdpatel04@gmail.com",
                "matt@informationoutpost.com"
            )
            if (vm.userEmail.lowercase() in adminEmails) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ADMIN TOOLS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Surface(
                        onClick = { showGlobalNegativeCamera = true },
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).background(Color(0xFFFFA500), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Record Global Negatives", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Capture empty spaces for the AI dataset", color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // Reload Model Button
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        isReloading = true
                        coroutineScope.launch {
                            delay(1500)
                            ModelAutoRefreshService.shared(context).start()
                            isReloading = false
                            showReloadSuccess = true
                            delay(2500)
                            showReloadSuccess = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showReloadSuccess) Color.Green else AppleBlue
                    ),
                    enabled = !isReloading
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isReloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Text("Fetching Clusters...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else if (showReloadSuccess) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Text("Models Reloaded!", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Text("Reload Model", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                    Spacer(Modifier.width(6.dp))
                    val activeLabel by looksee.angelll.com.detection.Detector.shared(context).currentLabel.collectAsState()
                    Text(
                        if (activeLabel == null) "No Model Loaded" else "Active Model: $activeLabel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                }
            }

            if (isFullyLoggedIn) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showAlertSignOut = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.Red)
                        Text("Sign Out", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            Text(
                text = "Copyright © 1999-2026 Information Outpost, LLC.  All rights reserved.",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 40.dp)
            )
        }

        if (showGlobalNegativeCamera) {
            NegativeVideoCameraView(
                uiTargetDuration = 10,
                minTotalTimeLimit = 2,
                maxTotalTimeLimit = 10,
                onDone = { video ->
                    coroutineScope.launch {
                        isUploadingGlobalNegative = true
                        try {
                            BusinessLandmarkService().uploadGlobalNegativeVideo(video.file)
                        } catch (_: Exception) {
                        } finally {
                            isUploadingGlobalNegative = false
                            try { video.file.delete() } catch (_: Exception) {}
                            showGlobalNegativeCamera = false
                        }
                    }
                },
                onDismiss = { showGlobalNegativeCamera = false }
            )

            if (isUploadingGlobalNegative) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Uploading Global Negative...", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Please keep app open", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showAlertSignOut) {
        AlertDialog(
            onDismissRequest = { showAlertSignOut = false },
            title = { Text("Sign Out", color = Color.White) },
            text = { Text("Are you sure you want to sign out?", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    showAlertSignOut = false
                    coroutineScope.launch {
                        vm.signOut()
                        authState.signOut()
                    }
                }) {
                    Text("Sign Out", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlertSignOut = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionView(onBack: () -> Unit) {
    val context = LocalContext.current
    val modelSelector = remember { ModelSelector.shared(context) }
    val selectedTestModelID by modelSelector.selectedTestModelId.collectAsState()
    val availableModels = modelSelector.availableTestModels

    Scaffold(
        containerColor = Color(0xFF0F0F1A),
        topBar = {
            TopAppBar(
                title = { Text("Model Select", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F1A),
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Selection Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                ModelRow(
                    title = "Automatic",
                    detail = "Use the normal location-based model",
                    icon = Icons.Default.LocationOn,
                    isSelected = selectedTestModelID == null,
                    onClick = { modelSelector.useAutomaticModelSelection() }
                )
            }

            item {
                Text("Bundled Models", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            }

            if (availableModels.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                        Text("No bundled models found in this app assets.", color = Color(0xFFFFA500), fontSize = 14.sp)
                    }
                }
            } else {
                items(availableModels) { model ->
                    ModelRow(
                        title = model.displayName,
                        detail = "Cluster: ${model.clusterId}",
                        icon = Icons.Default.Category,
                        isSelected = selectedTestModelID == model.id,
                        onClick = { modelSelector.selectTestModel(model) }
                    )
                }
            }

            item {
                Text(
                    "The selected model is applied immediately and remembered between launches.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (availableModels.size != 3) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Text(
                            "Expected 3 bundled models; found ${availableModels.size}.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelRow(
    title: String,
    detail: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AppleBlue else Color.Gray,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }

            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = AppleBlue, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// --- Merged from BusinessProfileView.kt ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileScreen(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showEditSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Business Profile", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "YOUR PUBLIC MERCHANT CARD",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Text(
                    "This is how your business will appear to users at the bottom of your landmark popups.",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            val storeName = vm.storeName.ifEmpty { "Your Store Name" }
            val bio = vm.storeBio.ifEmpty { "Add a short bio about your business here so users know what you do." }
            val phone = vm.phoneNumber.ifEmpty { "No Phone Number" }

            MerchantCard(
                storeName = storeName,
                logoUrl = normalizeCloudFrontUrl(vm.storeLogoUrl),
                bio = bio,
                phone = phone,
                website = vm.storeWebsite,
                address = vm.storeAddress
            )

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showEditSheet = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Edit Profile Details", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showEditSheet) {
        BusinessProfileEditSheet(vm = vm, onDismiss = { showEditSheet = false })
    }
}

// --- Merged from BusinessProfileEditSheet.kt ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileEditSheet(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var draftName by remember { mutableStateOf(vm.storeName) }
    var draftPhone by remember { mutableStateOf(vm.phoneNumber) }
    var draftBio by remember { mutableStateOf(vm.storeBio) }
    var draftLogoUrl by remember { mutableStateOf(vm.storeLogoUrl) }
    var draftWebsite by remember { mutableStateOf(vm.storeWebsite) }
    var draftAddress by remember { mutableStateOf(vm.storeAddress) }

    var isSaving by remember { mutableStateOf(false) }
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            logoBitmap = loadBitmapFromUri(context, uri)
            draftLogoUrl = ""
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            containerColor = Color(0xFF0F0F1A),
            topBar = {
                TopAppBar(
                    title = { Text("Edit Profile", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                    navigationIcon = {
                        TextButton(onClick = onDismiss, enabled = !isSaving) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                isSaving = true
                                coroutineScope.launch {
                                    val base64 = logoBitmap?.let { resizeAndConvertToBase64(it) }
                                    val success = vm.updateBusinessProfile(
                                        storeNameInput = draftName,
                                        phoneNumberInput = draftPhone,
                                        storeWebsiteInput = draftWebsite,
                                        storeAddressInput = draftAddress,
                                        storeBioInput = draftBio,
                                        storeLogoUrlInput = draftLogoUrl,
                                        storeLogoBase64Input = base64
                                    )
                                    isSaving = false
                                    if (success) onDismiss()
                                }
                            },
                            enabled = !isSaving
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                            else Text("Save", fontWeight = FontWeight.Bold, color = AppleBlue)
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Store Logo Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("STORE LOGO", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(AppleBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (logoBitmap != null) {
                                Image(
                                    bitmap = logoBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (draftLogoUrl.isNotEmpty()) {
                                RemoteImage(url = draftLogoUrl, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Default.Storefront, contentDescription = null, tint = AppleBlue)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(
                                        if (draftLogoUrl.isEmpty() && logoBitmap == null) "Choose Photo" else "Change Logo",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (draftLogoUrl.isNotEmpty() || logoBitmap != null) {
                                TextButton(
                                    onClick = { draftLogoUrl = ""; logoBitmap = null },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                                ) {
                                    Text("Remove Logo", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Basic Info
                EditField(label = "STORE NAME", value = draftName, onValueChange = { draftName = it })
                EditField(label = "SHORT BIO", value = draftBio, onValueChange = { draftBio = it }, singleLine = false)

                // Contact Info (10-digit phone filter matching iOS Settings.swift)
                EditField(
                    label = "PHONE NUMBER (OPTIONAL)",
                    value = draftPhone,
                    onValueChange = { newValue ->
                        draftPhone = newValue.filter { it.isDigit() }.take(10)
                    },
                    keyboardType = KeyboardType.Phone
                )
                EditField(label = "WEBSITE (OPTIONAL)", value = draftWebsite, onValueChange = { draftWebsite = it }, keyboardType = KeyboardType.Uri)
                EditField(label = "BUSINESS ADDRESS (OPTIONAL)", value = draftAddress, onValueChange = { draftAddress = it })
            }
        }
    }
}

@Composable
fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
    }
}