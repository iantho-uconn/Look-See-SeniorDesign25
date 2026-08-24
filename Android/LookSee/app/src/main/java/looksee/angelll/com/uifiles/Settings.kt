package looksee.angelll.com.uifiles

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Primary color config
val PrimaryBlue = Color(0xFF387DFF)

class SettingsPresenter : ViewModel() {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showLoginSheet by mutableStateOf(false)
    var showSignUpSheet by mutableStateOf(false)
    var showUserProfileEditor by mutableStateOf(false)

    var resumeCheckoutAction by mutableStateOf<String?>(null)
    var savedAddOnIndex by mutableIntStateOf(0)
    var savedTokenCount by mutableIntStateOf(0)
    var savedTokenCents by mutableIntStateOf(0)

    var justPurchased by mutableStateOf(false)
}

@Composable
fun SettingsScreen(
    vm: AuthViewModel,
    authState: AuthState,
    onDismiss: () -> Unit,
    onNavigateToBusinessLandmarks: () -> Unit,
    onNavigateToBusinessProfile: () -> Unit,
    onNavigateToAccountSecurity: () -> Unit,
    onNavigateToHelpAndSupport: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsOfService: () -> Unit,
    onNavigateToDeepSettings: () -> Unit
) {
    val presenter: SettingsPresenter = viewModel()
    val scope = rememberCoroutineScope()
    var showCancelAlert by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    val view = LocalView.current

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()
    val isFreeTrial = false
    val dynamicPlanTitle = when {
        !vm.hasActiveSubscription -> "Free Account"
        isFreeTrial -> "14-Day Free Trial"
        else -> "Verified Subscriber"
    }

    LaunchedEffect(authState.didSignOut) {
        if (authState.didSignOut) onDismiss()
    }

    LaunchedEffect(isFullyLoggedIn, presenter.resumeCheckoutAction) {
        if (isFullyLoggedIn && presenter.resumeCheckoutAction != null) {
            presenter.showLoginSheet = false
            presenter.showSignUpSheet = false
            delay(800.milliseconds)
            presenter.showSubscriptionFlow = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 40.dp)
        ) {
            // 1. PROFILE HEADER
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .shadow(4.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    if (!isFullyLoggedIn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HelpCenter,
                                contentDescription = "Guest",
                                modifier = Modifier.size(48.dp),
                                tint = Color.Gray
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Guest User", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Browsing anonymously", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        LaunchedEffect(Unit) {
                            if (!presenter.justPurchased) {
                                vm.checkSession()
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                    presenter.showUserProfileEditor = true
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (vm.profileImageUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = vm.profileImageUrl,
                                        contentDescription = "Profile",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Profile",
                                        modifier = Modifier.size(32.dp),
                                        tint = PrimaryBlue
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (vm.username.isEmpty()) "Set Username" else "@${vm.username}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (vm.hasActiveSubscription) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.Default.Verified, contentDescription = "Verified", tint = PrimaryBlue, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(dynamicPlanTitle, fontSize = 14.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Edit", tint = Color.LightGray)
                        }
                        LaunchedEffect(Unit) {
                            if (!presenter.justPurchased) {
                                vm.fetchUserDetails()
                                vm.fetchUserUsageStats()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. BUSINESS MANAGEMENT
            Text(
                "BUSINESS MANAGEMENT",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isFullyLoggedIn && vm.hasActiveSubscription) {
                if (isFreeTrial) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFA500).copy(alpha = 0.1f))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFA500))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Free Trial Active", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Please subscribe before your 14-day trial ends to prevent your landmarks from being deactivated.", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        SettingsRow(icon = Icons.Default.Business, iconBg = PrimaryBlue, title = "Manage My Landmarks", subtitle = "View the landmarks assigned to your account.") {
                            onNavigateToBusinessLandmarks()
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                        SettingsRow(icon = Icons.Default.GeneratingTokens, iconBg = Color(0xFFFFA500), title = "Tokens (${vm.tokenBalance})", subtitle = "Buy tokens to update your inventory.", showDivider = false) {
                            presenter.subscriptionStartingTab = 1
                            presenter.showSubscriptionFlow = true
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    SettingsRow(icon = Icons.Default.Lock, iconBg = Color.Gray, title = "Business Tools Locked", subtitle = "Subscribe to a plan to unlock landmarks and tokens.", showDivider = false) {
                        presenter.subscriptionStartingTab = 0
                        presenter.showSubscriptionFlow = true
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. ACCOUNT
            if (isFullyLoggedIn) {
                Text(
                    "ACCOUNT",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        if (vm.hasActiveSubscription) {
                            SettingsRow(icon = Icons.Default.Storefront, iconBg = Color(0xFF007AFF), title = "Business Profile", subtitle = vm.storeName.ifEmpty { "Update store name and phone number." }) {
                                onNavigateToBusinessProfile()
                            }
                        } else {
                            SettingsRow(icon = Icons.Default.Lock, iconBg = Color.Gray, title = "Business Profile Locked", subtitle = "Subscribe to edit your public store info.") {
                                presenter.subscriptionStartingTab = 0
                                presenter.showSubscriptionFlow = true
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                        SettingsRow(icon = Icons.Default.VpnKey, iconBg = Color.Gray, title = "Account & Security", subtitle = "Change your email or password.", showDivider = false) {
                            onNavigateToAccountSecurity()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 4. SUBSCRIPTION / MEMBERSHIP
            if (!vm.hasActiveSubscription || !isFullyLoggedIn) {
                GuestPromoCard(
                    onSignUp = { presenter.showSubscriptionFlow = true },
                    onLogIn = { presenter.showLoginSheet = true },
                    isFullyLoggedIn = isFullyLoggedIn
                )
            } else {
                Text(
                    "MEMBERSHIP",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Plan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(dynamicPlanTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Status", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Active", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34C759))
                        }
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { presenter.subscriptionStartingTab = 0; presenter.showSubscriptionFlow = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue.copy(alpha = 0.1f), contentColor = PrimaryBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Manage Plan", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showCancelAlert = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // 5. OTHER SETTINGS
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    SettingsRow(icon = Icons.Default.Help, iconBg = Color(0xFFFFA500), title = "Help & Support", showDivider = true) { onNavigateToHelpAndSupport() }
                    SettingsRow(icon = Icons.Default.PrivacyTip, iconBg = Color(0xFFA259FF), title = "Privacy Policy", showDivider = true) { onNavigateToPrivacyPolicy() }
                    SettingsRow(icon = Icons.Default.Description, iconBg = Color(0xFF34C759), title = "Terms of Service", showDivider = true) { onNavigateToTermsOfService() }
                    SettingsRow(icon = Icons.Default.Settings, iconBg = Color.Gray, title = "Settings & Preferences", showDivider = false) { onNavigateToDeepSettings() }
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
                        scope.launch {
                            vm.cancelSubscription()
                            isCancelling = false
                        }
                    }) { Text("Cancel Plan", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { showCancelAlert = false }) { Text("Keep Plan") } }
            )
        }

        if (isCancelling) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(16.dp)).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Canceling Plan...", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sheet Triggers
        if (presenter.showUserProfileEditor) {
            Dialog(onDismissRequest = { presenter.showUserProfileEditor = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                UserProfileEditSheet(vm) { presenter.showUserProfileEditor = false }
            }
        }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, iconBg: Color, title: String, subtitle: String? = null, showDivider: Boolean = true, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
    }
}

@Composable
fun GuestPromoCard(onSignUp: () -> Unit, onLogIn: () -> Unit, isFullyLoggedIn: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF142659), Color(0xFF0D0D1F))))
            .border(1.dp, Brush.linearGradient(listOf(PrimaryBlue.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MilitaryTech, contentDescription = "Premium", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Join LookSee", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Upload landmarks and manage data. Free trial available.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSignUp,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Sign up", fontWeight = FontWeight.Bold) }

                if (!isFullyLoggedIn) {
                    Button(
                        onClick = onLogIn,
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Log In", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun DeepSettingsScreen(
    vm: AuthViewModel,
    authState: AuthState,
    onNavigateToModelSelect: () -> Unit,
    onSignOutSuccess: () -> Unit
) {
    var showAlertSignOut by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var showReloadSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("APP LANGUAGE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 36.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable {
                        view.performHapticFeedback(android.view.Haptic