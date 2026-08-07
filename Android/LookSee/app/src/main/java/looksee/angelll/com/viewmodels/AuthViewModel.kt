package looksee.angelll.com.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.step.AuthSignInStep
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import looksee.angelll.com.services.AuthService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class AuthViewModel : ViewModel() {

    val isSignedIn = MutableStateFlow(false)
    val errorMessage = MutableStateFlow("")
    val userEmail = MutableStateFlow("")
    val userId = MutableStateFlow("")

    val requiresNewPassword = MutableStateFlow(false)

    // TOKEN / SUB / PROFILE TRACKERS
    val tokenBalance = MutableStateFlow(0)
    val activeLandmarksCount = MutableStateFlow(0)
    val hasActiveSubscription = MutableStateFlow(false)
    val stripeSubscriptionId = MutableStateFlow("")

    val activePlanCents = MutableStateFlow(0)
    val activePlanYears = MutableStateFlow(0)

    val storeName = MutableStateFlow("")
    val phoneNumber = MutableStateFlow("")
    val storeBio = MutableStateFlow("")
    val storeLogoUrl = MutableStateFlow("")

    suspend fun checkSession() {
        try {
            val session = Amplify.Auth.fetchAuthSession()
            isSignedIn.value = session.isSignedIn

            if (session.isSignedIn) {
                fetchUserDetails()
                if (isSignedIn.value) {
                    fetchUserUsageStats()
                }
            }
        } catch (e: Exception) {
            isSignedIn.value = false
        }
    }

    fun signIn(username: String, password: String) {
        viewModelScope.launch {
            try {
                val result = AuthService.signIn(username, password)
                if (result.isSignedIn) {
                    isSignedIn.value = true
                    requiresNewPassword.value = false
                    errorMessage.value = ""
                    fetchUserDetails()
                    fetchUserUsageStats()
                } else {
                    when (result.nextStep.signInStep) {
                        AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD -> {
                            requiresNewPassword.value = true
                            errorMessage.value = "Please enter a new permanent password."
                        }
                        AuthSignInStep.CONFIRM_SIGN_UP -> {
                            errorMessage.value = "Account not verified. Please check your email for a confirmation code."
                        }
                        AuthSignInStep.RESET_PASSWORD -> {
                            errorMessage.value = "Password reset required."
                        }
                        else -> {
                            errorMessage.value = "Additional verification required."
                        }
                    }
                    isSignedIn.value = false
                }
            } catch (error: AuthException) {
                errorMessage.value = friendlyMessage(error)
                isSignedIn.value = false
            } catch (error: Exception) {
                errorMessage.value = "Something went wrong. Please try again."
                isSignedIn.value = false
            }
        }
    }

    fun confirmNewPassword(newPassword: String) {
        viewModelScope.launch {
            try {
                val result = Amplify.Auth.confirmSignIn(newPassword)
                if (result.isSignedIn) {
                    isSignedIn.value = true
                    requiresNewPassword.value = false
                    errorMessage.value = ""
                    fetchUserDetails()
                    fetchUserUsageStats()
                } else {
                    errorMessage.value = "Additional steps required to sign in."
                }
            } catch (error: AuthException) {
                errorMessage.value = friendlyMessage(error)
            } catch (error: Exception) {
                errorMessage.value = "Failed to update password. Please try again."
            }
        }
    }

    fun signOut(authState: AuthState) {
        viewModelScope.launch {
            AuthService.signOut()
            authState.signOut()

            // MainActor.run equivalent for ViewModel flows
            isSignedIn.value = false
            requiresNewPassword.value = false
            tokenBalance.value = 0
            activeLandmarksCount.value = 0
            hasActiveSubscription.value = false
            stripeSubscriptionId.value = ""
            activePlanCents.value = 0
            activePlanYears.value = 0
            storeName.value = ""
            phoneNumber.value = ""
            storeBio.value = ""
            storeLogoUrl.value = ""
            userId.value = ""
            userEmail.value = ""
        }
    }

    suspend fun fetchUserDetails() {
        try {
            val user = Amplify.Auth.getCurrentUser()
            userId.value = user.userId

            val attributes = Amplify.Auth.fetchUserAttributes()
            val emailAttr = attributes.find { it.key == AuthUserAttributeKey.email() }
            if (emailAttr != null) {
                userEmail.value = emailAttr.value
            }
        } catch (error: Exception) {
            println("❌ Failed to fetch user details: ${error.message}")
            val errString = error.toString()
            if (errString.contains("UserNotFound") || errString.contains("NotAuthorizedException") || errString.contains("deleted")) {
                Amplify.Auth.signOut()
                isSignedIn.value = false
                userId.value = ""
                userEmail.value = ""
                hasActiveSubscription.value = false
                tokenBalance.value = 0
                activePlanCents.value = 0
                activePlanYears.value = 0
                stripeSubscriptionId.value = ""
            }
        }
    }

    suspend fun fetchUserEmail() {
        fetchUserDetails()
    }

    suspend fun fetchIdToken(): String {
        try {
            val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            val idToken = session?.userPoolTokensResult?.value?.idToken
            return idToken ?: ""
        } catch (error: Exception) {
            println("❌ Failed to fetch session token: ${error.message}")
        }
        return ""
    }

    suspend fun fetchUserUsageStats() {
        if (userId.value.isEmpty()) return
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats"

        withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject()
                body.put("userId", userId.value)

                connection.outputStream.use { os ->
                    val input = body.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)

                    withContext(Dispatchers.Main) {
                        val fetchedBalance = json.optInt("tokenBalance", 0)
                        val fetchedLandmarks = json.optInt("activeLandmarksCount", 0)
                        val fetchedSub = json.optBoolean("hasActiveSubscription", false)
                        val fetchedTier = json.optString("tier", "")
                        val fetchedStripeId = json.optString("stripeSubscriptionId", "")

                        val fetchedPlanCents = json.optInt("activePlanCents", 0)
                        val fetchedPlanYears = json.optInt("activePlanYears", 0)

                        tokenBalance.value = max(tokenBalance.value, fetchedBalance)
                        activeLandmarksCount.value = fetchedLandmarks

                        val isSubscribedOnBackend = fetchedSub || fetchedTier == "business" || fetchedStripeId.isNotEmpty()
                        hasActiveSubscription.value = hasActiveSubscription.value || isSubscribedOnBackend

                        activePlanCents.value = fetchedPlanCents
                        activePlanYears.value = fetchedPlanYears

                        if (fetchedStripeId.isNotEmpty()) stripeSubscriptionId.value = fetchedStripeId

                        val fetchedStore = json.optString("storeName", "")
                        if (fetchedStore.isNotEmpty()) storeName.value = fetchedStore

                        val fetchedPhone = json.optString("phoneNumber", "")
                        if (fetchedPhone.isNotEmpty()) phoneNumber.value = fetchedPhone

                        val fetchedBio = json.optString("storeBio", "")
                        if (fetchedBio.isNotEmpty()) storeBio.value = fetchedBio

                        val fetchedLogo = json.optString("storeLogoUrl", "")
                        if (fetchedLogo.isNotEmpty()) storeLogoUrl.value = fetchedLogo
                    }
                }
            } catch (error: Exception) {
                println("❌ Failed to fetch stats: ${error.message}")
            }
        }
    }

    suspend fun cancelSubscription(context: Context): Boolean {
        if (userId.value.isEmpty()) return false
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject()
                body.put("purchaseType", "cancel_subscription")
                body.put("userId", userId.value)
                body.put("subscriptionId", stripeSubscriptionId.value)

                connection.outputStream.use { os ->
                    val input = body.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == 200) {
                    withContext(Dispatchers.Main) {
                        hasActiveSubscription.value = false
                        stripeSubscriptionId.value = ""
                        activePlanCents.value = 0
                        activePlanYears.value = 0

                        // UserDefaults Equivalent
                        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("isFreeTrial_${userEmail.value}", false).apply()
                    }
                    return@withContext true
                }
            } catch (error: Exception) {
                println("❌ Failed to cancel subscription: ${error.message}")
            }
            return@withContext false
        }
    }

    suspend fun updateBusinessProfile(storeNameArg: String, phoneNumberArg: String, storeBioArg: String, storeLogoUrlArg: String, storeLogoBase64Arg: String? = null): Boolean {
        if (userId.value.isEmpty()) return false
        val urlString = "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/checkout"

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val body = JSONObject()
                body.put("purchaseType", "update_profile")
                body.put("userId", userId.value)
                body.put("storeName", storeNameArg)
                body.put("phoneNumber", phoneNumberArg)
                body.put("storeBio", storeBioArg)
                body.put("storeLogoUrl", storeLogoUrlArg)

                if (storeLogoBase64Arg != null) {
                    body.put("storeLogoBase64", storeLogoBase64Arg)
                }

                connection.outputStream.use { os ->
                    val input = body.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (connection.responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)
                    val newLogoUrl = json.optString("logoUrl", "")

                    withContext(Dispatchers.Main) {
                        storeName.value = storeNameArg
                        phoneNumber.value = phoneNumberArg
                        storeBio.value = storeBioArg
                        storeLogoUrl.value = if (newLogoUrl.isNotEmpty()) newLogoUrl else storeLogoUrlArg
                    }
                    return@withContext true
                } else {
                    val errorString = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    println("❌ Backend Rejected Upload (${connection.responseCode}): $errorString")
                }
            } catch (error: Exception) {
                println("❌ Failed to update business profile: ${error.message}")
            }
            return@withContext false
        }
    }

    private fun friendlyMessage(error: AuthException): String {
        val description = error.message ?: ""
        val cause = error.cause?.toString() ?: ""

        return when {
            description.contains("NotAuthorizedException", ignoreCase = true) || cause.contains("NotAuthorizedException", ignoreCase = true) ->
                "Incorrect email or password. Please try again."
            description.contains("UserNotFound", ignoreCase = true) || cause.contains("UserNotFound", ignoreCase = true) ->
                "No account found with that email."
            description.contains("UserNotConfirmed", ignoreCase = true) || cause.contains("UserNotConfirmed", ignoreCase = true) ->
                "Please verify your email."
            else -> "Something went wrong. Please try again."
        }
    }
}