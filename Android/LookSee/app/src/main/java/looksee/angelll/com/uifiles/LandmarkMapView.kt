package looksee.angelll.com.uifiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*
import looksee.angelll.com.detection.*

@Composable
fun LandmarkMapScreen(
    vm: AuthViewModel,
    nearbyService: NearbyLandmarkService,
    locationManager: LocationManager
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val activeLandmarks by nearbyService.items.collectAsState()
    val locationState by locationManager.state.collectAsState()

    var selectedLandmark by remember { mutableStateOf<NearbyLandmark?>(null) }
    var showPermissionAlert by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(37.7749, -122.4194), 15f)
    }

    LaunchedEffect(Unit) {
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
        } else {
            showPermissionAlert = true
        }
    }

    LaunchedEffect(locationState) {
        val state = locationState
        if (state is LookSeeLocationState.Ready) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(state.fix.latitude, state.fix.longitude), 15f
            )
            nearbyService.fetchNearby(state.fix.latitude, state.fix.longitude)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 50.dp),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = locationManager.hasLocationPermission()),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false, compassEnabled = false)
        ) {
            for (landmark in activeLandmarks) {
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
                                    .clip(CircleShape)
                                    .background(Color(0xFF007AFF))
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )
                        }
                    }
                }
            }
        }

        // Overlay UI
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnimatedVisibility(visible = selectedLandmark != null) {
                selectedLandmark?.let { landmark ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { /* Navigate */ },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (landmark.promotionEnabled) {
                                    Text(
                                        landmark.promotion ?: "Special Offer!",
                                        color = Color(0xFF007AFF),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(landmark.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(
                                    "${String.format("%.0f", landmark.distanceMeters)}m away",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    landmark.shortDescription,
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPermissionAlert) {
        AlertDialog(
            onDismissRequest = { showPermissionAlert = false },
            title = { Text("Location Required") },
            text = { Text("LookSee needs your location to show nearby landmarks and offers.") },
            confirmButton = {
                TextButton(onClick = { showPermissionAlert = false }) {
                    Text("OK")
                }
            }
        )
    }
}
