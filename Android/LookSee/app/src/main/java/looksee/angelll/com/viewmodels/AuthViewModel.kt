package looksee.angelll.com.viewmodels

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.step.AuthSignInStep
import com.amplifyframework.kotlin.core.Amplify // 🚀 THE MAGIC FIX: Using the Kotlin Facade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    // MARK: - State Properties
    var isSignedIn by mutableStateOf(false)
    var errorMessage by mutableStateOf("")
    var userEmail by mutableStateOf("")
    var userId by mutableStateOf("")

    var requiresNewPassword by mutableStateOf(false)

    // TOKEN / SUB / PROFILE TRACKERS
    var tokenBalance by mutableIntStateOf(0)
    var activeLandmarksCount by mutableIntStateOf(0)
    var hasActiveSubscription by mutableStateOf(false)
    var stripeSubscriptionId by mutableStateOf("")

    var activePlanCents by mutableIntStateOf(0)
    var activePlanYears by mutableIntStateOf(0)

    // Personal User Identity
    var username by mutableStateOf("")
    var profileImageUrl by mutableStateOf("")

    // Memory variable to carry the username from Signup to Login
    var pendingUsernameToSave by mutableStateOf("")

    // Website and Address properties
    var storeName by mutableStateOf("")
    var phoneNumber by mutableStateOf("")
    var storeWebsite by mutableStateOf("")
    var storeAddress by mutableStateOf("")
    var storeBio by mutableStateOf("")
    var storeLogoUrl by mutableStateOf("")

    // MARK: - Core Methods

    fun checkSession() {
        viewModelScope.launch {
            try {
                val session = Amplify.Auth.fetchAuthSession()
                isSignedIn = session.isSignedIn

                if (isSignedIn) {
                    fetchUserDetails()
                    if (isSignedIn) {
                        fetchUserUsageStats()
                    }
                }
            } catch (_: Exception) {
                isSignedIn = false
            }
        }
    }

    fun signIn(usernameInput: String, passwordInput: String) {
        viewModelScope.launch {
            try {
                val result = Amplify.Auth.signIn(usernameInput, passwordInput)
                if (result.isSignInComplete) {
                    isSignedIn = true
                    requiresNewPassword = false
                    errorMessage = ""

                    // 🚀 Capture the typed email instantly to bypass state delays
                    val guaranteedEmail = usernameInput
                    userEmail = guaranteedEmail

                    try {
                        val user = Amplify.Auth.getCurrentUser()
                        userId = user.userId
                    } catch (_: Exception) { /* User might not be fully cached yet */ }

                    // 🚀 Force-feed the guaranteed email directly into the network payloads
                    initDatabaseRow(emailToSave = guaranteedEmail)

                    if (pendingUsernameToSave.isNotEmpty()) {
                        updateUserIdentity(newUsername = pendingUsernameToSave, emailToSave = guaranteedEmail)
                        pendingUsernameToSave = "" // Clear memory
                    }

                    fetchUserDetails()
                    fetchUserUsageStats()
                } else {
                    when (result.nextStep.signInStep) {
                        AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD -> {
                            requiresNewPassword = true
                            errorMessage = "Please enter a new permanent password."
                        }
                        AuthSignInStep.CONFIRM_SIGN_UP -> {
                            errorMessage = "Account not verified. Please check your email for a confirmation code."
                        }
                        AuthSignInStep.RESET_PASSWORD -> {
                            errorMessage = "Password reset required."
                        }
                        else -> {
                            errorMessage = "Additional verification required."
                        }
                    }
                    isSignedIn = false
                }
            } catch (error: AuthException) {
                errorMessage = friendlyMessage(error)
                isSignedIn = false
            } catch (_: Exception) {
                errorMessage = "Something went wrong. Please try again."
                isSignedIn = false
            }
        }
    }

    fun confirmNewPassword(newPassword: String) {
        viewModelScope.launch {
            try {
                val result = Amplify.Auth.confirmSignIn(newPassword)
                if (result.isSignInComplete) {
                    isSignedIn = true
                    requiresNewPassword = false
                    errorMessage = ""

                    try {
                        val user = Amplify.Auth.getCurrentUser()
                        userId = user.userId
                    } catch (_: Exception) { /* Ignored */ }

                    initDatabaseRow(emailToSave = userEmail)
                    fetchUserDetails()
                    fetchUserUsageStats()
                } else {
                    errorMessage = "Additional steps required to sign in."
                }
            } catch (error: AuthException) {
                errorMessage = friendlyMessage(error)
            } catch (_: Exception) {
                errorMessage = "Failed to update password. Please try again."
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                Amplify.Auth.signOut()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                isSignedIn = false
                requiresNewPassword = false
                tokenBalance = 0
                activeLandmarksCount = 0
                hasActiveSubscription = false
                stripeSubscriptionId = ""
                activePlanCents = 0
                activePlanYears = 0
                username = ""
                profileImageUrl = ""
                storeName = ""
                phoneNumber = ""
                storeWebsite = ""
                storeAddress = ""
                storeBio = ""
                storeLogoUrl = ""
                userId = ""
                userEmail = ""
            }
        }
    }

    suspend fun fetchUserDetails() {
        try {
            val user = Amplify.Auth.getCurrentUser()
            userId = user.userId

            val attributes = Amplify.Auth.fetchUserAttributes()
            val emailAttr = attributes.find { it.key.keyString == "email" }
            if (emailAttr != null) {
                userEmail = emailAttr.value
            }
        } catch (error: Exception) {
            println("❌ Failed to fetch user details: $error")
            val errString = error.toString().lowercase()
            if (errString.contains("usernotfound") ||
                errString.contains("notauthorizedexception") ||
                errString.contains("deleted")) {

                try { Amplify.Auth.signOut() } catch (_: Exception) { }

                withContext(Dispatchers.Main) {
                    isSignedIn = false
                    userId = ""
                    userEmail = ""
                    username = ""
                    profileImageUrl = ""
                    hasActiveSubscription = false
                    tokenBalance = 0
                    activePlanCents = 0
                    activePlanYears = 0
                    stripeSubscriptionId = ""
                }
            }
        }
    }

    fun fetchUserEmail() {
        viewModelScope.launch {
            fetchUserDetails()
        }
    }

    suspend fun fetchIdToken(): String {
        try {
            val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            return session?.userPoolTokensResult?.value?.idToken ?: ""
        } catch (e: Exception) {
            println("❌ Failed to fetch session token: $e")
        }
        return ""
    }

    // MARK: - Networking & API Calls

    suspend fun fetchUserUsageStats() {
        if (userId.isEmpty()) return

        val url = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats"
        val body = JSONObject().apply { put("userId", userId) }

        val (code, data) = makePostRequest(url, body)
        if (code == 200 && data != null) {
            val json = JSONObject(data)
            withContext(Dispatchers.Main) {
                val fetchedBalance = json.optInt("tokenBalance", 0)
                val fetchedLandmarks = json.optInt("activeLandmarksCount", 0)
                val fetchedSub = json.optBoolean("hasActiveSubscription", false)
                val fetchedTier = json.optString("tier", "")
                val fetchedStripeId = json.optString("stripeSubscriptionId", "")

                val fetchedPlanCents = json.optInt("activePlanCents", 0)
                val fetchedPlanYears = json.optInt("activePlanYears", 0)

                tokenBalance = maxOf(tokenBalance, fetchedBalance)
                activeLandmarksCount = fetchedLandmarks

                val isSubscribedOnBackend = fetchedSub || fetchedTier == "business" || fetchedStripeId.isNotEmpty()
                hasActiveSubscription = hasActiveSubscription || isSubscribedOnBackend

                activePlanCents = fetchedPlanCents
                activePlanYears = fetchedPlanYears

                if (fetchedStripeId.isNotEmpty()) {
                    stripeSubscriptionId = fetchedStripeId
                }

                json.optString("username", "").takeIf { it.isNotEmpty() }?.let { username = it }
                json.optString("profileImageUrl", "").takeIf { it.isNotEmpty() }?.let { profileImageUrl = it }

                json.optString("storeName", "").takeIf { it.isNotEmpty() }?.let { storeName = it }
                json.optString("phoneNumber", "").takeIf { it.isNotEmpty() }?.let { phoneNumber = it }
                json.optString("storeWebsite", "").takeIf { it.isNotEmpty() }?.let { storeWebsite = it }
                json.optString("storeAddress", "").takeIf { it.isNotEmpty() }?.let { storeAddress = it }
                json.optString("storeBio", "").takeIf { it.isNotEmpty() }?.let { storeBio = it }
                json.optString("storeLogoUrl", "").takeIf { it.isNotEmpty() }?.let { storeLogoUrl = it }
            }
        } else {
            println("❌ Failed to fetch stats. Code: $code")
        }
    }

    suspend fun cancelSubscription(): Boolean {
        if (userId.isEmpty()) return false
        val url = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        val body = JSONObject().apply {
            put("purchaseType", "cancel_subscription")
            put("userId", userId)
            put("subscriptionId", stripeSubscriptionId)
        }

        val (code, _) = makePostRequest(url, body)
        return if (code == 200) {
            withContext(Dispatchers.Main) {
                hasActiveSubscription = false
                stripeSubscriptionId = ""
                activePlanCents = 0
                activePlanYears = 0

                val prefs = getApplication<Application>().getSharedPreferences("LookSeePrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("isFreeTrial_$userEmail", false).apply()
            }
            true
        } else {
            println("❌ Failed to cancel subscription")
            false
        }
    }

    suspend fun updateUserIdentity(newUsername: String, emailToSave: String, profileBase64: String? = null): Pair<Boolean, String?> {
        if (userId.isEmpty()) return Pair(false, "User not found")
        val url = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        val body = JSONObject().apply {
            put("purchaseType", "update_user_identity")
            put("userId", userId)
            put("userEmail", emailToSave) // 🚀 Forced parameter injection
            put("username", newUsername)
            put("currentUsername", username)
            put("profileImageUrl", profileImageUrl)
            profileBase64?.let { put("profileBase64", it) }
        }

        val (code, data) = makePostRequest(url, body)
        return if (code == 200 && data != null) {
            val json = JSONObject(data)
            withContext(Dispatchers.Main) {
                json.optString("username", "").takeIf { it.isNotEmpty() }?.let { username = it }
                json.optString("profileImageUrl", "").takeIf { it.isNotEmpty() }?.let { profileImageUrl = it }
            }
            Pair(true, null)
        } else {
            val errStr = data ?: "Unknown Error"
            if (errStr.contains("ERR_USERNAME_TAKEN")) {
                Pair(false, "That username is already taken.")
            } else {
                Pair(false, "Server Error: $errStr")
            }
        }
    }

    suspend fun updateBusinessProfile(
        storeNameInput: String,
        phoneNumberInput: String,
        storeWebsiteInput: String,
        storeAddressInput: String,
        storeBioInput: String,
        storeLogoUrlInput: String,
        storeLogoBase64Input: String? = null
    ): Boolean {
        if (userId.isEmpty()) return false
        val url = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        val body = JSONObject().apply {
            put("purchaseType", "update_profile")
            put("userId", userId)
            put("storeName", storeNameInput)
            put("phoneNumber", phoneNumberInput)
            put("storeWebsite", storeWebsiteInput)
            put("storeAddress", storeAddressInput)
            put("storeBio", storeBioInput)
            put("storeLogoUrl", storeLogoUrlInput)
            storeLogoBase64Input?.let { put("storeLogoBase64", it) }
        }

        val (code, data) = makePostRequest(url, body)
        return if (code == 200) {
            val json = data?.let { JSONObject(it) }
            val newLogoUrl = json?.optString("logoUrl", "")

            withContext(Dispatchers.Main) {
                storeName = storeNameInput
                phoneNumber = phoneNumberInput
                storeWebsite = storeWebsiteInput
                storeAddress = storeAddressInput
                storeBio = storeBioInput
                storeLogoUrl = newLogoUrl?.takeIf { it.isNotEmpty() } ?: storeLogoUrlInput
            }
            true
        } else {
            println("❌ Backend Rejected Upload ($code): $data")
            false
        }
    }

    // 🚀 NEW: Signature forces an email string to be passed in
    suspend fun initDatabaseRow(emailToSave: String) {
        if (userId.isEmpty()) return
        val url = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        val body = JSONObject().apply {
            put("purchaseType", "init_user")
            put("userId", userId)
            put("userEmail", emailToSave) // 🚀 Forced parameter injection
        }
        makePostRequest(url, body)
    }

    private fun friendlyMessage(error: AuthException): String {
        val fullMessage = "${error.message} ${error.recoverySuggestion}".lowercase()

        return when {
            fullMessage.contains("incorrect username or password") -> "Incorrect email or password. Please try again."
            fullMessage.contains("usernotfound") -> "No account found with that email."
            fullMessage.contains("usernotconfirmed") -> "Please verify your email."
            else -> "Something went wrong. Please try again."
        }
    }

    // MARK: - Native Android Network Helper (No Retrofit Required)
    private suspend fun makePostRequest(urlStr: String, body: JSONObject): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            val responseData = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }
            Pair(responseCode, responseData)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(500, e.localizedMessage)
        }
    }
}