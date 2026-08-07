package looksee.angelll.com.uifiles

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.models.ArchivedMedia
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.viewmodels.VariableContainer

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Buttons(
    vm: AuthViewModel,
    authState: AuthState
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val infoView = VariableContainer.shared // Ensure this acts like a singleton flow/state

    // UI States
    var showPromotion by remember { mutableStateOf(false) }
    var showBusinessAlert by remember { mutableStateOf(false) }
    var showSignUpPrompt by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }
    var pendingUploadLandmarkId by remember { mutableStateOf<String?>(null) }
    var draftToEdit by remember { mutableStateOf<ArchivedMedia?>(null) }

    var chromeVisible by remember { mutableStateOf(true) }
    var isDetecting by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showSideMenu by remember { mutableStateOf(false) }
    var isReticlePulsing by remember { mutableStateOf(false) }

    val isBusinessMode = vm.hasActiveSubscription
    val tabCount = if (isBusinessMode) 3 else 2
    val mapTabIndex = tabCount - 1
    val recordTabIndex = 1

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabCount })
    val currentTab = pagerState.currentPage
    val isScanTab = currentTab == 0

    val topBarTitle = when (currentTab) {
        0 -> "LookSee"
        mapTabIndex -> "Map"
        else -> "Record"
    }

    // Keep Screen On Logic (Idle Timer replacement)
    DisposableEffect(currentTab) {
        val window = (context as? Activity)?.window
        if (currentTab == 0) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Chrome Fading Logic
    fun revealChromeThenFade() {
        chromeVisible = true
        if (isScanTab) {
            coroutineScope.launch {
                delay(3000L)
                if (!isDetecting) {
                    chromeVisible = false
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        revealChromeThenFade()
    }

    LaunchedEffect(isDetecting) {
        if (isDetecting && isScanTab) {
            chromeVisible = false
        }
    }

    // Main Layout
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Horizontal Pager replaces the custom GeometryReader drag logic!
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !infoView.infoView && !showSideMenu
        ) { page ->
            when {
                page == 0 -> {
                    // LandmarkScan(onTap = { revealChromeThenFade() }, isDetecting = isDetecting, isNavVisible = chromeVisible)
                }
                isBusinessMode && page == recordTabIndex -> {
                    // LandmarkRecord(onSaved = { id ->
                    //     pendingUploadLandmarkId = id
                    //     coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    // })
                }
                page == mapTabIndex -> {
                    // LandmarkMapView()
                }
            }
        }

        // Overlay Chrome (Top Bar / Bottom Bar)
        if (!infoView.infoView) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavButton(icon = Icons.Default.Info, label = "Info") {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showTutorial = true
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = topBarTitle,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            if (isBusinessMode) showPromotion = true else showBusinessAlert = true
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    NavButton(icon = Icons.Default.Menu, label = "Menu") {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSideMenu = true
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible || !isScanTab,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                // Bottom Bar
                Row(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .background(Color.DarkGray.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TabButton("Scan", Icons.Default.Camera, 0, currentTab, false) {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }
                    if (isBusinessMode) {
                        TabButton("Record", Icons.Default.Videocam, recordTabIndex, currentTab, false) {
                            coroutineScope.launch { pagerState.animateScrollToPage(recordTabIndex) }
                        }
                    }
                    TabButton("Map", Icons.Default.Map, mapTabIndex, currentTab, false) {
                        coroutineScope.launch { pagerState.animateScrollToPage(mapTabIndex) }
                    }
                }
            }
        }

        // Side Menu Overlay
        if (showSideMenu) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showSideMenu = false }
            )
            AnimatedVisibility(
                visible = showSideMenu,
                enter = slideInHorizontally(initialOffsetX = { it }), // Slide in from right
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.75f).background(Color(0xFFF2F2F7))
                ) {
                    // Settings()
                }
            }
        }

        // PopUp Landmark Info
        if (infoView.infoView) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable {
                    infoView.dismissLandmark()
                },
                contentAlignment = Alignment.Center
            ) {
                // PopUp()
            }
        }

        // Sign Up Prompt
        if (showSignUpPrompt) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showSignUpPrompt = false }, contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(24.dp).background(Color.White, RoundedCornerShape(32.dp)).padding(30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.size(70.dp).background(Color(0xFF387DFF).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF387DFF), modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Sign up to upload", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Create an account to start contributing landmarks and help improve recognition.", fontSize = 15.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showSignUpPrompt = false
                            showSignUp = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text("Create Account", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSignUpPrompt = false
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }) {
                        Text("Not now", color = Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Signup Full Screen Cover
        if (showSignUp) {
            // Signup(onSignupSuccess = { showSignUp = false }, onGoToLogin = { showSignUp = false })
        }

        // Modals
        if (showTutorial) {
            ModalBottomSheet(onDismissRequest = { showTutorial = false }, containerColor = Color.White) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (currentTab) {
                        0 -> {
                            Icon(Icons.Default.FilterCenterFocus, contentDescription = null, modifier = Modifier.size(70.dp), tint = Color(0xFF387DFF))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("How to Scan", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Point your camera at a landmark. Keep the object well-lit and steady. LookSee will identify it automatically.", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        }
                        mapTabIndex -> {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(60.dp), tint = Color(0xFF387DFF))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("Explore the Map", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Find valid landmarks around you to scan. Use the search bar or filters to narrow down locations.", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        }
                        else -> {
                            Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(60.dp), tint = Color(0xFF387DFF))
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("Record Landmark", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Record a short video of a nearby landmark to help improve our recognition models.", fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        if (showBusinessAlert) {
            AlertDialog(
                onDismissRequest = { showBusinessAlert = false },
                title = { Text("Premium Account Required") },
                text = { Text("You need an active subscription to access the Promotion Editor.") },
                confirmButton = { TextButton(onClick = { showBusinessAlert = false }) { Text("OK") } }
            )
        }
    }
}

// 🚀 FIXED: Added RowScope so Modifier.weight(1f) works!
@Composable
fun RowScope.TabButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tab: Int, currentTab: Int, locked: Boolean, onClick: () -> Unit) {
    val isSelected = tab == currentTab
    val primaryColor = Color(0xFF387DFF)

    Column(
        modifier = Modifier
            .weight(1f)
            .background(if (isSelected && !locked) primaryColor.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = !locked) { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = if (locked) Color.DarkGray else if (isSelected) primaryColor else Color.Gray, modifier = Modifier.size(22.dp))
            if (locked) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(10.dp).align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (locked) Color.DarkGray else if (isSelected) primaryColor else Color.Gray)
    }
}

@Composable
fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable { onClick() }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}