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
import com.amplifyframework.AmplifyException
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
        } catch (error: AmplifyException) {
            Log.i("AmplifyEngine", "ℹ️ Amplify was already configured.")
        } catch (error: Exception) {
            Log.e("AmplifyEngine", "❌ Failed to configure Amplify", error)
        }
    }

    private fun configureSentry() {
        try {
            SentryAndroid.init(this) { options ->
                options.dsn = "https://e9ee0e43b4735fe777a4d240a4423a56@o4512005291573248.ingest.us.sentry.io/4512005296816128"
                options.tracesSampleRate = 1.0
                options.profilesSampleRate = 1.0
            }
            Log.i("SentryEngine", "✅ Sentry configured")
        } catch (error: Exception) {
            Log.e("SentryEngine", "❌ Failed to configure Sentry", error)
        }
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
    GuestSignUp,
    ScanHistory,
    BusinessAnalytics
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


    var isModelLoadingDone by remember { mutableStateOf(false) }
    var isAuthResolutionDone by remember { mutableStateOf(false) }

    fun advanceIfReady() {
        if (isModelLoadingDone && isAuthResolutionDone) {
            appState = AppState.Main
            isModelLoadingDone = false
            isAuthResolutionDone = false
        }
    }

    when (appState) {
        AppState.LoadingModel -> {
            LaunchedEffect(Unit) {
                vm.checkSession()
                if (vm.isSignedIn) {
                    authState.resolveTier()
                }
                isAuthResolutionDone = true
                advanceIfReady()
            }

            ModelLoadingScreen(
                onComplete = {
                    isModelLoadingDone = true
                    advanceIfReady()
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
                        "HistoryView" -> appState = AppState.ScanHistory
                        "BusinessAnalyticsView" -> appState = AppState.BusinessAnalytics
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
                    } else if (route == "back" || route == "Dismiss") {
                        appState = AppState.Settings
                    }
                }
            )
        }

        AppState.BusinessLandmarkDetail -> {
            selectedLandmark?.let { landmark ->
                BusinessLandmarkDetailView(
                    vm = vm,
                    initialLandmark = landmark,
                    onNavigate = { _, _ -> },
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
                initialBusinessAccount = false,
                onSignupSuccess = { email, wantsBusiness ->
                    appState = AppState.LoadingModel
                },
                onGoToLogin = {
                    appState = AppState.Login
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

        AppState.ScanHistory -> {
            HistoryView(vm = vm, onBack = { appState = AppState.Settings })
        }

        AppState.BusinessAnalytics -> {
            BusinessAnalyticsView(vm = vm, onBack = { appState = AppState.Settings })
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
            SignupScreen(
                vm = vm,
                initialBusinessAccount = false,
                onSignupSuccess = { email, wantsBusiness ->
                    appState = AppState.LoadingModel
                },
                onNavigate = { route ->
                    if (route == "login") {
                        appState = AppState.Login
                    }
                }
            )
        }

        AppState.ForgotPassword -> {
            ForgotPasswordView(
                onDismiss = { appState = AppState.Login }
            )
        }
    }
}