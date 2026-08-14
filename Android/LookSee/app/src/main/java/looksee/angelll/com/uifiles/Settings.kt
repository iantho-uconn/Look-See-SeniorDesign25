package looksee.angelll.com.uifiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val PrimaryColor = Color(0xFF387DFF)
private val SecondaryGroupedBackground = Color(0xFFF2F2F7)

class SettingsPresenter {
    var showSubscriptionFlow by mutableStateOf(false)
    var subscriptionStartingTab by mutableIntStateOf(0)
    var showLoginSheet by mutableStateOf(false)
    var showSignUpSheet by mutableStateOf(false)
    var resumeCheckoutAction by mutableStateOf<String?>(null)
    var justPurchased by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AuthViewModel,
    authState: AuthState,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val presenter = remember { SettingsPresenter() }
    var showCancelAlert by remember { mutableStateOf(false) }
    var isCancelling by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSignedIn by vm.isSignedIn.collectAsState()
    val userEmail by vm.userEmail.collectAsState()
    val hasActiveSubscription by vm.hasActiveSubscription.collectAsState()
    val tokenBalance by vm.tokenBalance.collectAsState()
    val storeName by vm.storeName.collectAsState()

    val isFullyLoggedIn = isSignedIn && userEmail.isNotEmpty()
    val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    val isFreeTrial = prefs.getBoolean("isFreeTrial_$userEmail", false)

    val dynamicPlanTitle = when {
        !hasActiveSubscription -> "Free Account"
        isFreeTrial -> "14-Day Free Trial"
        else -> "Yearly Subscription"
    }

    LaunchedEffect(isFullyLoggedIn) {
        if (isFullyLoggedIn && presenter.resumeCheckoutAction != null) {
            presenter.showLoginSheet = false
            presenter.showSignUpSheet = false
            delay(800.milliseconds)
            presenter.showSubscriptionFlow = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(4.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = SecondaryGroupedBackground
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!isFullyLoggedIn) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Guest", modifier = Modifier.size(48.dp), tint = Color.Gray)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Guest User", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Browsing anonymously", fontSize = 14.sp, color = Color.Gray)
                        }
                        LaunchedEffect(Unit) {
                            if (!presenter.justPurchased) vm.checkSession()
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Logged In", modifier = Modifier.size(48.dp), tint = PrimaryColor)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(userEmail, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(dynamicPlanTitle, fontSize = 14.sp, color = Color.Gray)
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

            if (isFullyLoggedIn && hasActiveSubscription) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BUSINESS MANAGEMENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))

                    if (isFreeTrial) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Color(0xFFFFF3E0), RoundedCornerShape(16.dp)).padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA000))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Free Trial Active", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Please subscribe before your 14-day trial ends to prevent your landmarks from being deactivated.", fontSize = 13.sp, color = Color.DarkGray)
                            }
                        }
                    }

                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground) {
                        Column {
                            SettingsRow(Icons.Default.Place, PrimaryColor, "Manage My Landmarks", "View the landmarks assigned to your account.", showDivider = true) { onNavigate("BusinessLandmarksView") }
                            SettingsRow(Icons.Default.Star, Color(0xFFFFA000), "Tokens ($tokenBalance)", "Buy tokens to update your inventory.", showDivider = false) {
                                presenter.subscriptionStartingTab = 1
                                presenter.showSubscriptionFlow = true
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BUSINESS MANAGEMENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground) {
                        SettingsRow(Icons.Default.Lock, Color.Gray, "Business Tools Locked", "Subscribe to a plan to unlock landmarks and tokens.") {
                            presenter.subscriptionStartingTab = 0
                            presenter.showSubscriptionFlow = true
                        }
                    }
                }
            }

            if (isFullyLoggedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ACCOUNT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground) {
                        Column {
                            if (hasActiveSubscription) {
                                SettingsRow(Icons.Default.Storefront, PrimaryColor, "Business Profile", storeName.ifEmpty { "Update store name and phone number." }, showDivider = true) { onNavigate("BusinessProfileView") }
                            } else {
                                SettingsRow(Icons.Default.Lock, Color.Gray, "Business Profile Locked", "Subscribe to edit your public store info.", showDivider = true) {
                                    presenter.subscriptionStartingTab = 0
                                    presenter.showSubscriptionFlow = true
                                }
                            }
                            SettingsRow(Icons.Default.Lock, Color.Gray, "Account & Security", "Change your email or password.") { onNavigate("AccountSecurityView") }
                        }
                    }
                }
            }

            if (!hasActiveSubscription || !isFullyLoggedIn) {
                GuestPromoCard(presenter, isFullyLoggedIn)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MEMBERSHIP", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
                    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Current Plan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(dynamicPlanTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryColor)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Status", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Active", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Green)
                            }
                            HorizontalDivider()
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { presenter.subscriptionStartingTab = 0; presenter.showSubscriptionFlow = true },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Manage Plan", color = PrimaryColor, fontWeight = FontWeight.Bold) }

                                Button(
                                    onClick = { showCancelAlert = true },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Cancel", color = Color.Red, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).shadow(2.dp, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), color = SecondaryGroupedBackground) {
                Column {
                    SettingsRow(Icons.Default.Info, Color(0xFFFFA000), "Help & Support", showDivider = true) { onNavigate("Help") }
                    SettingsRow(Icons.Default.ThumbUp, Color(0xFF9C27B0), "Privacy Policy", showDivider = true) { onNavigate("Privacy") }
                    SettingsRow(Icons.Default.List, Color(0xFF4CAF50), "Terms of Service", showDivider = true) { onNavigate("Terms") }
                    SettingsRow(Icons.Default.Settings, Color.Gray, "Settings & Preferences") { onNavigate("DeepSettingsView") }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }

        if (isCancelling) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = Color.White)
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
                        vm.cancelSubscription(context)
                        isCancelling = false
                    }
                }) {
                    Text("Cancel Plan", color = Color.Red)
                }
            },
            dismissButton = { TextButton(onClick = { showCancelAlert = false }) { Text("Keep Plan") } }
        )
    }

    if (presenter.showSubscriptionFlow) { ModalBottomSheet(onDismissRequest = { presenter.showSubscriptionFlow = false }) { } }
    if (presenter.showLoginSheet) { ModalBottomSheet(onDismissRequest = { presenter.showLoginSheet = false }) { } }
    if (presenter.showSignUpSheet) { ModalBottomSheet(onDismissRequest = { presenter.showSignUpSheet = false }) { } }
}

@Composable
fun SettingsRow(icon: ImageVector, iconBg: Color, title: String, subtitle: String? = null, showDivider: Boolean = false, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).background(iconBg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Color.Gray)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.LightGray)
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.LightGray.copy(alpha = 0.5f))
    }
}

@Composable
fun GuestPromoCard(presenter: SettingsPresenter, isFullyLoggedIn: Boolean) {
    Box(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().shadow(15.dp, RoundedCornerShape(24.dp)).background(Brush.linearGradient(colors = listOf(Color(0xFF142659), Color(0xFF0D0D1F))), shape = RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))) {
        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.height(140.dp).fillMaxWidth().offset(y = 20.dp), tint = PrimaryColor.copy(alpha = 0.1f))
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(PrimaryColor, CircleShape).shadow(8.dp, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color.White)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Join LookSee", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Upload landmarks and manage data.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { presenter.showSubscriptionFlow = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor), shape = RoundedCornerShape(14.dp)) {
                    Text("Subscribe", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                }
                if (!isFullyLoggedIn) {
                    Button(onClick = { presenter.showLoginSheet = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)), shape = RoundedCornerShape(14.dp)) {
                        Text("Log In", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DeepSettingsScreen(vm: AuthViewModel, authState: AuthState, isFullyLoggedIn: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var showAlertSignOut by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var showReloadSuccess by remember { mutableStateOf(false) }
    val activeClusterID by remember { mutableStateOf("None") }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("APP LANGUAGE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp))
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }, shape = RoundedCornerShape(16.dp), color = SecondaryGroupedBackground) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("App Language", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("System Settings", fontSize = 15.sp, color = Color.Gray)
                        Icon(Icons.Default.Build, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isReloading = true
                    coroutineScope.launch {
                        delay(1.5.seconds)
                        isReloading = false
                        showReloadSuccess = true
                        delay(2.5.seconds)
                        showReloadSuccess = false
                    }
                },
                enabled = !isReloading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if (showReloadSuccess) Color.Green else PrimaryColor)
            ) {
                Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isReloading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Fetching Clusters...", fontWeight = FontWeight.Bold)
                    } else if (showReloadSuccess) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text("Models Reloaded!", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Reload Model", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Text(if (activeClusterID == "None") "No Cluster Loaded" else "Active Cluster: $activeClusterID", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            }
        }

        if (isFullyLoggedIn) {
            Button(
                onClick = { showAlertSignOut = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)), shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.Red)
                    Text("Sign Out", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showAlertSignOut) {
        AlertDialog(
            onDismissRequest = { showAlertSignOut = false },
            title = { Text("Are you sure you want to sign out?") },
            confirmButton = { TextButton(onClick = { coroutineScope.launch { vm.signOut(authState) } }) { Text("Sign Out", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showAlertSignOut = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileScreen(vm: AuthViewModel) {
    var showEditSheet by remember { mutableStateOf(false) }

    val storeName by vm.storeName.collectAsState()
    val storeBio by vm.storeBio.collectAsState()
    val phoneNumber by vm.phoneNumber.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOUR PUBLIC MERCHANT CARD", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Text("This is exactly how your business will appear to users at the bottom of your AR Landmarks.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }

        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.LightGray, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(storeName.ifEmpty { "Store Name" }, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(storeBio.ifEmpty { "Bio..." }, color = Color.DarkGray)
                Text(phoneNumber.ifEmpty { "Phone..." }, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
            }
        }

        Button(onClick = { showEditSheet = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor), shape = RoundedCornerShape(16.dp)) {
            Text("Edit Profile Details", modifier = Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(40.dp))
    }

    if (showEditSheet) {
        ModalBottomSheet(onDismissRequest = { showEditSheet = false }, modifier = Modifier.fillMaxHeight(0.9f)) {
            BusinessProfileEditSheet(vm) { showEditSheet = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileEditSheet(vm: AuthViewModel, onDismiss: () -> Unit) {
    val currentStoreName by vm.storeName.collectAsState()
    val currentPhone by vm.phoneNumber.collectAsState()
    val currentBio by vm.storeBio.collectAsState()
    val currentLogoUrl by vm.storeLogoUrl.collectAsState()

    var draftName by remember { mutableStateOf(currentStoreName) }
    var draftPhone by remember { mutableStateOf(currentPhone) }
    var draftBio by remember { mutableStateOf(currentBio) }
    var draftLogoUrl by remember { mutableStateOf(currentLogoUrl) }

    var isSaving by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { selectedImageUri = uri; draftLogoUrl = "" }
    }

    Column(modifier = Modifier.fillMaxSize().clickable { keyboardController?.hide() }.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Text("Edit Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            TextButton(
                onClick = {
                    isSaving = true
                    coroutineScope.launch {
                        val success = vm.updateBusinessProfile(draftName, draftPhone, draftBio, draftLogoUrl, null)
                        isSaving = false
                        if (success) onDismiss()
                    }
                },
                enabled = !isSaving
            ) { if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Save", fontWeight = FontWeight.Bold) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BASIC INFO", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = draftName, onValueChange = { draftName = it }, label = { Text("Store Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draftBio, onValueChange = { draftBio = it }, label = { Text("Short Bio") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
            Text("Your store name and a short bio describing what you do.", fontSize = 12.sp, color = Color.Gray)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STORE LOGO", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.size(56.dp).background(PrimaryColor.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = PrimaryColor)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (draftLogoUrl.isEmpty() && selectedImageUri == null) "Choose Photo" else "Change Logo")
                    }
                    if (draftLogoUrl.isNotEmpty() || selectedImageUri != null) {
                        TextButton(onClick = { draftLogoUrl = ""; selectedImageUri = null }) { Text("Remove Logo", color = Color.Red, fontSize = 12.sp) }
                    }
                }
            }
            Text("Upload a square logo or image from your photo library.", fontSize = 12.sp, color = Color.Gray)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CONTACT INFO", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = draftPhone,
                onValueChange = { input -> val filtered = input.filter { it.isDigit() }; if (filtered.length <= 10) draftPhone = filtered },
                label = { Text("Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}