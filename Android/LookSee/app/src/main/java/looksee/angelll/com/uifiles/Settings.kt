package looksee.angelll.com.uifiles

import looksee.angelll.com.subscription.SubscriptionAccountState
import looksee.angelll.com.subscription.SubscriptionTab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.BuildConfig
import looksee.angelll.com.R
import looksee.angelll.com.models.*
import looksee.angelll.com.models.BundledTestModel
import looksee.angelll.com.models.ModelSelector
import looksee.angelll.com.services.*
import looksee.angelll.com.subscription.SubscriptionPlans
import looksee.angelll.com.ui.theme.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel


class SettingsPresenter {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showUserProfileEditor by mutableStateOf(false)
    var signupStartsAsBusiness by mutableStateOf(false)
    
    var showGlobalNegativeCamera by mutableStateOf(false)
    var isReloadingModels by mutableStateOf(false)
    var showReloadSuccess by mutableStateOf(false)
    var isUploadingGlobalNegative by mutableStateOf(false)
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
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val prefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)

    var showCancelAlert by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    var showSignOutAlert by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    val dynamicPlanTitle = remember(vm.hasActiveSubscription, vm.userEmail) {
        if (!vm.hasActiveSubscription) "Free Account"
        else if (prefs.getBoolean("isFreeTrial_${vm.userEmail}", false)) "14-Day Free Trial"
        else "Verified Subscriber"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }) {
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
                        Text("Browsing anonymously", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
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
                    
                    val consentManager = remember { looksee.angelll.com.services.AdConsentManager(context) }
                    if (consentManager.isPrivacyOptionsRequired) {
                        LookSeeRow(icon = Icons.Default.Tune, iconContainerColor = Color(0xFF3F51B5), title = "Privacy Options") {
                            val activity = context as? android.app.Activity
                            if (activity != null) {
                                consentManager.showPrivacyOptions(activity)
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
            }

            androidx.compose.material3.Text(
                text = "Copyright © 1999-2026 Information Outpost, LLC.  All rights reserved.",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 40.dp)
            )
        }

        if (showSignOutAlert) {
            AlertDialog(
                onDismissRequest = { showSignOutAlert = false },
                title = { Text("Sign Out") },
                text = { Text("Are you sure you want to sign out?") },
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
                            looksee.angelll.com.models.BusinessLandmarkService().uploadGlobalNegativeVideo(video.file)
                        } catch (e: Exception) {
                            // Ignored for now
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
                        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text("Uploading Global Negative...", color = Color.White, fontWeight = FontWeight.Bold)
                        }
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
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(8.dp, CircleShape, ambientColor = AppleBlue, spotColor = AppleBlue)
                        .clip(CircleShape)
                        .background(AppleBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White)
                }
                Column {
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
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.7f)
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
                            onNavigate("signup")
                        }
                    },
                    modifier = Modifier.weight(1.2f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isFullyLoggedIn) "View Business Plans" else "Create Free Account", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                        Text("Log In", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                    Text(
                        "Usernames must be letters, numbers, and underscores only.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp)
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

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    Scaffold(
        containerColor = Color(0xFF000000),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000000),
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
            // App Language (Placeholder/System Link)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "APP LANGUAGE",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                
                Surface(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", context.packageName, null)
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

            // Testing Section
            if (BuildConfig.DEBUG) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "TESTING",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Surface(
                        onClick = { onNavigate("ModelSelectionView") },
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).background(Color(0xFF4B0082), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Model Select", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(modelSelector.activeDisplayName, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                            }

                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // Admin Tools
            val adminEmails = listOf("angelgabriel2828@icloud.com", "angelgabriel0846@gmail.com", "nisargdpatel04@gmail.com", "matt@informationoutpost.com")
            if (vm.userEmail.lowercase() in adminEmails) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ADMIN TOOLS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Surface(
                        onClick = { onNavigate("global_negatives") },
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
                            looksee.angelll.com.models.ModelAutoRefreshService.shared(context).start()
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

            // Sign Out Button
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
fun ModelSelectionView(
    onBack: () -> Unit
) {
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
        }
    }
}

@Composable
fun ModelRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                    "This is exactly how your business will appear to users at the bottom of your AR Landmarks.",
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
                logoUrl = vm.storeLogoUrl,
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
            logoBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            draftLogoUrl = ""
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            containerColor = Color(0xFF0F0F1A),
            topBar = {
                TopAppBar(
                    title = { Text("Edit Profile", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
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
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(AppleBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (logoBitmap != null) {
                                Image(bitmap = logoBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                                    Text(if(draftLogoUrl.isEmpty() && logoBitmap == null) "Choose Photo" else "Change Logo", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (draftLogoUrl.isNotEmpty() || logoBitmap != null) {
                                TextButton(onClick = { draftLogoUrl = ""; logoBitmap = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                                    Text("Remove Logo", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Basic Info
                EditField(label = "STORE NAME", value = draftName, onValueChange = { draftName = it })
                EditField(label = "SHORT BIO", value = draftBio, onValueChange = { draftBio = it }, singleLine = false)

                // Contact Info
                EditField(label = "PHONE NUMBER", value = draftPhone, onValueChange = { draftPhone = it }, keyboardType = KeyboardType.Phone)
                EditField(label = "WEBSITE", value = draftWebsite, onValueChange = { draftWebsite = it }, keyboardType = KeyboardType.Uri)
                EditField(label = "ADDRESS", value = draftAddress, onValueChange = { draftAddress = it })
            }
        }
    }
}

@Composable
fun EditField(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
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
