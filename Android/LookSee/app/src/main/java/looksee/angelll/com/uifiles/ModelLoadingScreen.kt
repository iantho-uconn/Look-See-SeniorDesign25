package looksee.angelll.com.uifiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ModelLoadingScreen(
    onComplete: () -> Unit
) {
    // Unresolved references
    val modelService = remember { ModelService.shared }
    val locationManager = remember { LocationManager() }
    val coroutineScope = rememberCoroutineScope()

    var opacity by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Getting your location…") }
    var failed by remember { mutableStateOf(false) }

    var showLoadingUI by remember { mutableStateOf(false) }
    var animationFinished by remember { mutableStateOf(false) }

    fun startLoading() {
        coroutineScope.launch {
            // Step 1 — wait for location
            statusMessage = "Getting your location…"
            var attempts = 0
            while (!locationManager.isAuthorized || locationManager.latitude == null) {
                delay(500.milliseconds)
                attempts += 1
                if (attempts > 20) {
                    failed = true
                    statusMessage = "Could not get your location. Make sure location access is enabled."
                    return@launch
                }
            }

            val lat = locationManager.latitude
            val lon = locationManager.longitude
            if (lat == null || lon == null) {
                failed = true
                statusMessage = "Location unavailable. Please try again."
                return@launch
            }

            // Step 2 — load models
            statusMessage = "Finding models for your area…"
            modelService.loadModels(lat, lon)

            // Step 3 — check result
            when (val state = modelService.state) {
                is ModelState.Loaded -> {
                    when (val reason = modelService.pullReason) {
                        is PullReason.None -> {
                            failed = true
                            statusMessage = "No models available for your area."
                        }
                        is PullReason.Single -> {
                            val model = state.infos.firstOrNull()
                            if (model != null) {
                                statusMessage = "Loaded ${model.name} · Cluster ${model.clusterID}\n${reason.reason}"
                                delay(800.milliseconds)
                                opacity = 0f // withAnimation equivalent handled by animateFloatAsState in parent
                                delay(400.milliseconds)
                                onComplete()
                            }
                        }
                        is PullReason.Multiple -> {
                            val names = state.infos.joinToString(", ") { it.name }
                            val clusterIDs = state.infos.map { it.clusterID }.distinct().sorted().joinToString(", ")
                            statusMessage = "Loaded ${state.infos.size} models: $names\nClusters: $clusterIDs\n${reason.reasons.joinToString(" · ")}"
                            delay(800.milliseconds)
                            opacity = 0f
                            delay(400.milliseconds)
                            onComplete()
                        }
                    }
                }
                is ModelState.Failed -> {
                    failed = true
                    statusMessage = state.error
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(Unit) {
        // Opacity fade in equivalent
        opacity = 1f
        startLoading()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(opacity)
            .background(Color(0xFF0F0F1A)), // Dark background fallback
        contentAlignment = Alignment.Center
    ) {
        // Unresolved reference: AnimatedBackground
        AnimatedBackground(showLoadingUI = showLoadingUI)

        // Glow
        Box(
            modifier = Modifier
                .size(300.dp)
                .blur(60.dp)
                .background(Color(0xFF387DFF).copy(alpha = 0.12f), CircleShape)
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Logo / Animation
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LoadingAnimationScreen(
                    animationFinished = animationFinished,
                    onFinished = {
                        animationFinished = true
                        coroutineScope.launch {
                            delay(1000.milliseconds)
                            showLoadingUI = true
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showLoadingUI,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    if (failed) {
                        // Error state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = "Error", tint = Color.LightGray, modifier = Modifier.size(28.dp))
                            Text(
                                text = statusMessage,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp)
                            )
                            Button(
                                onClick = {
                                    failed = false
                                    startLoading()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Retry", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                            TextButton(onClick = onComplete) {
                                Text("Continue without model", fontSize = 13.sp, color = Color.White.copy(alpha = 0.35f))
                            }
                        }
                    } else {
                        // Progress state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (modelService.state is ModelState.Loading) {
                                LinearProgressIndicator(
                                    progress = { modelService.downloadProgress.toFloat() },
                                    modifier = Modifier.width(200.dp),
                                    color = Color(0xFF387DFF),
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                            } else {
                                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
                            }
                            Text(
                                text = statusMessage,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}