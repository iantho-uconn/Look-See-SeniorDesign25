package looksee.angelll.com

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import com.amplifyframework.storage.s3.AWSS3StoragePlugin
import kotlinx.coroutines.launch
import looksee.angelll.com.ui.theme.LookSeeTheme

// 🚀 FIXED: Pointing to the correct 'viewmodels' folder!
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.viewmodels.UserTier

class MainActivity : ComponentActivity() {

    // Initialize our ViewModels bound to the Activity lifecycle
    private val authViewModel: AuthViewModel by viewModels()
    private val authState: AuthState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Boot up AWS when the app launches (From LookSeeProtoApp.swift)
        configureAmplify()

        setContent {
            LookSeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Start the routing traffic cop!
                    RootView(vm = authViewModel, authState = authState)
                }
            }
        }
    }

    private fun configureAmplify() {
        try {
            // Add the Auth plugin (Cognito)
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            // Initializes Storage (From LookSeeProtoApp.swift)
            Amplify.addPlugin(AWSS3StoragePlugin())

            // Tell Amplify to configure itself using amplifyconfiguration.json
            Amplify.configure(applicationContext)
            Log.i("AmplifyEngine", "✅ Amplify configured")
        } catch (error: Exception) {
            Log.e("AmplifyEngine", "❌ Failed to configure Amplify", error)
        }
    }
}

// MARK: - App State Enum
enum class AppState {
    CheckingSession,
    Login,
    Signup,
    LoadingModel,
    Main
}

// MARK: - Root View (Translated from RootView.swift)
@Composable
fun RootView(vm: AuthViewModel, authState: AuthState) {
    var appState by remember { mutableStateOf(AppState.CheckingSession) }
    var pendingEmail by remember { mutableStateOf("") }

    var isModelLoadingDone by remember { mutableStateOf(false) }
    var isAuthResolutionDone by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Observe ViewModel states
    val isSignedIn by vm.isSignedIn.collectAsState()
    val didSignOut by authState.didSignOut.collectAsState()

    fun resetLoadingFlags() {
        isModelLoadingDone = false
        isAuthResolutionDone = false
    }

    fun advanceIfReady() {
        if (!isModelLoadingDone || !isAuthResolutionDone) {
            Log.d("RootView", "[RootView] Waiting — modelDone: $isModelLoadingDone, authDone: $isAuthResolutionDone")
            return
        }
        Log.d("RootView", "✅ [RootView] Both ready — advancing to .main")
        resetLoadingFlags()
        appState = AppState.Main
    }

    // React to sign out events globally
    LaunchedEffect(didSignOut) {
        if (didSignOut) {
            resetLoadingFlags()
            appState = AppState.LoadingModel
        }
    }

    when (appState) {
        AppState.CheckingSession -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            LaunchedEffect(Unit) {
                Log.d("RootView", "[RootView] Entering loadingModel — auth + model load run concurrently")
                appState = AppState.LoadingModel
            }
        }

        AppState.Login -> {
            LoginScreenPlaceholder(
                onSignedIn = {
                    coroutineScope.launch {
                        authState.resolveTier()
                        appState = AppState.LoadingModel
                    }
                },
                onGoToSignup = { appState = AppState.Signup },
                onContinueAsGuest = {
                    appState = AppState.LoadingModel
                }
            )
        }

        AppState.Signup -> {
            SignupScreenPlaceholder(
                onSignupSuccess = { email ->
                    pendingEmail = email
                    appState = AppState.LoadingModel
                },
                onGoToLogin = { appState = AppState.Login }
            )
        }

        AppState.LoadingModel -> {
            ModelLoadingScreenPlaceholder(
                onModelLoaded = {
                    Log.d("RootView", "🧠 [RootView] Model loading finished")
                    isModelLoadingDone = true
                    advanceIfReady()
                }
            )
            LaunchedEffect(Unit) {
                vm.checkSession()
                Log.d("RootView", "[RootView] checkSession complete — isSignedIn: $isSignedIn")
                if (isSignedIn) {
                    authState.resolveTier()
                    Log.d("RootView", " [RootView] resolveTier complete — tier resolved")
                }
                isAuthResolutionDone = true
                advanceIfReady()
            }
        }

        AppState.Main -> {
            MainScreenPlaceholder()
        }
    }
}

// =====================================================================
// 🚧 PLACEHOLDER SCREENS (Delete these once you translate the real files)
// =====================================================================

@Composable
fun LoginScreenPlaceholder(onSignedIn: () -> Unit, onGoToSignup: () -> Unit, onContinueAsGuest: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Login Screen Placeholder")
        Button(onClick = onSignedIn) { Text("Simulate Sign In") }
        Button(onClick = onGoToSignup) { Text("Go to Signup") }
        Button(onClick = onContinueAsGuest) { Text("Continue as Guest") }
    }
}

@Composable
fun SignupScreenPlaceholder(onSignupSuccess: (String) -> Unit, onGoToLogin: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Signup Screen Placeholder")
        Button(onClick = { onSignupSuccess("test@test.com") }) { Text("Simulate Signup") }
        Button(onClick = onGoToLogin) { Text("Go to Login") }
    }
}

@Composable
fun ModelLoadingScreenPlaceholder(onModelLoaded: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text("Loading Models & Checking Auth...")
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500L) // 🚀 FIXED: Added "L" to satisfy the warning!
            onModelLoaded()
        }
    }
}

@Composable
fun MainScreenPlaceholder() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Main App Interface", style = MaterialTheme.typography.headlineMedium)
        Text("You made it past the routing!")
    }
}