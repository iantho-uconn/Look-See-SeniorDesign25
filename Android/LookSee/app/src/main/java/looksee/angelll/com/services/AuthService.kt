package looksee.angelll.com.services

import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.options.AuthSignUpOptions
import com.amplifyframework.auth.result.AuthSignInResult
import com.amplifyframework.auth.result.AuthSignUpResult
import com.amplifyframework.kotlin.core.Amplify

object AuthService {

    // SIGN UP
    suspend fun signUp(usernameInput: String, passwordInput: String, emailInput: String, groupInput: String): AuthSignUpResult {
        val options = AuthSignUpOptions.builder()
            .userAttribute(AuthUserAttributeKey.email(), emailInput)
            .userAttribute(AuthUserAttributeKey.custom("group"), groupInput) // store desired group
            .build()

        return Amplify.Auth.signUp(
            username = usernameInput, // 🚀 FIX: Now uses the username parameter!
            password = passwordInput,
            options = options
        )
    }

    // SIGN IN
    suspend fun signIn(usernameInput: String, passwordInput: String): AuthSignInResult {
        return Amplify.Auth.signIn(
            username = usernameInput,
            password = passwordInput
        )
    }

    // CONFIRM CODE
    suspend fun confirm(usernameInput: String, code: String): AuthSignUpResult {
        return Amplify.Auth.confirmSignUp(
            username = usernameInput,
            confirmationCode = code
        )
    }

    // SIGN OUT
    suspend fun signOut() {
        try {
            Amplify.Auth.signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // FETCH ID TOKEN
    suspend fun fetchIdToken(): String {
        val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            ?: throw Exception("Could not read Cognito session. Session did not provide Cognito tokens.")

        return session.userPoolTokensResult.value?.idToken
            ?: throw Exception("Could not retrieve ID token from session.")
    }

    // FETCH VERIFIED EMAIL
    suspend fun fetchVerifiedEmail(): String? {
        return try {
            val attributes = Amplify.Auth.fetchUserAttributes()
            attributes.find { it.key.keyString == "email" }?.value
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}