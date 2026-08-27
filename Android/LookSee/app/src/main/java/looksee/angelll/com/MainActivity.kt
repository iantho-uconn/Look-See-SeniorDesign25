package looksee.angelll.com

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import com.amplifyframework.storage.s3.AWSS3StoragePlugin
import looksee.angelll.com.ui.theme.LookSeeTheme
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.uifiles.*

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val authState: AuthState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAmplify()

        setContent {
            LookSeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootView(vm = authViewModel, authState = authState)
                }
            }
        }
    }

    private fun configureAmplify() {
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.addPlugin(AWSS3StoragePlugin())
            Amplify.configure(applicationContext)
            Log.i("AmplifyEngine", "✅ Amplify configured")
        } catch (error: Exception) {
            Log.e("AmplifyEngine", "❌ Failed to configure Amplify", error)
        }
    }
}

enum class AppState {
    Splash,
    CheckingSession,
    Login,
    Signup,
    LoadingModel,
    Main
}

@Composable
fun RootView(vm: AuthViewModel, authState: AuthState) {
    var appState by remember { mutableStateOf(AppState.Splash) }
    var isModelLoadingDone by remember { mutableStateOf(false) }
    var isAuthResolutionDone by remember { mutableStateOf(false) }

    val didSignOut by authState.didSignOut.collectAsState(initial = false)

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

    LaunchedEffect(didSignOut) {
        if (didSignOut) {
            resetLoadingFlags()
            appState = AppState.LoadingModel
        }
    }

    when (appState) {
        AppState.Splash -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                LoadingAnimationScreen(onFinished = {
                    appState = AppState.CheckingSession
                })
            }
        }

        AppState.CheckingSession -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            LaunchedEffect(Unit) {
                appState = AppState.LoadingModel
            }
        }

        AppState.Login -> {
            LoginScreen(
                vm = vm,
                onNavigate = { route ->
                    if (route == "main") {
                        appState = AppState.LoadingModel
                    } else if (route == "signup") {
                        appState = AppState.Signup
                    }
                }
            )
        }

        AppState.Signup -> {
            Signup(
                vm = vm,
                onSignupSuccess = {
                    appState = AppState.LoadingModel
                },
                onGoToLogin = { appState = AppState.Login }
            )
        }

        AppState.LoadingModel -> {
            ModelLoadingScreen(
                onModelsLoaded = {
                    Log.d("RootView", "🧠 [RootView] Model loading finished")
                    isModelLoadingDone = true
                    advanceIfReady()
                }
            )
            LaunchedEffect(Unit) {
                vm.checkSession()
                if (vm.isSignedIn) {
                    authState.resolveTier()
                }
                isAuthResolutionDone = true
                advanceIfReady()
            }
        }

        AppState.Main -> {
            ButtonsScreen(
                vm = vm,
                onNavigate = { route ->
                    Log.d("RootView", "Navigating to: $route")
                    if (route == "login") appState = AppState.Login
                }
            )
        }
    }
}
