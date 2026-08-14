package looksee.angelll.com.uifiles

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, MapsComposeExperimentalApi::class)
@Composable
fun LandmarkMapScreen(
    vm: AuthViewModel,
    nearbyService: NearbyLandmarkService,
    locationManager: LocationManager
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var selectedLandmark by remember { mutableStateOf<NearbyLandmark?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }

    var searchText by remember { mutableStateOf("") }
    var isGlobalSearch by remember { mutableStateOf(true) }
    var searchRadiusMiles by remember { mutableFloatStateOf(10f) } // Fixed: Float optimization
    var myUploadsOnly by remember { mutableStateOf(false) }
    var promotedOnly by remember { mutableStateOf(false) }
    var selectedClusters by remember { mutableStateOf(setOf<String>()) }

    val primaryColor = Color(0xFF387DFF)
    val promoColor = Color(0xFFFFA500)
    val sheetBackgroundColor = Color(0xFF1C1C1E)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.7749, -122.4194), 10f)
    }

    val activeLandmarks = nearbyService.items.filter { landmark ->
        val matchesUser = if (myUploadsOnly) landmark.createdBy == vm.userEmail else true
        val matchesPromo = if (promotedOnly) landmark.promotionEnabled else true
        val matchesCluster = if (selectedClusters.isEmpty()) true else landmark.clusterId != null && selectedClusters.contains(landmark.clusterId)
        val matchesSearch = if (searchText.isEmpty()) true else landmark.label.contains(searchText, ignoreCase = true)

        matchesUser && matchesPromo && matchesCluster && matchesSearch
    }

    val availableClusters = nearbyService.items
        .mapNotNull { it.clusterId }
        .distinct()
        .sortedWith { a, b ->
            val numA = a.toIntOrNull()
            val numB = b.toIntOrNull()
            if (numA != null && numB != null) numA.compareTo(numB) else a.compareTo(b)
        }

    LaunchedEffect(Unit) {
        if (!locationManager.isAuthorized) {
            locationManager.requestPermissionIfNeeded()
        }
        vm.fetchUserEmail()

        val lat = locationManager.latitude
        val lon = locationManager.longitude
        if (lat != null && lon != null) {
            val meters = if (isGlobalSearch) 50000.0 else (searchRadiusMiles * 1609.34)
            nearbyService.fetchNearby(lat, lon, meters)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 50.dp),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = locationManager.isAuthorized),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false, compassEnabled = false)
        ) {
            activeLandmarks.forEach { landmark ->
                MarkerComposable(
                    state = MarkerState(position = LatLng(landmark.latitude, landmark.longitude)),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedLandmark = landmark
                        true
                    }
                ) {
                    val scale = if (selectedLandmark?.id == landmark.id) 1.3f else 1.0f
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(8.dp)
                            .size((40 * scale).dp)
                    ) {
                        if (landmark.promotionEnabled) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(promoColor, CircleShape)
                                    .shadow(6.dp, CircleShape)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = "Promo", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White, CircleShape)
                                    .shadow(4.dp, CircleShape)
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = "Pin", tint = primaryColor, modifier = Modifier.size(24.dp))
                            }
                        }
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Pin Tail",
                            tint = if (landmark.promotionEnabled) promoColor else primaryColor,
                            modifier = Modifier.offset(y = (-4).dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                    .shadow(15.dp, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = primaryColor)
                Spacer(modifier = Modifier.width(12.dp))
                TextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search landmarks...", color = Color.Gray) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            activeLandmarks.firstOrNull()?.let { firstMatch ->
                                coroutineScope.launch {
                                    val latLng = LatLng(firstMatch.latitude, firstMatch.longitude)
                                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
                                }
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        searchText = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(end = 20.dp)
                    .size(50.dp)
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .shadow(10.dp, CircleShape)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showFilterSheet = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Filters", tint = Color.Black)
                if (myUploadsOnly || promotedOnly || selectedClusters.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(14.dp)
                            .background(promoColor, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }
        }

        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showFilterSheet = false
                    coroutineScope.launch {
                        val lat = locationManager.latitude
                        val lon = locationManager.longitude
                        if (lat != null && lon != null) {
                            val meters = if (isGlobalSearch) 50000.0 else (searchRadiusMiles * 1609.34)
                            nearbyService.fetchNearby(lat, lon, meters)
                        }
                    }
                },
                containerColor = sheetBackgroundColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text("MAP FILTERS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2C2C2E), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Global Search (Everywhere)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(checked = isGlobalSearch, onCheckedChange = { isGlobalSearch = it }, colors = SwitchDefaults.colors(checkedThumbColor = primaryColor))
                        }

                        if (!isGlobalSearch) {
                            HorizontalDivider(color = Color.DarkGray) // Fixed Deprecation
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Distance:", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${searchRadiusMiles.toInt()} mi", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = searchRadiusMiles,
                                onValueChange = { searchRadiusMiles = it },
                                valueRange = 1f..100f,
                                colors = SliderDefaults.colors(thumbColor = primaryColor, activeTrackColor = primaryColor)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2C2C2E), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("My Uploads Only", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(checked = myUploadsOnly, onCheckedChange = { myUploadsOnly = it }, colors = SwitchDefaults.colors(checkedThumbColor = primaryColor))
                        }
                        HorizontalDivider(color = Color.DarkGray) // Fixed Deprecation
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Promoted Only", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(checked = promotedOnly, onCheckedChange = { promotedOnly = it }, colors = SwitchDefaults.colors(checkedThumbColor = promoColor))
                        }
                    }

                    if (availableClusters.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2C2C2E), RoundedCornerShape(24.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("FILTER BY CLUSTER", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (selectedClusters.isNotEmpty()) {
                                    Text(
                                        "Clear",
                                        color = promoColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedClusters = emptySet()
                                        }
                                    )
                                }
                            }
                            availableClusters.forEach { clusterId ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            selectedClusters = if (selectedClusters.contains(clusterId)) {
                                                selectedClusters - clusterId
                                            } else {
                                                selectedClusters + clusterId
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Cluster $clusterId", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (selectedClusters.contains(clusterId)) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = primaryColor)
                                    } else {
                                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Unselected", tint = Color.Gray)
                                    }
                                }
                                if (clusterId != availableClusters.last()) HorizontalDivider(color = Color.DarkGray) // Fixed Deprecation
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (selectedLandmark != null) {
            val landmark = selectedLandmark!!
            ModalBottomSheet(
                onDismissRequest = { selectedLandmark = null },
                containerColor = sheetBackgroundColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (landmark.promotionEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(promoColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Star, contentDescription = "Promo", tint = promoColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(landmark.promotion ?: "Special Promotion Available!", color = promoColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(landmark.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        // Fixed Locale bug for string formatting
                        val miles = String.format(Locale.US, "%.1f", landmark.distanceMeters / 1609.34)
                        Text("$miles miles away", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                    }

                    Text(landmark.shortDescription, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Gray)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Fixed Uri deprecation using .toUri()
                            val uri = "google.navigation:q=${landmark.latitude},${landmark.longitude}".toUri()
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            intent.setPackage("com.google.android.apps.maps")
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = "Directions", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Directions", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}