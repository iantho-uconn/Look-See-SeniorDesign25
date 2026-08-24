package looksee.angelll.com.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.auth.options.AuthFetchSessionOptions
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class UserTier {
    GUEST, AUTHENTICATED, BUSINESS
}

class AuthState : ViewModel() {

    private val _tier = MutableStateFlow(UserTier.GUEST)
    val tier: StateFlow<UserTier> = _tier.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _didSignOut = MutableStateFlow(false)
    val didSignOut: StateFlow<Boolean> = _didSignOut.asStateFlow()

    fun resolveTier() {
        viewModelScope.launch {
            try {
                // Wrapper to convert Java callbacks to Kotlin Coroutines
                val session = suspendCancellableCoroutine<com.amplifyframework.auth.AuthSession> { cont ->
                    Amplify.Auth.fetchAuthSession({ cont.resume(it) }, { cont.resumeWithException(it) })
                }

                if (!session.isSignedIn) {
                    _tier.value = UserTier.GUEST
                    _isReady.value = true
                    return@launch
                }

                // Force refresh tokens
                val options = AuthFetchSessionOptions.builder().forceRefresh(true).build()
                suspendCancellableCoroutine<com.amplifyframework.auth.AuthSession> { cont ->
                    Amplify.Auth.fetchAuthSession(options, { cont.resume(it) }, { cont.resumeWithException(it) })
                }

                val attributes = suspendCancellableCoroutine<List<com.amplifyframework.auth.AuthUserAttribute>> { cont ->
                    Amplify.Auth.fetchUserAttributes({ cont.resume(it) }, { cont.resumeWithException(it) })
                }

                println("🔍 All attributes: $attributes")

                val groupAttr = attributes.firstOrNull { it.key.keyString == "custom:group" }
                if (groupAttr != null) {
                    println("🔍 Found group attribute: ${groupAttr.value}")
                    _tier.value = if (groupAttr.value == "business-users") UserTier.BUSINESS else UserTier.AUTHENTICATED
                } else {
                    println("⚠️ No group attribute found")
                    _tier.value = UserTier.AUTHENTICATED
                }
            } catch (e: Exception) {
                println("❌ resolveTier failed: $e")
                _tier.value = UserTier.AUTHENTICATED
            } finally {
                _isReady.value = true
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                suspendCancellableCoroutine<com.amplifyframework.auth.result.AuthSignOutResult> { cont ->
                    Amplify.Auth.signOut { cont.resume(it) }
                }
            } catch (e: Exception) {
                println("❌ AuthState signOut failed: $e")
            } finally {
                _tier.value = UserTier.GUEST
                _isReady.value = true
                _didSignOut.value = true
            }
        }
    }
}