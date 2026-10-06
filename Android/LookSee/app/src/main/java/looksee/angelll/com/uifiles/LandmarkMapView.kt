package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import looksee.angelll.com.R
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import looksee.angelll.com.detection.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.AppleOrange

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
    val infoView = remember { VariableContainer.shared }
    val coroutineScope = rememberCoroutineScope()

    val rawLandmarks by nearbyService.items.collectAsState()
    val locationState by locationManager.state.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // 🚀 THE FIX: Defaulted isGlobalSearch to false so we don't query an 80,000km radius and crash the backend query
    var isGlobalSearch by remember { mutableStateOf(false) }
    var searchRadiusMiles by remember { mutableStateOf(10.0f) }
    var myUploadsOnly by remember { mutableStateOf(false) }
    var promotedOnly by remember { mutableStateOf(false) }
    val selectedClusters = remember { mutableStateListOf<String>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.7749, -122.4194), 13f)
    }

    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Filter Logic
    val activeLandmarks = remember(rawLandmarks, searchText, myUploadsOnly, promotedOnly, selectedClusters.toList()) {
        rawLandmarks.filter { landmark ->
            val matchesUser = if (myUploadsOnly) landmark.createdBy == vm.userEmail else true
            val matchesPromo = if (promotedOnly) landmark.promotionEnabled else true
            val matchesCluster = if (selectedClusters.isEmpty()) true else landmark.clusterId in selectedClusters
            val matchesSearch = if (searchText.isEmpty()) true else landmark.label.contains(searchText, ignoreCase = true)

            matchesUser && matchesPromo && matchesCluster && matchesSearch
        }
    }

    val availableClusters = remember(rawLandmarks) {
        rawLandmarks.mapNotNull { it.clusterId }.distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    }

    val mapStyleOptions = remember {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)
    }

    var hasSetInitialCameraPosition by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.fetchUserEmail()
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
        }
        // 🚀 THE FIX: Even if GPS is hanging on the emulator, immediately fetch landmarks for the default camera view!
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

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding()),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationState !is LookSeeLocationState.PermissionRequired,
                mapStyleOptions = mapStyleOptions
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = true,
                compassEnabled = true,
                zoomControlsEnabled = false,
                scrollGesturesEnabled = true,
                tiltGesturesEnabled = true,
                zoomGesturesEnabled = true,
                rotationGesturesEnabled = true
            )
        ) {
            activeLandmarks.forEach { landmark ->
                // 🚀 THE FIX: properly initialized rememberMarkerState
                val state = rememberMarkerState(
                    key = landmark.landmarkId,
                    position = LatLng(landmark.latitude, landmark.longitude)
                )

                // 🚀 THE FIX: Added "keys = arrayOf(landmark.landmarkId)" - Compose will swallow markers without this!
                MarkerComposable(
                    keys = arrayOf(landmark.landmarkId),
                    state = state,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    .statusBarsPadding()
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
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.fillMaxWidth(),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleBlue),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = {
                                        activeLandmarks.firstOrNull()?.let { firstMatch ->
                                            coroutineScope.launch {
                                                cameraPositionState.animate(
                                                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
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
                                    .clickable { searchText = "" }
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
                            showFilterSheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BadgedBox(badge = {
                        if (myUploadsOnly || promotedOnly || selectedClusters.isNotEmpty()) {
                            Badge(containerColor = AppleOrange, modifier = Modifier.size(14.dp).offset(x = (-2).dp, y = 2.dp))
                        }
                    }) {
                        Icon(Icons.Default.Tune, contentDescription = "Filter", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

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
    selectedClusters: MutableList<String>,
    onApply: () -> Unit
) {
    val iOSLightBackground = Color(0xFFF2F2F7)
    val iOSCardBackground = Color.White
    val iOSTextPrimary = Color.Black
    val iOSTextSecondary = Color(0xFF6E6E73)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(iOSLightBackground)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
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
                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue.copy(alpha = 0.12f), contentColor = AppleBlue),
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
            FilterSection(title = "SEARCH RADIUS", cardColor = iOSCardBackground, titleColor = iOSTextSecondary) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Text("Global Search (Everywhere)", color = iOSTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = isGlobalSearch, onCheckedChange = onGlobalSearchChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleBlue))
                }
                if (!isGlobalSearch) {
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Distance:", color = iOSTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${searchRadiusMiles.toInt()}", color = iOSTextPrimary, fontSize = 16.sp)
                            Text(" mi", color = iOSTextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = searchRadiusMiles,
                            onValueChange = onSearchRadiusChange,
                            valueRange = 1f..100f,
                            colors = SliderDefaults.colors(thumbColor = AppleBlue, activeTrackColor = AppleBlue, inactiveTrackColor = Color.LightGray)
                        )
                    }
                }
            }

            FilterSection(title = "VISIBILITY", cardColor = iOSCardBackground, titleColor = iOSTextSecondary) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("My Uploads Only", color = iOSTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = myUploadsOnly, onCheckedChange = onMyUploadsChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppleBlue))
                }
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Promoted Only", color = iOSTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = promotedOnly, onCheckedChange = onPromotedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.LightGray, uncheckedTrackColor = Color.LightGray))
                }
            }

            if (availableClusters.isNotEmpty()) {
                FilterSection(title = "FILTER BY CLUSTER", cardColor = iOSCardBackground, titleColor = iOSTextSecondary) {
                    availableClusters.forEachIndexed { index, clusterId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (clusterId in selectedClusters) selectedClusters.remove(clusterId)
                                    else selectedClusters.add(clusterId)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cluster $clusterId", color = AppleBlue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (clusterId in selectedClusters) {
                                Icon(Icons.Default.RadioButtonChecked, contentDescription = null, tint = AppleBlue)
                            } else {
                                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = Color.LightGray)
                            }
                        }
                        if (index < availableClusters.size - 1) {
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun FilterSection(title: String, cardColor: Color = Color.White.copy(alpha = 0.05f), titleColor: Color = Color.Gray, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = titleColor, modifier = Modifier.padding(horizontal = 16.dp))
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