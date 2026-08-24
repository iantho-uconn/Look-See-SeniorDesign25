package looksee.angelll.com.viewmodels

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.step.AuthSignInStep
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    // MARK: Published state mapped to StateFlow
    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private val _userEmail = MutableStateFlow("")
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _userId = MutableStateFlow("")
    val userId: StateFlow<String> = _userId.asStateFlow()

    private val _requiresNewPassword = MutableStateFlow(false)
    val requiresNewPassword: StateFlow<Boolean> = _requiresNewPassword.asStateFlow()

    // TOKEN / SUB / PROFILE TRACKERS
    private val _tokenBalance = MutableStateFlow(0)
    val tokenBalance: StateFlow<Int> = _tokenBalance.asStateFlow()

    private val _activeLandmarksCount = MutableStateFlow(0)
    val activeLandmarksCount: StateFlow<Int> = _activeLandmarksCount.asStateFlow()

    private val _hasActiveSubscription = MutableStateFlow(false)
    val hasActiveSubscription: StateFlow<Boolean> = _hasActiveSubscription.asStateFlow()

    private val _stripeSubscriptionId = MutableStateFlow("")
    val stripeSubscriptionId: StateFlow<String> = _stripeSubscriptionId.asStateFlow()

    private val _activePlanCents = MutableStateFlow(0)
    val activePlanCents: StateFlow<Int> = _activePlanCents.asStateFlow()

    private val _activePlanYears = MutableStateFlow(0)
    val activePlanYears: StateFlow<Int> = _activePlanYears.asStateFlow()

    // 🚀 NEW: Personal User Identity
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _profileImageUrl = MutableStateFlow("")
    val profileImageUrl: StateFlow<String> = _profileImageUrl.asStateFlow()

    // 🚀 FIXED: Memory variable to carry the username from Signup to Login
    var pendingUsernameToSave: String = ""

    private val _storeName = MutableStateFlow("")
    val storeName: StateFlow<String> = _storeName.asStateFlow()

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber: StateFlow<String> = _phoneNumber.asStateFlow()

    private val _storeBio = MutableStateFlow("")
    val storeBio: StateFlow<String> = _storeBio.asStateFlow()

    private val _storeLogoUrl = MutableStateFlow("")
    val storeLogoUrl: StateFlow<String> = _storeLogoUrl.asStateFlow()

    fun checkSession() {
        viewModelScope.launch {
            try {
                // Wrapper to convert Java callbacks to Kotlin Coroutines
                val session = suspendCancellableCoroutine<com.amplifyframework.auth.AuthSession> { cont ->
                    Amplify.Auth.fetchAuthSession({ cont.resume(it) }, { cont.resumeWithException(it) })
                }

                _isSignedIn.value = session.isSignedIn

                if (session.isSignedIn) {
                    fetchUserDetails()
                    if (_isSignedIn.value) {
                        fetchUserUsageStats()
                    }
                }
            } catch (e: Exception) {
                _isSignedIn.value = false
            }
        }
    }

    fun signIn(usernameInput: String, passwordInput: String) {
        viewModelScope.launch {
            try {
                // Expected ghost error until AuthService is brought over
                val result = AuthService.shared.signIn(usernameInput, passwordInput)
                if (result.isSignInComplete) {
                    _isSignedIn.value = true
                    _requiresNewPassword.value = false
                    _errorMessage.value = ""
                    fetchUserDetails()

                    // 🚀 FIXED: The exact moment we get the real userId, we save the pending username!
                    if (pendingUsernameToSave.isNotEmpty()) {
                        updateUserIdentity(newUsername = pendingUsernameToSave)
                        pendingUsernameToSave = "" // Clear memory
                    }

                    fetchUserUsageStats()
                } else {
                    when (result.nextStep.signInStep) {
                        AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD -> {
                            _requiresNewPassword.value = true
                            _errorMessage.value = "Please enter a new permanent password."
                        }
                        AuthSignInStep.CONFIRM_SIGN_UP -> {
                            _errorMessage.value = "Account not verified. Please check your email for a confirmation code."
                        }
                        AuthSignInStep.RESET_PASSWORD -> {
                            _errorMessage.value = "Password reset required."
                        }
                        else -> {
                            _errorMessage.value = "Additional verification required."
                        }
                    }
                    _isSignedIn.value = false
                }
            } catch (error: Exception) {
                _errorMessage.value = friendlyMessage(error)
                _isSignedIn.value = false
            }
        }
    }

    fun confirmNewPassword(newPasswordInput: String) {
        viewModelScope.launch {
            try {
                val result = suspendCancellableCoroutine<com.amplifyframework.auth.result.AuthSignInResult> { cont ->
                    Amplify.Auth.confirmSignIn(newPasswordInput, { cont.resume(it) }, { cont.resumeWithException(it) })
                }

                if (result.isSignInComplete) {
                    _isSignedIn.value = true
                    _requiresNewPassword.value = false
                    _errorMessage.value = ""
                    fetchUserDetails()
                    fetchUserUsageStats()
                } else {
                    _errorMessage.value = "Additional steps required to sign in."
                }
            } catch (error: Exception) {
                _errorMessage.value = friendlyMessage(error)
            }
        }
    }

    fun signOut(authState: AuthState) {
        viewModelScope.launch {
            // Expected ghost error until AuthService is brought over
            AuthService.shared.signOut()
            authState.signOut()

            _isSignedIn.value = false
            _requiresNewPassword.value = false
            _tokenBalance.value = 0
            _activeLandmarksCount.value = 0
            _hasActiveSubscription.value = false
            _stripeSubscriptionId.value = ""
            _activePlanCents.value = 0
            _activePlanYears.value = 0
            _username.value = ""
            _profileImageUrl.value = ""
            _storeName.value = ""
            _phoneNumber.value = ""
            _storeBio.value = ""
            _storeLogoUrl.value = ""
            _userId.value = ""
            _userEmail.value = ""
        }
    }

    suspend fun fetchUserDetails() {
        try {
            val user = Amplify.Auth.getCurrentUser()
            _userId.value = user.userId

            val attributes = suspendCancellableCoroutine<List<com.amplifyframework.auth.AuthUserAttribute>> { cont ->
                Amplify.Auth.fetchUserAttributes({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            val emailAttr = attributes.firstOrNull { it.key.keyString == "email" }
            if (emailAttr != null) {
                _userEmail.value = emailAttr.value
            }
        } catch (error: Exception) {
            println("❌ Failed to fetch user details: $error")
            val errString = error.toString()
            if (errString.contains("userNotFound") || errString.contains("NotAuthorizedException") || errString.contains("deleted")) {
                suspendCancellableCoroutine<com.amplifyframework.auth.result.AuthSignOutResult> { cont ->
                    Amplify.Auth.signOut { cont.resume(it) }
                }
                _isSignedIn.value = false
                _userId.value = ""
                _userEmail.value = ""
                _username.value = ""
                _profileImageUrl.value = ""
                _hasActiveSubscription.value = false
                _tokenBalance.value = 0
                _activePlanCents.value = 0
                _activePlanYears.value = 0
                _stripeSubscriptionId.value = ""
            }
        }
    }

    fun fetchUserEmail() {
        viewModelScope.launch {
            fetchUserDetails()
        }
    }

    suspend fun fetchIdToken(): String {
        return try {
            val session = suspendCancellableCoroutine<com.amplifyframework.auth.AuthSession> { cont ->
                Amplify.Auth.fetchAuthSession({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            val tokenProvider = session as? AWSCognitoAuthSession
            val idToken = tokenProvider?.userPoolTokensResult?.value?.idToken
            idToken ?: ""
        } catch (error: Exception) {
            println("❌ Failed to fetch session token: $error")
            ""
        }
    }

    suspend fun fetchUserUsageStats() {
        if (_userId.value.isEmpty()) return
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats"

        withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject().apply {
                    put("userId", _userId.value)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)

                    withContext(Dispatchers.Main) {
                        val fetchedBalance = json.optInt("tokenBalance", 0)
                        val fetchedLandmarks = json.optInt("activeLandmarksCount", 0)
                        val fetchedSub = json.optBoolean("hasActiveSubscription", false)
                        val fetchedTier = json.optString("tier", "")
                        val fetchedStripeId = json.optString("stripeSubscriptionId", "")
                        val fetchedPlanCents = json.optInt("activePlanCents", 0)
                        val fetchedPlanYears = json.optInt("activePlanYears", 0)

                        _tokenBalance.value = max(_tokenBalance.value, fetchedBalance)
                        _activeLandmarksCount.value = fetchedLandmarks

                        val isSubscribedOnBackend = fetchedSub || fetchedTier == "business" || fetchedStripeId.isNotEmpty()
                        _hasActiveSubscription.value = _hasActiveSubscription.value || isSubscribedOnBackend

                        _activePlanCents.value = fetchedPlanCents
                        _activePlanYears.value = fetchedPlanYears

                        if (fetchedStripeId.isNotEmpty()) {
                            _stripeSubscriptionId.value = fetchedStripeId
                        }

                        json.optString("username", "").takeIf { it.isNotEmpty() }?.let { _username.value = it }
                        json.optString("profileImageUrl", "").takeIf { it.isNotEmpty() }?.let { _profileImageUrl.value = it }
                        json.optString("storeName", "").takeIf { it.isNotEmpty() }?.let { _storeName.value = it }
                        json.optString("phoneNumber", "").takeIf { it.isNotEmpty() }?.let { _phoneNumber.value = it }
                        json.optString("storeBio", "").takeIf { it.isNotEmpty() }?.let { _storeBio.value = it }
                        json.optString("storeLogoUrl", "").takeIf { it.isNotEmpty() }?.let { _storeLogoUrl.value = it }
                    }
                }
            } catch (error: Exception) {
                println("❌ Failed to fetch stats: ${error.localizedMessage}")
            }
        }
    }

    suspend fun cancelSubscription(): Boolean {
        if (_userId.value.isEmpty()) return false
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject().apply {
                    put("purchaseType", "cancel_subscription")
                    put("userId", _userId.value)
                    put("subscriptionId", _stripeSubscriptionId.value)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

                if (connection.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        _hasActiveSubscription.value = false
                        _stripeSubscriptionId.value = ""
                        _activePlanCents.value = 0
                        _activePlanYears.value = 0

                        val sharedPrefs = context.getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit { putBoolean("isFreeTrial_${_userEmail.value}", false) }
                    }
                    true
                } else false
            } catch (error: Exception) {
                println("❌ Failed to cancel subscription: $error")
                false
            }
        }
    }

    suspend fun updateUserIdentity(newUsername: String, profileBase64: String? = null): Pair<Boolean, String?> {
        if (_userId.value.isEmpty()) return Pair(false, "User not found")
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject().apply {
                    put("purchaseType", "update_user_identity")
                    put("userId", _userId.value)
                    put("username", newUsername)
                    put("currentUsername", _username.value)
                    put("profileImageUrl", _profileImageUrl.value)
                    if (profileBase64 != null) put("profileBase64", profileBase64)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)
                    withContext(Dispatchers.Main) {
                        json.optString("username", "").takeIf { it.isNotEmpty() }?.let { _username.value = it }
                        json.optString("profileImageUrl", "").takeIf { it.isNotEmpty() }?.let { _profileImageUrl.value = it }
                    }
                    Pair(true, null)
                } else {
                    val errorStr = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
                    if (errorStr.contains("ERR_USERNAME_TAKEN")) {
                        Pair(false, "That username is already taken.")
                    } else {
                        Pair(false, "Server Error: $errorStr")
                    }
                }
            } catch (error: Exception) {
                Pair(false, error.localizedMessage)
            }
        }
    }

    suspend fun updateBusinessProfile(newStoreName: String, newPhoneNumber: String, newStoreBio: String, newStoreLogoUrl: String, storeLogoBase64: String? = null): Boolean {
        if (_userId.value.isEmpty()) return false
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject().apply {
                    put("purchaseType", "update_profile")
                    put("userId", _userId.value)
                    put("storeName", newStoreName)
                    put("phoneNumber", newPhoneNumber)
                    put("storeBio", newStoreBio)
                    put("storeLogoUrl", newStoreLogoUrl)
                    if (storeLogoBase64 != null) put("storeLogoBase64", storeLogoBase64)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = try { JSONObject(responseStr) } catch (e: Exception) { null }

                    withContext(Dispatchers.Main) {
                        _storeName.value = newStoreName
                        _phoneNumber.value = newPhoneNumber
                        _storeBio.value = newStoreBio
                        _storeLogoUrl.value = json?.optString("logoUrl")?.takeIf { it.isNotEmpty() } ?: newStoreLogoUrl
                    }
                    true
                } else {
                    val errorStr = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    println("❌ Backend Rejected Upload (${connection.responseCode}): $errorStr")
                    false
                }
            } catch (error: Exception) {
                println("❌ Failed to update business profile: $error")
                false
            }
        }
    }

    private fun friendlyMessage(error: Exception): String {
        val description = error.message ?: error.cause?.toString() ?: ""
        if (description.contains("NotAuthorizedException") || description.contains("Incorrect username or password")) {
            return "Incorrect email or password. Please try again."
        }
        if (description.contains("UserNotFound")) {
            return "No account found with that email."
        }
        if (description.contains("UserNotConfirmed")) {
            return "Please verify your email."
        }
        return "Something went wrong. Please try again."
    }
}