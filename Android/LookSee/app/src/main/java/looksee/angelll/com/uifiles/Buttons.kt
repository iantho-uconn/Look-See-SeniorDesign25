package looksee.angelll.com.uifiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.zIndex
import androidx.core.app.ComponentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import looksee.angelll.com.models.ModelAutoRefreshService
import looksee.angelll.com.detection.LocationManager
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.CardBackground
import looksee.angelll.com.ui.theme.DarkBackground
import looksee.angelll.com.ui.theme.LookSeeCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ButtonsScreen(
    vm: AuthViewModel,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // ViewModels & Managers
    val infoView = remember { VariableContainer.shared }
    val authState = remember { AuthState() }
    val libraryService = remember { LibraryService.shared(context) }
    val modelService = remember { ModelService.shared(context) }
    val locationManager = remember { LocationManager(context) }
    val nearbyService = remember { NearbyLandmarkService() }
    val prefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)

    // State
    var showPromotion by remember { mutableStateOf(false) }
    var showBusinessAlert by remember { mutableStateOf(false) }
    var showSignUpPrompt by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentTab = pagerState.currentPage

    var pendingUploadLandmarkId by remember { mutableStateOf<String?>(null) }
    var showRecordSheet by remember { mutableStateOf(false) }
    var isRecordSheetAnimating by remember { mutableStateOf(false) }

    // Redo logic
    var redoLandmarkId by remember { mutableStateOf<String?>(null) }
    var redoLandmarkLabel by remember { mutableStateOf<String?>(null) }
    var redoLandmarkDesc by remember { mutableStateOf<String?>(null) }
    var redoSecondsNeeded by remember { mutableStateOf<Double?>(null) }

    var chromeVisible by remember { mutableStateOf(true) }
    var chromeFadeJob by remember { mutableStateOf<Job?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var isReticlePulsing by remember { mutableStateOf(false) }
    var hasPreloadedMap by remember { mutableStateOf(false) }

    // Notifications
    var showGenericNotification by remember { mutableStateOf(false) }
    var hasShownNotificationThisSession by remember { mutableStateOf(false) }
    var showMyLandmarksFromAlert by remember { mutableStateOf(false) }

    val isBusinessMode = vm.hasActiveSubscription
    val isScanTab = currentTab == 0
    val topBarTitle = if (isScanTab) "LookSee" else "Map"

    // Restoration logic
    val isScanCameraActive by remember {
        derivedStateOf {
            currentTab == 0 && !showRecordSheet && !showSignUpPrompt && !showTutorial && !showMyLandmarksFromAlert
        }
    }

    val shouldShowBanner by remember {
        derivedStateOf {
            isScanCameraActive && !infoView.infoView && !showSignUpPrompt && currentTab == 0
        }
    }

    // Keep screen on during scan
    val view = LocalView.current
    DisposableEffect(isScanCameraActive) {
        if (isScanCameraActive) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Permissions & Location
    val locationPermissionState = rememberPermissionState(
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    LaunchedEffect(Unit) {
        if (!locationPermissionState.status.isGranted) {
            locationPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(locationPermissionState.status) {
        if (locationPermissionState.status.isGranted) {
            locationManager.start()
        }
    }

    val locationState by locationManager.state.collectAsState()
    LaunchedEffect(locationState) {
        if (locationState is looksee.angelll.com.detection.LookSeeLocationState.Ready) {
            val fix = (locationState as looksee.angelll.com.detection.LookSeeLocationState.Ready).fix
            ModelAutoRefreshService.shared(context).updateLocation(
                latitude = fix.latitude,
                longitude = fix.longitude
            )
            ModelAutoRefreshService.shared(context).start()
        }
    }

    fun scheduleChromeFadeIfNeeded() {
        chromeFadeJob?.cancel()
        if (!isScanTab) return
        chromeFadeJob = coroutineScope.launch {
            delay(3000)
            if (!isDetecting) {
                chromeVisible = false
            }
        }
    }

    fun revealChromeThenFade() {
        chromeVisible = true
        scheduleChromeFadeIfNeeded()
    }

    fun checkForGlobalNotifications() {
        if (!vm.isSignedIn || hasShownNotificationThisSession) return

        coroutineScope.launch {
            val silentVM = BusinessLandmarksViewModel()
            silentVM.loadLandmarks()

            val dismissedString = prefs.getString("dismissedGlobalNotifs_v4", "") ?: ""
            val dismissedSet = dismissedString.split(",").filter { it.isNotEmpty() }.toMutableSet()

            val needsMediaLandmark = silentVM.landmarks.value.firstOrNull { it.status == "NEEDS_MORE_MEDIA" && !dismissedSet.contains(it.landmarkId) }

            if (needsMediaLandmark != null) {
                dismissedSet.add(needsMediaLandmark.landmarkId)
                prefs.edit().putString("dismissedGlobalNotifs_v4", dismissedSet.joinToString(",")).apply()

                hasShownNotificationThisSession = true
                showGenericNotification = true
                chromeVisible = true

                delay(3000)
                showGenericNotification = false
            }
        }
    }

    // Broadcast Receivers
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "CheckGlobalNotifications" -> {
                        if (currentTab == 0 && !showGenericNotification) {
                            checkForGlobalNotifications()
                        }
                    }
                    "TriggerRedoRecord" -> {
                        redoLandmarkId = intent.getStringExtra("id")
                        redoLandmarkLabel = intent.getStringExtra("label")
                        redoLandmarkDesc = intent.getStringExtra("description")
                        redoSecondsNeeded = intent.getDoubleExtra("secondsNeeded", 30.0).takeIf { it > 0 }

                        coroutineScope.launch {
                            delay(600)
                            showRecordSheet = true
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("CheckGlobalNotifications")
            addAction("TriggerRedoRecord")
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(isDetecting) {
        if (isDetecting && isScanTab) {
            chromeFadeJob?.cancel()
            chromeVisible = false
        } else if (!isDetecting && isScanTab) {
            revealChromeThenFade()
        }
    }

    LaunchedEffect(Unit) {
        scheduleChromeFadeIfNeeded()
        isReticlePulsing = true
        delay(500)
        hasPreloadedMap = true
        checkForGlobalNotifications()
    }

    LaunchedEffect(infoView.infoView) {
        if (infoView.infoView) {
            chromeVisible = false
        } else {
            revealChromeThenFade()
        }
    }

    LaunchedEffect(currentTab) {
        revealChromeThenFade()
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = chromeVisible || !isScanTab,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (isScanTab) it.background(Color.Transparent) else it.background(Color.Black) }
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavButton(icon = Icons.Outlined.Info, label = "Info") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showTutorial = true
                        }

                        Text(
                            text = topBarTitle,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isBusinessMode) showPromotion = true else showBusinessAlert = true
                                },
                            textAlign = TextAlign.Center
                        )

                        NavButton(icon = Icons.Default.Menu, label = "Menu") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNavigate("Settings")
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = chromeVisible || !isScanTab,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (shouldShowBanner) 12.dp else 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .width(320.dp)
                                .clip(CircleShape)
                                .background(DarkBackground.copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TabButton("Scan", Icons.Default.CenterFocusStrong, currentTab == 0, false) {
                                coroutineScope.launch { pagerState.animateScrollToPage(0, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!isBusinessMode) showSignUpPrompt = true else showRecordSheet = true
                                    }
                            ) {
                                Box(contentAlignment = Alignment.TopEnd) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = null,
                                        tint = if (isBusinessMode) Color.White else Color.Gray,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    if (!isBusinessMode) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = Color.Gray,
                                            modifier = Modifier.size(10.dp).offset(x = 12.dp, y = (-4).dp)
                                        )
                                    }
                                }
                                Text(
                                    "Record",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isBusinessMode) Color.White else Color.Gray
                                )
                            }

                            TabButton("Map", Icons.Default.Map, currentTab == 1, false) {
                                coroutineScope.launch { pagerState.animateScrollToPage(1, animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }
                        }
                    }
                }

                if (shouldShowBanner) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        AdBannerView()
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !infoView.infoView
            ) { page ->
                if (page == 0) {
                    val blurModifier = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && infoView.infoView) {
                        Modifier.graphicsLayer {
                            renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                20f, 20f, android.graphics.Shader.TileMode.CLAMP
                            ).asComposeRenderEffect()
                        }
                    } else Modifier

                    Box(modifier = blurModifier.fillMaxSize()) {
                        LandmarkScan(
                            vm = vm,
                            onTap = { revealChromeThenFade() },
                            isDetecting = isDetecting,
                            onIsDetectingChange = { isDetecting = it },
                            isNavVisible = chromeVisible,
                            isActive = isScanCameraActive
                        )
                    }
                } else {
                    Box(modifier = Modifier.padding(paddingValues)) {
                        LandmarkMapScreen(vm = vm, nearbyService = nearbyService, locationManager = locationManager)
                    }
                }
            }

            // Notification Pill
            AnimatedVisibility(
                visible = showGenericNotification && isScanTab,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp).zIndex(100f)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Red)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showGenericNotification = false
                            showMyLandmarksFromAlert = true
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = Color.White)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Action Required", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Attention is needed for landmarks.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                }
            }

            if (infoView.infoView) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(Unit) { detectTapGestures { infoView.dismissLandmark() } }
                        .zIndex(200f),
                    contentAlignment = Alignment.Center
                ) {
                    PopUp()
                }
            }

            // Modals
            if (showSignUpPrompt) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .pointerInput(Unit) { detectTapGestures { showSignUpPrompt = false; coroutineScope.launch { pagerState.animateScrollToPage(0) } } },
                    contentAlignment = Alignment.Center
                ) {
                    LookSeeCard(modifier = Modifier.padding(24.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp), // Additional padding for inside the card
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Box(modifier = Modifier.size(70.dp).background(AppleBlue.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ArrowCircleUp, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(32.dp))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sign up to upload", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Create an account to start contributing landmarks and help improve recognition.", fontSize = 15.sp, color = Color.Gray, textAlign = TextAlign.Center)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showSignUpPrompt = false; onNavigate("guest_signup") },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Create Account", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showSignUpPrompt = false
                                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Not now", color = Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }

            if (showTutorial) {
                ModalBottomSheet(
                    onDismissRequest = { showTutorial = false },
                    containerColor = Color.Transparent,
                    dragHandle = { BottomSheetDefaults.DragHandle() },
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C1C1E).copy(alpha = 0.95f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(horizontal = 32.dp)
                            .padding(top = 32.dp, bottom = 48.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            if (currentTab == 0) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(70.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("How to Scan", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Point your camera at a landmark. Keep the object well-lit and steady. LookSee will identify it automatically.", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                }
                            } else {
                                Icon(Icons.Default.Map, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(60.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Explore the Map", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Find valid landmarks around you to scan. Use the search bar or filters to narrow down locations.", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRecordSheet) {
        LandmarkRecordScreen(
            vm = vm,
            isActive = true,
            existingLandmarkId = redoLandmarkId,
            existingLabel = redoLandmarkLabel,
            existingDescription = redoLandmarkDesc,
            existingSecondsNeeded = redoSecondsNeeded,
            onAddMoreMedia = { id ->
                pendingUploadLandmarkId = id
                showRecordSheet = false
            },
            onDismiss = { showRecordSheet = false }
        )
    }

    if (showMyLandmarksFromAlert) {
        BusinessLandmarksView(vm = vm) { route, _ ->
            if (route == "Dismiss") showMyLandmarksFromAlert = false
        }
    }

}

@Composable
fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RowScope.TabButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, isLocked: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).clickable {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(icon, contentDescription = null, tint = if (isLocked) Color.DarkGray else if (isSelected) AppleBlue else Color.Gray, modifier = Modifier.size(22.dp))
            if (isLocked) Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(10.dp).offset(x = 8.dp, y = (-4).dp))
        }
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isLocked) Color.DarkGray else if (isSelected) AppleBlue else Color.Gray)
        if (isSelected && !isLocked) Box(modifier = Modifier.padding(top = 4.dp).size(width = 24.dp, height = 3.dp).background(AppleBlue, CircleShape))
    }
}
