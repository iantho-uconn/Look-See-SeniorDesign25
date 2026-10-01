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
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.*
import com.google.maps.android.compose.clustering.Clustering
import kotlinx.coroutines.launch
import looksee.angelll.com.R
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import looksee.angelll.com.detection.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.AppleOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkMapScreen(
    vm: AuthViewModel,
    nearbyService: NearbyLandmarkService,
    locationManager: LocationManager,
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

    // Filter State
    var isGlobalSearch by remember { mutableStateOf(true) }
    var searchRadiusMiles by remember { mutableStateOf(10.0f) }
    var myUploadsOnly by remember { mutableStateOf(false) }
    var promotedOnly by remember { mutableStateOf(false) }
    val selectedClusters = remember { mutableStateListOf<String>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.7749, -122.4194), 13f)
    }

    // Filter Logic
    val activeLandmarks = remember(rawLandmarks, searchText, myUploadsOnly, promotedOnly, selectedClusters.size) {
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

    // Map Style
    val mapStyleOptions = remember {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)
    }

    LaunchedEffect(Unit) {
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
            vm.fetchUserEmail()
        }
    }

    LaunchedEffect(locationState) {
        if (locationState is LookSeeLocationState.Ready) {
            val fix = (locationState as LookSeeLocationState.Ready).fix
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(fix.latitude, fix.longitude), 13f
            )
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

    // 🚀 FIXED: Isolated the Box and map constraints to prevent touch ingestion bugs
    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding()), // iOS safe area match
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationState !is LookSeeLocationState.PermissionRequired,
                mapStyleOptions = mapStyleOptions
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = true,
                compassEnabled = true,
                zoomControlsEnabled = false,
                scrollGesturesEnabled = true, // 🚀 Explicitly forced to true
                tiltGesturesEnabled = true,
                zoomGesturesEnabled = true,
                rotationGesturesEnabled = true
            )
        ) {
            activeLandmarks.forEach { landmark ->
                val state = rememberUpdatedMarkerState(position = LatLng(landmark.latitude, landmark.longitude))
                MarkerComposable(
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

        // Overlay UI: Search and Filters
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 20.dp, end = 20.dp), // iOS topChromeReservedHeight spacing match
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xB32C2C2E), // Match iOS .regularMaterial better
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
            containerColor = Color(0xFF1C1C1E),
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

// 🚀 FIXED: Custom marker shapes that perfectly match the iOS map screenshot!
@Composable
fun LandmarkMarker(landmark: NearbyLandmark, isSelected: Boolean) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.3f else 1.0f, label = "marker_scale")

    // Provide a fixed-size container so Compose doesn't change layout size, which stops map jumping
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
                        .clip(CircleShape)
                        .background(AppleOrange)
                        .shadow(6.dp, CircleShape)
                ) {
                    Text("👑", fontSize = 18.sp) // matching "crown.fill"
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AppleOrange,
                    modifier = Modifier
                        .size(24.dp)
                        .offset(y = (-8).dp)
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .shadow(4.dp, CircleShape)
                ) {
                    // Match iOS "mappin.circle.fill" primary color style
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AppleBlue,
                    modifier = Modifier
                        .size(24.dp)
                        .offset(y = (-8).dp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Map Filters", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            TextButton(onClick = onApply) {
                Text("Apply", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppleBlue)
            }
        }
        
        Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            FilterSection(title = "Search Radius") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Global Search (Everywhere)", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = isGlobalSearch, onCheckedChange = onGlobalSearchChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleBlue, checkedTrackColor = AppleBlue.copy(alpha = 0.5f)))
                }
                if (!isGlobalSearch) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Distance: ${searchRadiusMiles.toInt()} mi", color = Color.White, modifier = Modifier.weight(1f))
                    }
                    Slider(
                        value = searchRadiusMiles,
                        onValueChange = onSearchRadiusChange,
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(thumbColor = AppleBlue, activeTrackColor = AppleBlue)
                    )
                }
            }

            FilterSection(title = "Visibility") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My Uploads Only", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = myUploadsOnly, onCheckedChange = onMyUploadsChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleBlue, checkedTrackColor = AppleBlue.copy(alpha = 0.5f)))
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Promoted Only", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = promotedOnly, onCheckedChange = onPromotedChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleOrange, checkedTrackColor = AppleOrange.copy(alpha = 0.5f)))
                }
            }

            if (availableClusters.isNotEmpty()) {
                FilterSection(title = "Filter by Cluster") {
                    availableClusters.forEach { clusterId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (clusterId in selectedClusters) selectedClusters.remove(clusterId)
                                    else selectedClusters.add(clusterId)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cluster $clusterId", color = Color.White, modifier = Modifier.weight(1f))
                            if (clusterId in selectedClusters) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppleBlue)
                            } else {
                                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}