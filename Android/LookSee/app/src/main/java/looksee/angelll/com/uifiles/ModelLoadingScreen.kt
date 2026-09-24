package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.detection.*
import looksee.angelll.com.models.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ModelLoadingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val modelService = remember { ModelService.shared(context) }
    val locationManager = remember { LocationManager(context) }

    val modelState by modelService.state.collectAsState()
    val pullReason by modelService.pullReason.collectAsState()
    val locationState by locationManager.state.collectAsState()

    val opacity = remember { Animatable(0f) }
    var statusMessage by remember { mutableStateOf("Getting your location…") }
    var failed by remember { mutableStateOf(false) }
    var showLoadingUI by remember { mutableStateOf(false) }

    // Requests BOTH Fine and Coarse permissions to prevent Android 12+ dialog traps
    val locationPermissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(locationPermissionsState.allPermissionsGranted) {
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
        } else {
            locationPermissionsState.launchMultiplePermissionRequest()
        }
    }

    suspend fun startLoading() {
        failed = false
        statusMessage = "Getting your location…"

        // Wait on location fix without getting blocked by strict fine-only checks
        var attempts = 0
        while (locationState !is LookSeeLocationState.Ready) {
            delay(500)
            attempts++
            if (attempts > 40) { // 🚀 FIXED: Allow 20 seconds for cold Android network locations to settle
                failed = true
                statusMessage = "Could not get your location. Ensure location access is enabled."
                return
            }
        }

        val readyState = locationState as? LookSeeLocationState.Ready
        val fix = readyState?.fix
        if (fix == null) {
            failed = true
            statusMessage = "Location unavailable. Please try again."
            return
        }

        statusMessage = "Finding models for your area…"
        modelService.loadModels(latitude = fix.latitude, longitude = fix.longitude)

        val finalState = modelService.state.value
        if (finalState is ModelState.Loaded) {
            val models = finalState.models
            when (val reason = pullReason) {
                is ModelPullReason.None -> {
                    failed = true
                    statusMessage = "No models available for your area."
                }
                is ModelPullReason.Single -> {
                    val model = models[0]
                    statusMessage = "Loaded ${model.name} · Cluster ${model.clusterId}\n${reason.reason}"
                    delay(500)
                    opacity.animateTo(0f, tween(300))
                    onComplete()
                }
                is ModelPullReason.Multiple -> {
                    val names = models.joinToString { it.name }
                    val clusterIds = models.map { it.clusterId }.distinct().sorted().joinToString()
                    statusMessage = "Loaded ${models.size} models: $names\nClusters: $clusterIds"
                    delay(500)
                    opacity.animateTo(0f, tween(300))
                    onComplete()
                }
            }
        } else if (finalState is ModelState.Failed) {
            failed = true
            statusMessage = finalState.message
        }
    }

    LaunchedEffect(locationState) {
        if (locationState is LookSeeLocationState.Ready && modelState is ModelState.NotLoaded) {
            startLoading()
        }
    }

    LaunchedEffect(Unit) {
        opacity.animateTo(1f, tween(300))
        startLoading()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).alpha(opacity.value)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        0.0f to Color(0xFF387DFF).copy(alpha = 0.12f),
                        1.0f to Color.Transparent
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            LoadingAnimationScreen(onFinished = {
                coroutineScope.launch {
                    showLoadingUI = true
                }
            })

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier.padding(bottom = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (failed) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFA500),
                        modifier = Modifier.size(28.dp)
                    )

                    Text(
                        text = statusMessage,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )

                    Button(
                        onClick = {
                            locationManager.start()
                            coroutineScope.launch { startLoading() }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                    ) {
                        Text("Retry", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    TextButton(onClick = { onComplete() }) {
                        Text(
                            "Continue without model",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        text = statusMessage,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        }
    }
}