package looksee.angelll.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import looksee.angelll.com.models.ModelSelector
import looksee.angelll.com.ui.theme.LookSeeTheme

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
    val detector = remember(context) {
        Detector(ModelSelector.shared(context.applicationContext))
    }

    var zoomLevel by rememberSaveable { mutableStateOf(1f) }
    var isAIPaused by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    val cameraStatus = cameraErrorMessage ?: when {
        !cameraPermissionGranted -> "Camera permission is required"
        isAIPaused -> "Camera paused"
        else -> "Camera active"
    }

    BackHandler(onBack = onBack)

    DisposableEffect(detector) {
        onDispose { detector.close() }
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
