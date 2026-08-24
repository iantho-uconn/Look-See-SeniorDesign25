package looksee.angelll.com.uifiles

import android.app.Activity
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex.zIndex
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Buttons(
    vm: AuthViewModel,
    @Suppress("UNUSED_PARAMETER") authState: AuthState,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Assuming VariableContainer/InfoView maps to this local state for UI purposes
    val isInfoViewVisible by remember { mutableStateOf(false) }

    var showPromotion by remember { mutableStateOf(false) }
    var showBusinessAlert by remember { mutableStateOf(false) }
    var showSignUpPrompt by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }

    var pendingUploadLandmarkId by remember { mutableStateOf<String?>(null) }
    var showRecordSheet by remember { mutableStateOf(false) }

    // Redo logic
    var redoLandmarkId by remember { mutableStateOf<String?>(null) }
    var redoLandmarkLabel by remember { mutableStateOf<String?>(null) }
    var redoLandmarkDesc by remember { mutableStateOf<String?>(null) }

    var chromeVisible by remember { mutableStateOf(true) }
    var chromeFadeJob by remember { mutableStateOf<Job?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }

    var hasPreloadedMap by remember { mutableStateOf(false) }

    // Generic One-Time Notification State
    var showGenericNotification by remember { mutableStateOf(false) }
    var hasShownNotificationThisSession by remember { mutableStateOf(false) }
    var showMyLandmarksFromAlert by remember { mutableStateOf(false) }

    val sharedPrefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)

    val isBusinessMode = vm.hasActiveSubscription

    val pagerState = rememberPagerState(pageCount = { 2 })
    val currentTab = pagerState.currentPage
    val isScanTab = currentTab == 0

    val topBarTitle = if (currentTab == 0) "LookSee" else "Map"

    val isNavVisibleState = remember { mutableStateOf(chromeVisible) }

    fun scheduleChromeFadeIfNeeded() {
        if (!isScanTab) return
        chromeFadeJob?.cancel()
        chromeFadeJob = coroutineScope.launch {
            delay(3.seconds)
            if (!isDetecting) {
                chromeVisible = false
            }
        }
    }

    fun revealChromeThenFade() {
        chromeFadeJob?.cancel()
        chromeVisible = true
        scheduleChromeFadeIfNeeded()
    }

    fun updateIdleTimer(tab: Int, active: Boolean) {
        val window = (context as? Activity)?.window ?: return
        val shouldDisableAutoLock = (tab == 0 && active)
        if (shouldDisableAutoLock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun checkForGlobalNotifications() {
        if (!vm.isSignedIn || hasShownNotificationThisSession) return

        coroutineScope.launch(Dispatchers.IO) {
            // Expected to be a ghost error until BusinessLandmarksViewModel is added
            val silentVM = BusinessLandmarksViewModel()
            silentVM.loadLandmarks()

            val dismissedString = sharedPrefs.getString("dismissedGlobalNotifs_v4", "") ?: ""
            val dismissedSet = dismissedString.split(",").filter { it.isNotEmpty() }.toMutableSet()

            val needsMediaLandmark = silentVM.landmarks.firstOrNull { it.status == "NEEDS_MORE_MEDIA" && !dismissedSet.contains(it.landmarkId) }

            if (needsMediaLandmark != null) {
                dismissedSet.add(needsMediaLandmark.landmarkId)
                @Suppress("ApplySharedPref")
                sharedPrefs.edit {
                    putString("dismissedGlobalNotifs_v4", dismissedSet.joinToString(","))
                }

                withContext(Dispatchers.Main) {
                    hasShownNotificationThisSession = true
                    showGenericNotification = true
                    chromeVisible = true

                    launch {
                        delay(3.seconds)
                        showGenericNotification = false
                    }
                }
            }
        }
    }

    // Reset Redo properties after the sheet fully closes to mimic Swift's DispatchQueue delay
    LaunchedEffect(showRecordSheet) {
        if (!showRecordSheet) {
            delay(300.milliseconds)
            redoLandmarkId = null
            redoLandmarkLabel = null
            redoLandmarkDesc = null
        }
    }

    LaunchedEffect(currentTab) {
        revealChromeThenFade()
        updateIdleTimer(currentTab, isActive)
    }

    LaunchedEffect(isDetecting) {
        if (isDetecting && isScanTab) {
            chromeFadeJob?.cancel()
            chromeVisible = false
        }
    }

    LaunchedEffect(isActive) {
        updateIdleTimer(currentTab, isActive)
    }

    LaunchedEffect(isInfoViewVisible) {
        chromeFadeJob?.cancel()
        if (isInfoViewVisible) {
            chromeVisible = false
        } else {
            revealChromeThenFade()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkForGlobalNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        scheduleChromeFadeIfNeeded()
        updateIdleTimer(currentTab, isActive)
        delay(500.milliseconds)
        hasPreloadedMap = true
        checkForGlobalNotifications()
    }

    // Main View
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // PAGER
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isInfoViewVisible, // Disable swipe if popup is showing
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
                LandmarkScan(
                    onTap = { revealChromeThenFade() },
                    isDetecting = isDetecting,
                    onIsDetectingChange = { isDetecting = it },
                    isNavVisible = chromeVisible
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(top = 45.dp, bottom = 90.dp)) {
                    LandmarkMapView()
                }
            }
        }

        // 🚀 THE GENERIC 3-SECOND NOTIFICATION PILL
        AnimatedVisibility(
            visible = showGenericNotification && currentTab == 0,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp).zIndex(100f)
        ) {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showGenericNotification = false
                    showMyLandmarksFromAlert = true
                },
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.padding(horizontal = 24.dp).shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Action Required", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Attention is needed for landmarks.", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f))
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                }
            }
        }

        // Top & Bottom Chrome
        if (!isInfoViewVisible) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300))
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.size(56.dp, 48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showTutorial = true
                            }
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = "Info", tint = Color.White, modifier = Modifier.size(24.dp))
                            Text("Info", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = topBarTitle,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    if (isBusinessMode) showPromotion = true else showBusinessAlert = true
                                })
                            }
                        )

                        Spacer(Modifier.weight(1f))

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.size(56.dp, 48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showSettings = true
                            }
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(24.dp))
                            Text("Menu", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                AnimatedVisibility(
                    visible = chromeVisible || !isScanTab,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(300)),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(300))
                ) {
                    // Bottom Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .background(Color(0x80000000), RoundedCornerShape(50))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Scan Tab
                        Box(modifier = Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = if (currentTab == 0) Color(0xFF387DFF) else Color.Gray, modifier = Modifier.size(22.dp))
                                Text("Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (currentTab == 0) Color(0xFF387DFF) else Color.Gray)
                                if (currentTab == 0) {
                                    Box(modifier = Modifier.padding(top = 2.dp).size(24.dp, 3.dp).background(Color(0xFF387DFF), CircleShape))
                                }
                            }
                        }

                        // Record Button
                        Box(modifier = Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (!isBusinessMode) showSignUpPrompt = true else showRecordSheet = true
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Box(contentAlignment = Alignment.TopEnd) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(22.dp).padding(end = if (!isBusinessMode) 8.dp else 0.dp))
                                    if (!isBusinessMode) {
                                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(10.dp))
                                    }
                                }
                                Text("Record", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            }
                        }

                        // Map Tab
                        Box(modifier = Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Place, contentDescription = null, tint = if (currentTab == 1) Color(0xFF387DFF) else Color.Gray, modifier = Modifier.size(22.dp))
                                Text("Map", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (currentTab == 1) Color(0xFF387DFF) else Color.Gray)
                                if (currentTab == 1) {
                                    Box(modifier = Modifier.padding(top = 2.dp).size(24.dp, 3.dp).background(Color(0xFF387DFF), CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Info Popup / Dim Overlay
        if (isInfoViewVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .zIndex(100f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        // isInfoViewVisible = false (Mapped via external viewmodel usually)
                    },
                contentAlignment = Alignment.Center
            ) {
                PopUp() // Ghost error expected here until the component is fully added
            }
        }

        // Sign Up Prompt
        if (showSignUpPrompt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .zIndex(200f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        showSignUpPrompt = false
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp).background(Color.White, RoundedCornerShape(32.dp)).border(0.5.dp, Color.LightGray, RoundedCornerShape(32.dp)).padding(30.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(70.dp).background(Color(0xFF387DFF).copy(alpha = 0.15f), CircleShape))
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign up to upload", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Create an account to start contributing landmarks and help improve recognition.", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showSignUpPrompt = false
                                showSignUp = true
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF))
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Create Account", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                        }

                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showSignUpPrompt = false
                                coroutineScope.launch { pagerState.animateScrollToPage(0) }
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray.copy(alpha = 0.2f))
                        ) { Text("Not now", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray) }
                    }
                }
            }
        }
    }

    // Modal / Sheets / Intents
    if (showSettings) {
        // Native full-screen transition workaround for settings
        Box(modifier = Modifier.fillMaxSize().zIndex(300f).background(Color.Black)) {
            Settings(
                vm = vm,
                onDismiss = { showSettings = false }
            )
        }
    }

    if (showMyLandmarksFromAlert) {
        Box(modifier = Modifier.fillMaxSize().zIndex(300f).background(Color.Black)) {
            BusinessLandmarksView(
                onNavigateToDetail = { _ -> },
                onNavigateToRecord = { _, _ -> }
            )

            // Native Compose floating close button since it handles its own internal NavController
            IconButton(
                onClick = { showMyLandmarksFromAlert = false },
                modifier = Modifier
                    .padding(top = 40.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }

    if (showRecordSheet) {
        Box(modifier = Modifier.fillMaxSize().zIndex(300f).background(Color.Black)) {
            val isNavVisibleForRecord = remember { mutableStateOf(true) }
            LandmarkRecord(
                vm = vm,
                isNavVisible = isNavVisibleForRecord,
                isActive = true,
                existingLandmarkId = redoLandmarkId,
                existingLabel = redoLandmarkLabel,
                existingDescription = redoLandmarkDesc,
                onAddMoreMedia = { landmarkId ->
                    pendingUploadLandmarkId = landmarkId
                    showRecordSheet = false
                },
                onDismiss = { showRecordSheet = false }
            )
        }
    }

    if (showSignUp) {
        Box(modifier = Modifier.fillMaxSize().zIndex(300f).background(Color.Black)) {
            Signup(
                vm = vm,
                onSignupSuccess = { showSignUp = false },
                onGoToLogin = { showSignUp = false }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    if (showTutorial) {
        ModalBottomSheet(
            onDismissRequest = { showTutorial = false },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 40.dp, top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (currentTab == 0) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 0.95f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse),
                        label = "scale"
                    )

                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(70.dp).scale(scale))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How to Scan", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Point your camera at a landmark. Keep the object well-lit and steady. LookSee will identify it automatically.", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                } else {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(60.dp).shadow(10.dp, spotColor = Color(0xFF387DFF).copy(alpha = 0.5f)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Explore the Map", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("Find valid landmarks around you to scan. Use the search bar or filters to narrow down locations.", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}