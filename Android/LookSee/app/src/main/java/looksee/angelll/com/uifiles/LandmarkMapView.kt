package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import looksee.angelll.com.R
import looksee.angelll.com.models.*
import looksee.angelll.com.services.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.AppleOrange
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.detection.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkMapScreen(
    vm: AuthViewModel,
    nearbyService: NearbyLandmarkService,
    locationManager: LocationManager,
    onSwipeToScan: () -> Unit,
    paddingValues: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val infoView = remember { VariableContainer.shared }
    val coroutineScope = rememberCoroutineScope()

    val rawLandmarks by nearbyService.items.collectAsState()
    val locationState by locationManager.state.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    var isGlobalSearch by remember { mutableStateOf(false) }
    var searchRadiusMiles by remember { mutableFloatStateOf(10.0f) }
    var myUploadsOnly by remember { mutableStateOf(false) }
    var promotedOnly by remember { mutableStateOf(false) }
    val selectedClusters = remember { mutableStateListOf<String>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.7749, -122.4194), 13f)
    }

    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val untrainedStatuses = setOf(
        "NEEDS_MORE_MEDIA",
        "PREPARING_DATA",
        "PENDING_TRAINING",
        "TRAINING_MODEL",
        "OPTIMIZING_MODEL"
    )

    val activeLandmarks = remember(
        rawLandmarks,
        searchText,
        myUploadsOnly,
        promotedOnly,
        selectedClusters.toList(),
        infoView.inactiveLandmarkIds,
        infoView.hiddenMapLandmarkIds
    ) {
        infoView.allLandmarks = rawLandmarks
        val prefs = context.getSharedPreferences("LookSeePrefs", android.content.Context.MODE_PRIVATE)
        if (vm.userEmail.isNotEmpty()) {
            prefs.edit().putString("lastKnownEmail", vm.userEmail).apply()
        }
        val currentEmail = vm.userEmail.takeIf { it.isNotEmpty() } ?: prefs.getString("lastKnownEmail", "") ?: ""

        rawLandmarks.filter { landmark ->
            val cluster = landmark.clusterId?.trim() ?: ""
            if (cluster.isEmpty()) return@filter false

            val status = landmark.status?.uppercase()
            if (status != null && untrainedStatuses.contains(status)) return@filter false

            val isLocallyInactive = infoView.inactiveLandmarkIds.contains(landmark.landmarkId) ||
                    VariableContainer.isLandmarkInactive(landmark.landmarkId)
            val isActive = if (infoView.isLocallyModified(landmark.landmarkId)) {
                !isLocallyInactive
            } else {
                (landmark.isActive ?: true) && !isLocallyInactive
            }
            if (!isActive) return@filter false

            val matchesUser = if (myUploadsOnly) landmark.createdBy == currentEmail else true
            val matchesPromo = if (promotedOnly) landmark.promotionEnabled else true
            val matchesCluster = if (selectedClusters.isEmpty()) true else cluster in selectedClusters
            val matchesSearch = if (searchText.isEmpty()) true else landmark.label.contains(searchText, ignoreCase = true)

            matchesUser && matchesPromo && matchesCluster && matchesSearch
        }
    }

    LaunchedEffect(activeLandmarks) {
        if (infoView.infoView && !activeLandmarks.any { it.landmarkId == infoView.landmarkId }) {
            infoView.dismissLandmark()
        }
    }

    val availableClusters = remember(rawLandmarks) {
        rawLandmarks
            .mapNotNull { it.clusterId?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .distinct()
            .sortedWith { lhs, rhs ->
                val lNum = lhs.toIntOrNull()
                val rNum = rhs.toIntOrNull()
                if (lNum != null && rNum != null) lNum.compareTo(rNum) else lhs.compareTo(rhs)
            }
    }

    val mapStyleOptions = remember {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)
    }

    var hasSetInitialCameraPosition by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("LookSeePrefs", android.content.Context.MODE_PRIVATE)
        if (vm.userEmail.isNotEmpty()) {
            prefs.edit().putString("lastKnownEmail", vm.userEmail).apply()
        }
        vm.fetchUserEmail()
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
        }
        val fallbackTarget = cameraPositionState.position.target
        val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
        nearbyService.fetchNearby(fallbackTarget.latitude, fallbackTarget.longitude, meters)
    }

    LaunchedEffect(locationState) {
        if (locationState is LookSeeLocationState.Ready && !hasSetInitialCameraPosition) {
            val fix = (locationState as LookSeeLocationState.Ready).fix
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(fix.latitude, fix.longitude), 13f
            )
            hasSetInitialCameraPosition = true

            val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
            nearbyService.fetchNearby(fix.latitude, fix.longitude, meters)
        }
    }

    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            val target = cameraPositionState.position.target
            val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
            nearbyService.fetchNearby(target.latitude, target.longitude, meters)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationState !is LookSeeLocationState.PermissionRequired,
                mapStyleOptions = mapStyleOptions
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                compassEnabled = true,
                zoomControlsEnabled = false,
                scrollGesturesEnabled = true,
                tiltGesturesEnabled = true,
                zoomGesturesEnabled = true,
                rotationGesturesEnabled = true
            )
        ) {
            activeLandmarks.forEach { landmark ->
                val state = rememberMarkerState(
                    key = landmark.landmarkId,
                    position = LatLng(landmark.latitude, landmark.longitude)
                )

                MarkerComposable(
                    keys = arrayOf(landmark.landmarkId),
                    state = state,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        focusManager.clearFocus()
                        infoView.presentNearbyLandmark(landmark)
                        true
                    }
                ) {
                    LandmarkMarker(
                        landmark = landmark,
                        isSelected = infoView.infoView && infoView.landmarkId == landmark.landmarkId
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(top = 80.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Search Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xB32C2C2E),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchText.isEmpty()) {
                                Text("Search landmarks...", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                            BasicTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.fillMaxWidth(),
                                cursorBrush = SolidColor(AppleBlue),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        activeLandmarks.firstOrNull()?.let { firstMatch ->
                                            coroutineScope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        LatLng(firstMatch.latitude, firstMatch.longitude), 17f
                                                    ),
                                                    1000
                                                )
                                            }
                                        }
                                    }
                                )
                            )
                        }
                        if (searchText.isNotEmpty()) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Clear",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        searchText = ""
                                        focusManager.clearFocus()
                                    }
                            )
                        }
                    }
                }

                // Filter FAB
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0xB32C2C2E))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            focusManager.clearFocus()
                            showFilterSheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BadgedBox(badge = {
                        if (myUploadsOnly || promotedOnly || selectedClusters.isNotEmpty()) {
                            Badge(
                                containerColor = AppleOrange,
                                modifier = Modifier.size(14.dp).offset(x = (-2).dp, y = 2.dp)
                            )
                        }
                    }) {
                        Icon(Icons.Default.Tune, contentDescription = "Filter", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Left-edge swipe zone back to Scan tab
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(40.dp)
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        if (dragAmount.x > 20f) {
                            change.consume()
                            onSwipeToScan()
                        }
                    }
                }
        )

        if (infoView.infoView) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.35f)
                    .align(Alignment.BottomCenter)
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            if (dragAmount.y > 15f) {
                                change.consume()
                                infoView.infoView = false
                            }
                        }
                    }
            )
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showFilterSheet = false
                val target = cameraPositionState.position.target
                val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
                coroutineScope.launch {
                    nearbyService.fetchNearby(target.latitude, target.longitude, meters)
                }
            },
            sheetState = filterSheetState,
            containerColor = Color(0xFFF2F2F7),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            scrimColor = Color.Black.copy(alpha = 0.5f)
        ) {
            FilterMenuContent(
                isGlobalSearch = isGlobalSearch,
                onGlobalSearchChange = { isGlobalSearch = it },
                searchRadiusMiles = searchRadiusMiles,
                onSearchRadiusChange = { searchRadiusMiles = it },
                myUploadsOnly = myUploadsOnly,
                onMyUploadsChange = { myUploadsOnly = it },
                promotedOnly = promotedOnly,
                onPromotedChange = { promotedOnly = it },
                availableClusters = availableClusters,
                selectedClusters = selectedClusters,
                onToggleCluster = { clusterId ->
                    if (selectedClusters.contains(clusterId)) {
                        selectedClusters.remove(clusterId)
                    } else {
                        selectedClusters.add(clusterId)
                    }
                },
                onClearClusters = { selectedClusters.clear() },
                onApply = {
                    showFilterSheet = false
                    val target = cameraPositionState.position.target
                    val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
                    coroutineScope.launch {
                        nearbyService.fetchNearby(target.latitude, target.longitude, meters)
                    }
                }
            )
        }
    }
}

@Composable
fun LandmarkMarker(landmark: NearbyLandmark, isSelected: Boolean) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.3f else 1.0f, label = "marker_scale")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(60.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale)
        ) {
            if (landmark.promotionEnabled) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(4.dp, CircleShape)
                        .background(AppleOrange, CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    Text("👑", fontSize = 18.sp)
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AppleOrange,
                    modifier = Modifier
                        .size(24.dp)
                        .offset(y = (-10).dp)
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .shadow(4.dp, CircleShape)
                        .background(AppleBlue, CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AppleBlue,
                    modifier = Modifier
                        .size(24.dp)
                        .offset(y = (-10).dp)
                )
            }
        }
    }
}

@Composable
fun FilterMenuContent(
    isGlobalSearch: Boolean,
    onGlobalSearchChange: (Boolean) -> Unit,
    searchRadiusMiles: Float,
    onSearchRadiusChange: (Float) -> Unit,
    myUploadsOnly: Boolean,
    onMyUploadsChange: (Boolean) -> Unit,
    promotedOnly: Boolean,
    onPromotedChange: (Boolean) -> Unit,
    availableClusters: List<String>,
    selectedClusters: List<String>,
    onToggleCluster: (String) -> Unit,
    onClearClusters: () -> Unit,
    onApply: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val iOSLightBackground = Color(0xFFF2F2F7)
    val iOSCardBackground = Color.White
    val iOSTextPrimary = Color.Black
    val iOSTextSecondary = Color(0xFF6E6E73)

    var radiusText by remember(searchRadiusMiles) { mutableStateOf(searchRadiusMiles.roundToInt().toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(iOSLightBackground)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Map Filters",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = iOSTextPrimary,
                modifier = Modifier.align(Alignment.Center)
            )

            Button(
                onClick = onApply,
                modifier = Modifier.align(Alignment.CenterEnd).height(32.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleBlue.copy(alpha = 0.12f),
                    contentColor = AppleBlue
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Apply", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. SEARCH RADIUS
            FilterSection(title = "SEARCH RADIUS", cardColor = iOSCardBackground, titleColor = iOSTextSecondary) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Global Search (Everywhere)",
                        color = iOSTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isGlobalSearch,
                        onCheckedChange = onGlobalSearchChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleBlue)
                    )
                }
                if (!isGlobalSearch) {
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Distance:",
                                color = iOSTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = radiusText,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(3)
                                    radiusText = digits
                                    digits.toFloatOrNull()?.let { parsed ->
                                        onSearchRadiusChange(parsed.coerceIn(1f, 100f))
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(
                                    color = iOSTextPrimary,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.End,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.width(76.dp).height(48.dp),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("mi", color = iOSTextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = searchRadiusMiles,
                            onValueChange = { onSearchRadiusChange(it.roundToInt().toFloat()) },
                            valueRange = 1f..100f,
                            steps = 98,
                            colors = SliderDefaults.colors(
                                thumbColor = AppleBlue,
                                activeTrackColor = AppleBlue,
                                inactiveTrackColor = Color.LightGray
                            )
                        )
                    }
                }
            }

            // 2. VISIBILITY
            FilterSection(title = "VISIBILITY", cardColor = iOSCardBackground, titleColor = iOSTextSecondary) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "My Uploads Only",
                        color = iOSTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = myUploadsOnly,
                        onCheckedChange = onMyUploadsChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleBlue)
                    )
                }
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "Promotions Only",
                        color = iOSTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = promotedOnly,
                        onCheckedChange = onPromotedChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppleOrange,
                            uncheckedTrackColor = Color.LightGray
                        )
                    )
                }
            }

            // 3. FILTER BY CLUSTER (Ported from iOS LandmarkMapView.swift)
            if (availableClusters.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "FILTER BY CLUSTER",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = iOSTextSecondary
                        )
                        if (selectedClusters.isNotEmpty()) {
                            Text(
                                "Clear",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleOrange,
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onClearClusters()
                                }
                            )
                        }
                    }

                    Surface(
                        color = iOSCardBackground,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            availableClusters.forEachIndexed { index, clusterId ->
                                val isSelected = selectedClusters.contains(clusterId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onToggleCluster(clusterId)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Cluster $clusterId",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = iOSTextPrimary
                                    )
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) AppleBlue else Color.Gray
                                    )
                                }
                                if (index < availableClusters.size - 1) {
                                    HorizontalDivider(
                                        color = Color.LightGray.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun FilterSection(
    title: String,
    cardColor: Color = Color.White.copy(alpha = 0.05f),
    titleColor: Color = Color.Gray,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Surface(
            color = cardColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                content()
            }
        }
    }
}