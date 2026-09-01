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
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.launch
import looksee.angelll.com.ui.theme.LookSeeTheme
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.uifiles.*
import looksee.angelll.com.models.BusinessLandmark

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val authState: AuthState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAmplify()
        configureSentry()

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

    private fun configureSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn = "https://e9ee0e43b4735fe777a4d240a4423a56@o4512005291573248.ingest.us.sentry.io/4512005296816128"
            // Set tracesSampleRate to 1.0 to capture 100% of transactions for performance monitoring.
            options.tracesSampleRate = 1.0
            // Set profilesSampleRate to 1.0 to enable CPU profiling.
            options.profilesSampleRate = 1.0
        }
        Log.i("SentryEngine", "✅ Sentry configured")
    }
}

enum class AppState {
    LoadingModel,
    Login,
    Signup,
    ForgotPassword,
    Main,
    Settings,
    DeepSettings,
    Archive,
    ReportIssue,
    Help,
    PrivacyPolicy,
    TermsOfService,
    ModelSelection,
    BusinessLandmarks,
    BusinessLandmarkDetail,
    AccountSecurity,
    BusinessProfile,
    GuestSignUp
}

@Composable
fun RootView(vm: AuthViewModel, authState: AuthState) {
    var appState by remember { mutableStateOf(AppState.LoadingModel) }
    val coroutineScope = rememberCoroutineScope()
    val didSignOut by authState.didSignOut.collectAsState(initial = false)

    var selectedLandmark by remember { mutableStateOf<BusinessLandmark?>(null) }

    LaunchedEffect(didSignOut) {
        if (didSignOut) {
            appState = AppState.LoadingModel
        }
    }

    when (appState) {
        AppState.LoadingModel -> {
            ModelLoadingScreen(
                onComplete = {
                    appState = AppState.Main
                    coroutineScope.launch {
                        vm.checkSession()
                        if (vm.isSignedIn) {
                            authState.resolveTier()
                        }
                    }
                }
            )
        }

        AppState.Main -> {
            ButtonsScreen(
                vm = vm,
                onNavigate = { route ->
                    when (route) {
                        "Settings" -> appState = AppState.Settings
                        "login" -> appState = AppState.Login
                        "guest_signup" -> appState = AppState.GuestSignUp
                        "BusinessLandmarksView" -> appState = AppState.BusinessLandmarks
                    }
                }
            )
        }

        AppState.Settings -> {
            SettingsScreen(
                vm = vm,
                onDismiss = { appState = AppState.Main },
                onNavigate = { route ->
                    when (route) {
                        "BusinessLandmarksView" -> appState = AppState.BusinessLandmarks
                        "AccountSecurityView" -> appState = AppState.AccountSecurity
                        "ArchiveView" -> appState = AppState.Archive
                        "ReportIssueView" -> appState = AppState.ReportIssue
                        "Help" -> appState = AppState.Help
                        "PrivacyPolicy" -> appState = AppState.PrivacyPolicy
                        "TermsOfService" -> appState = AppState.TermsOfService
                        "DeepSettings" -> appState = AppState.DeepSettings
                        "BusinessProfileView" -> appState = AppState.BusinessProfile
                        "login" -> appState = AppState.Login
                        "signup" -> appState = AppState.Signup
                        "guest_signup" -> appState = AppState.GuestSignUp
                    }
                }
            )
        }

        AppState.BusinessLandmarks -> {
            BusinessLandmarksView(
                vm = vm,
                onNavigate = { route, payload ->
                    if (route == "BusinessLandmarkDetailView" && payload is BusinessLandmark) {
                        selectedLandmark = payload
                        appState = AppState.BusinessLandmarkDetail
                    } else if (route == "back") {
                        appState = AppState.Settings
                    } else if (route == "LandmarkRecord" && payload is looksee.angelll.com.models.ArchivedMedia) {
                        // Special case for routing from upload queue to record screen?
                        // Actually, I'll just open record screen with payload later.
                    }
                }
            )
        }

        AppState.BusinessLandmarkDetail -> {
            selectedLandmark?.let { landmark ->
                BusinessLandmarkDetailView(
                    initialLandmark = landmark,
                    onNavigate = { route, payload ->
                        // Handle potential sub-navigation if needed
                    },
                    onDismiss = { appState = AppState.BusinessLandmarks }
                )
            }
        }

        AppState.AccountSecurity -> {
            AccountSecurityView(
                vm = vm,
                authState = authState,
                onDismiss = { appState = AppState.Settings }
            )
        }

        AppState.BusinessProfile -> {
            BusinessProfileScreen(
                vm = vm,
                onDismiss = { appState = AppState.Settings }
            )
        }

        AppState.GuestSignUp -> {
            GuestSignUpView(
                vm = vm,
                onNavigate = { route ->
                    if (route.startsWith("confirm_signup/")) {
                        appState = AppState.Login // Or handle confirmation specifically
                    } else if (route == "login") {
                        appState = AppState.Login
                    }
                },
                onDismiss = { appState = AppState.Settings }
            )
        }

        AppState.DeepSettings -> {
            DeepSettingsView(
                vm = vm,
                authState = authState,
                onBack = { appState = AppState.Settings },
                onNavigate = { route ->
                    if (route == "ModelSelectionView") appState = AppState.ModelSelection
                }
            )
        }

        AppState.ModelSelection -> {
            ModelSelectionView(
                onBack = { appState = AppState.DeepSettings }
            )
        }

        AppState.Archive -> {
            ArchiveView(vm = vm, onBack = { appState = AppState.Settings })
        }

        AppState.ReportIssue -> {
            ReportIssueView(vm = vm, onDismiss = { appState = AppState.Settings })
        }

        AppState.Help -> {
            HelpScreen(onBack = { appState = AppState.Settings })
        }

        AppState.PrivacyPolicy -> {
            PrivacyPolicy(onDismiss = { appState = AppState.Settings })
        }

        AppState.TermsOfService -> {
            TermsOfService(onDismiss = { appState = AppState.Settings })
        }

        AppState.Login -> {
            LoginScreen(
                vm = vm,
                onNavigate = { route ->
                    when (route) {
                        "main" -> appState = AppState.Main
                        "signup" -> appState = AppState.Signup
                        "forgot_password" -> appState = AppState.ForgotPassword
                    }
                }
            )
        }

        AppState.Signup -> {
            Signup(
                vm = vm,
                onSignupSuccess = { appState = AppState.Login },
                onGoToLogin = { appState = AppState.Login }
            )
        }

        AppState.ForgotPassword -> {
            ForgotPasswordView(
                onDismiss = { appState = AppState.Login }
            )
        }
    }
}
