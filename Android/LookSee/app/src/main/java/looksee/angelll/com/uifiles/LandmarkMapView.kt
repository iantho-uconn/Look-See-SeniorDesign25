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
import looksee.angelll.com.detection.*
import looksee.angelll.com.ui.theme.AppleBlue
import looksee.angelll.com.ui.theme.AppleOrange

data class LandmarkClusterItem(
    val landmark: NearbyLandmark
) : ClusterItem {
    override val position: LatLng = LatLng(landmark.latitude, landmark.longitude)
    override val title: String = landmark.label
    override val snippet: String = landmark.shortDescription
    override val zIndex: Float? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkMapScreen(
    vm: AuthViewModel,
    nearbyService: NearbyLandmarkService,
    locationManager: LocationManager
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val infoView = remember { VariableContainer.shared }

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

    val clusterItems = remember(activeLandmarks) {
        activeLandmarks.map { LandmarkClusterItem(it) }
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

    // Initial focus on user location
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

    // "Search as I move" logic
    LaunchedEffect(cameraPositionState.isMoving) {
        if (!cameraPositionState.isMoving) {
            val target = cameraPositionState.position.target
            val meters = (if (isGlobalSearch) 50000.0 else searchRadiusMiles.toDouble()) * 1609.34
            nearbyService.fetchNearby(target.latitude, target.longitude, meters)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = locationManager.hasLocationPermission(),
                mapStyleOptions = mapStyleOptions
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                compassEnabled = false,
                zoomControlsEnabled = false
            )
        ) {
            Clustering(
                items = clusterItems,
                onClusterItemClick = { item ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    infoView.presentNearbyLandmark(item.landmark)
                    true
                },
                clusterItemContent = { item ->
                    LandmarkMarker(landmark = item.landmark, isSelected = false)
                }
            )
        }

        // Overlay UI: Search and Filters
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchText.isEmpty()) {
                            Text("Search landmarks...", color = Color.Gray, fontSize = 16.sp)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.fillMaxWidth(),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppleBlue)
                        )
                    }
                    if (searchText.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
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
                    .background(Color.Black.copy(alpha = 0.7f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showFilterSheet = true
                    },
                contentAlignment = Alignment.Center
            ) {
                BadgedBox(badge = {
                    if (myUploadsOnly || promotedOnly || selectedClusters.isNotEmpty()) {
                        Badge(containerColor = AppleOrange, modifier = Modifier.size(10.dp).offset(x = (-4).dp, y = 4.dp))
                    }
                }) {
                    Icon(Icons.Default.Tune, contentDescription = "Filter", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = Color(0xFF1C1C1E),
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
                onApply = { showFilterSheet = false }
            )
        }
    }
}

@Composable
fun LandmarkMarker(landmark: NearbyLandmark, isSelected: Boolean) {
    val primaryColor = AppleBlue
    val promoColor = AppleOrange
    val scale = if (isSelected) 1.3f else 1.0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size((45 * scale).dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (landmark.promotionEnabled) promoColor else Color.White)
                .padding(if (landmark.promotionEnabled) 0.dp else 2.dp)
                .clip(CircleShape)
                .background(if (landmark.promotionEnabled) promoColor else primaryColor)
        ) {
            Icon(
                imageVector = if (landmark.promotionEnabled) Icons.Default.Stars else Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (landmark.promotionEnabled) promoColor else primaryColor,
            modifier = Modifier
                .size(24.dp)
                .offset(y = (-8).dp)
        )
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
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Map Filters", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // Search Radius
        FilterSection(title = "Search Radius") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Global Search (Everywhere)", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = isGlobalSearch, onCheckedChange = onGlobalSearchChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleBlue))
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

        // Visibility
        FilterSection(title = "Visibility") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My Uploads Only", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = myUploadsOnly, onCheckedChange = onMyUploadsChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleBlue))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Promoted Only", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = promotedOnly, onCheckedChange = onPromotedChange, colors = SwitchDefaults.colors(checkedThumbColor = AppleOrange))
            }
        }

        // Clusters
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

        Button(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Apply Filters", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.height(20.dp))
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
