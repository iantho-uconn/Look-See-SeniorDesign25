package looksee.angelll.com.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.auth.options.AuthFetchSessionOptions
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /**
     * Resolves the current user's tier by checking their auth session and attributes.
     * This ensures the UI is locked/unlocked based on their subscription level.
     */
    fun resolveTier() {
        viewModelScope.launch {
            try {
                // Use Kotlin Facade for suspend support
                val session = Amplify.Auth.fetchAuthSession()

                if (!session.isSignedIn) {
                    _tier.value = UserTier.GUEST
                    return@launch
                }

                // Force refresh to ensure we have the latest group/tier information from Cognito
                val options = AuthFetchSessionOptions.builder().forceRefresh(true).build()
                Amplify.Auth.fetchAuthSession(options)

                // Fetch user attributes to check for the "custom:group" attribute
                val attributes = Amplify.Auth.fetchUserAttributes()
                val groupAttr = attributes.find { it.key.keyString == "custom:group" }

                _tier.value = if (groupAttr?.value == "business-users") {
                    UserTier.BUSINESS
                } else {
                    UserTier.AUTHENTICATED
                }
            } catch (e: Exception) {
                println("❌ resolveTier failed: $e")
                // Fallback to AUTHENTICATED if they are signed in but attributes fail
                _tier.value = UserTier.AUTHENTICATED
            } finally {
                _isReady.value = true
            }
        }
    }

    /**
     * Signs the user out and resets the local auth state.
     */
    fun signOut() {
        viewModelScope.launch {
            try {
                Amplify.Auth.signOut()
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
