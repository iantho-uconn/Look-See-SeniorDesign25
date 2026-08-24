package looksee.angelll.com.services

import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.options.AuthSignUpOptions
import com.amplifyframework.auth.result.AuthSignInResult
import com.amplifyframework.auth.result.AuthSignOutResult
import com.amplifyframework.auth.result.AuthSignUpResult
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AuthService {

    // Singleton instance matching "static let shared"
    val shared = this

    // SIGN UP
    suspend fun signUp(usernameInput: String, passwordInput: String, emailInput: String, groupInput: String): AuthSignUpResult {
        return suspendCancellableCoroutine { cont ->
            val options = AuthSignUpOptions.builder()
                .userAttribute(AuthUserAttributeKey.email(), emailInput)
                .userAttribute(AuthUserAttributeKey.custom("group"), groupInput) // store desired group
                .build()

            Amplify.Auth.signUp(emailInput, passwordInput, options,
                { cont.resume(it) },
                { cont.resumeWithException(it) }
            )
        }
    }

    // SIGN IN
    suspend fun signIn(usernameInput: String, passwordInput: String): AuthSignInResult {
        return suspendCancellableCoroutine { cont ->
            Amplify.Auth.signIn(usernameInput, passwordInput,
                { cont.resume(it) },
                { cont.resumeWithException(it) }
            )
        }
    }

    // CONFIRM CODE
    suspend fun confirm(usernameInput: String, codeInput: String): AuthSignUpResult {
        return suspendCancellableCoroutine { cont ->
            Amplify.Auth.confirmSignUp(usernameInput, codeInput,
                { cont.resume(it) },
                { cont.resumeWithException(it) }
            )
        }
    }

    // SIGN OUT
    suspend fun signOut() {
        try {
            suspendCancellableCoroutine<AuthSignOutResult> { cont ->
                Amplify.Auth.signOut { cont.resume(it) }
            }
        } catch (_: Exception) {
            // Silently fail if sign out errors (e.g. user already signed out)
        }
    }

    // FETCH ID TOKEN
    suspend fun fetchIdToken(): String {
        return suspendCancellableCoroutine { cont ->
            Amplify.Auth.fetchAuthSession(
                { session ->
                    val cognitoSession = session as? AWSCognitoAuthSession
                    val idToken = cognitoSession?.userPoolTokensResult?.value?.idToken ?: ""
                    cont.resume(idToken)
                },
                { cont.resumeWithException(it) }
            )
        }
    }

    // FETCH VERIFIED EMAIL
    suspend fun fetchVerifiedEmail(): String? {
        return suspendCancellableCoroutine { cont ->
            Amplify.Auth.fetchUserAttributes(
                { attributes ->
                    val email = attributes.firstOrNull { it.key.keyString == "email" }?.value
                    cont.resume(email)
                },
                { cont.resumeWithException(it) }
            )
        }
    }
}