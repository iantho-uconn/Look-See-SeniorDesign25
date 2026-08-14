package looksee.angelll.com.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class UserTier {
    GUEST, AUTHENTICATED, BUSINESS
}

class AuthState : ViewModel() {
    val tier = MutableStateFlow(UserTier.GUEST)
    val isReady = MutableStateFlow(false)
    val didSignOut = MutableStateFlow(false)

    fun resolveTier() {
        // Launch a coroutine to handle async background networking (Replaces Task { @MainActor })
        viewModelScope.launch {
            try {
                val session = Amplify.Auth.fetchAuthSession()

                if (!session.isSignedIn) {
                    tier.value = UserTier.GUEST
                    isReady.value = true
                    return@launch
                }

                // Force fetching attributes (AWS SDK Kotlin handles caching logic inherently)
                val attributes = Amplify.Auth.fetchUserAttributes()
                println("🔍 All attributes: $attributes")

                val groupAttr = attributes.find { it.key.keyString == "custom:group" }

                if (groupAttr != null) {
                    println("🔍 Found group attribute: ${groupAttr.value}")
                    tier.value = if (groupAttr.value == "business-users") {
                        UserTier.BUSINESS
                    } else {
                        UserTier.AUTHENTICATED
                    }
                } else {
                    println("⚠️ No group attribute found")
                    tier.value = UserTier.AUTHENTICATED
                }

            } catch (e: Exception) {
                println("❌ resolveTier failed: ${e.message}")
                tier.value = UserTier.AUTHENTICATED
            } finally {
                isReady.value = true
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                Amplify.Auth.signOut()
                tier.value = UserTier.GUEST
                isReady.value = true
                didSignOut.value = true
            } catch (e: Exception) {
                println("❌ Sign out failed: ${e.message}")
            }
        }
    }
}