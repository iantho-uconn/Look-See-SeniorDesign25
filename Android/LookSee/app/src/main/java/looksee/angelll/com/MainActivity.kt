package looksee.angelll.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import looksee.angelll.com.detection.CameraPreview
import looksee.angelll.com.detection.Detector
import looksee.angelll.com.detection.LocationManager
import looksee.angelll.com.detection.LookSeeLocationState
import looksee.angelll.com.detection.detectorHudState
import looksee.angelll.com.models.ModelAutoRefreshService
import looksee.angelll.com.models.ModelService
import looksee.angelll.com.models.ModelSelector
import looksee.angelll.com.models.ModelState
import looksee.angelll.com.ui.theme.LookSeeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚀 Boot up AWS when the app launches
        configureAmplify()

        setContent {
            LookSeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LookSeeApp()
                }
            }
        }
    }

    private fun configureAmplify() {
        try {
            // Add the Auth plugin (Cognito)
            Amplify.addPlugin(AWSCognitoAuthPlugin())

            // Tell Amplify to configure itself using that JSON file we pasted
            Amplify.configure(applicationContext)
            Log.i("AmplifyEngine", "Initialized Amplify successfully")
        } catch (error: Exception) {
            Log.e("AmplifyEngine", "Could not initialize Amplify", error)
        }
    }
}

@Composable
private fun LookSeeApp() {
    var isScanning by rememberSaveable { mutableStateOf(false) }

    if (isScanning) {
        LookSeeScannerScreen(onBack = { isScanning = false })
    } else {
        LookSeeWelcomeScreen(onStartScanning = { isScanning = true })
    }
}

@Composable
fun LookSeeWelcomeScreen(onStartScanning: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Welcome to LookSee Android!")

        Button(onClick = {
            Log.i("UserAction", "Start Scanning Tapped!")
            onStartScanning()
        }) {
            Text("Start Scanning")
        }
    }
}

@Composable
private fun LookSeeScannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val modelService = remember(applicationContext) {
        ModelService.shared(applicationContext)
    }
    val modelSelector = remember(applicationContext) {
        ModelSelector.shared(applicationContext)
    }
    val modelAutoRefreshService = remember(applicationContext) {
        ModelAutoRefreshService.shared(applicationContext)
    }
    val locationManager = remember(applicationContext) {
        LocationManager(applicationContext)
    }
    val detector = remember(modelSelector) {
        Detector(
            modelSelector = modelSelector,
            allowSyntheticPreview = BuildConfig.DEBUG,
        )
    }
    val coroutineScope = rememberCoroutineScope()

    val detections by detector.detections.collectAsState()
    val loadState by detector.loadState.collectAsState()
    val lastInferenceMs by detector.lastInferenceMs.collectAsState()
    val isSyntheticPreviewEnabled by detector.isSyntheticPreviewEnabled.collectAsState()
    val modelState by modelService.state.collectAsState()
    val downloadProgress by modelService.downloadProgress.collectAsState()
    val activeRelease by modelSelector.activeRelease.collectAsState()
    val locationState by locationManager.state.collectAsState()

    var zoomLevel by rememberSaveable { mutableStateOf(1f) }
    var isAIPaused by rememberSaveable { mutableStateOf(false) }
    var initialModelLoadStarted by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    var locationPermissionGranted by remember {
        mutableStateOf(locationManager.hasLocationPermission())
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        locationPermissionGranted =
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    locationManager.hasLocationPermission()
    }

    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    LaunchedEffect(locationPermissionGranted) {
        if (locationPermissionGranted) locationManager.start()
    }

    val locationFix = (locationState as? LookSeeLocationState.Ready)?.fix
    LaunchedEffect(locationFix) {
        val fix = locationFix ?: return@LaunchedEffect

        modelAutoRefreshService.updateLocation(fix.latitude, fix.longitude)
        detector.updateUserLocation(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters.toDouble(),
        )

        if (!initialModelLoadStarted) {
            initialModelLoadStarted = true
            coroutineScope.launch {
                modelService.loadModels(fix.latitude, fix.longitude)
                modelAutoRefreshService.start()
            }
        }
    }

    LaunchedEffect(modelState) {
        val loaded = modelState as? ModelState.Loaded ?: return@LaunchedEffect
        if (loaded.models.isNotEmpty()) {
            // Chunk D pins the sole bundled debug model for explicit testing.
            // Once a live release is installed, return selection to proximity mode.
            modelSelector.useAutomaticModelSelection()
        }
    }
    val cameraStatus = cameraErrorMessage ?: when {
        !cameraPermissionGranted -> "Camera permission is required"
        isAIPaused -> "Camera paused"
        else -> "Camera active"
    }
    val detectionStatus = detectorHudState(
        loadState = loadState,
        detectionCount = detections.size,
        lastInferenceMilliseconds = lastInferenceMs,
        isPaused = isAIPaused,
        isSyntheticPreviewEnabled = isSyntheticPreviewEnabled,
    )

    BackHandler(onBack = onBack)

    DisposableEffect(detector, locationManager, modelAutoRefreshService) {
        onDispose {
            modelAutoRefreshService.stop()
            locationManager.close()
            detector.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            detector = detector,
            zoomLevel = zoomLevel,
            onZoomLevelChange = { zoomLevel = it },
            showSafeZone = false,
            safeZoneRect = null,
            onTap = {},
            onPinch = {},
            isAIPaused = isAIPaused,
            onBoxTap = { detection ->
                Log.i("Detection", "Tapped ${detection.displayLabel()}")
            },
            modifier = Modifier.fillMaxSize(),
            onCameraPermissionResult = { granted ->
                cameraPermissionGranted = granted
                if (granted) cameraErrorMessage = null
            },
            onCameraError = { error ->
                cameraErrorMessage = error.message ?: "Unable to start camera."
                Log.e("CameraPreview", "Unable to start camera", error)
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) {
                Text("Back")
            }

            Button(onClick = { isAIPaused = !isAIPaused }) {
                Text(if (isAIPaused) "Resume" else "Pause")
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(cameraStatus)
                Text(
                    text = detectionStatus.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = detectionStatus.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = locationStatusText(locationState),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = modelDeliveryStatusText(modelState, downloadProgress),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Active: ${activeRelease?.displayName ?: "none"}",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (locationState == LookSeeLocationState.PermissionRequired) {
                    Button(
                        onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Enable location")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text("Detections: ${detectionStatus.detectionCount}")
                    Text(
                        detectionStatus.inferenceMilliseconds?.let {
                            String.format("Inference: %.1f ms", it)
                        } ?: "Inference: —",
                    )
                }

                if (BuildConfig.DEBUG) {
                    Button(
                        enabled = locationFix != null && modelState != ModelState.Loading,
                        onClick = {
                            locationFix?.let { fix ->
                                coroutineScope.launch {
                                    modelService.loadModels(fix.latitude, fix.longitude)
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Retry live model")
                    }

                    Button(
                        onClick = {
                            detector.setSyntheticPreviewEnabled(!isSyntheticPreviewEnabled)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(
                            if (isSyntheticPreviewEnabled) {
                                "Stop overlay test"
                            } else {
                                "Test overlay (no model)"
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { zoomLevel = (zoomLevel - 0.5f).coerceAtLeast(1f) }) {
                        Text("−")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(String.format("%.1fx", zoomLevel))
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { zoomLevel = (zoomLevel + 0.5f).coerceAtMost(5f) }) {
                        Text("+")
                    }
                }
            }
        }
    }
}

private fun locationStatusText(state: LookSeeLocationState): String = when (state) {
    LookSeeLocationState.PermissionRequired -> "Location: permission required"
    LookSeeLocationState.Searching -> "Location: searching…"
    is LookSeeLocationState.Ready ->
        "Location: ready (±${state.fix.accuracyMeters.toInt()}m)"
    is LookSeeLocationState.Unavailable -> "Location: ${state.message}"
}

private fun modelDeliveryStatusText(state: ModelState, progress: Double): String = when (state) {
    ModelState.NotLoaded -> "Live model: waiting for location"
    ModelState.Loading ->
        "Live model: downloading ${(progress.coerceIn(0.0, 1.0) * 100).toInt()}%"
    is ModelState.Loaded -> {
        if (state.models.isEmpty()) {
            "Live model: none returned for this location"
        } else {
            val clusters = state.models.joinToString { it.clusterId }
            "Live model: available (cluster $clusters)"
        }
    }
    is ModelState.Failed -> "Live model failed: ${state.message}"
}
