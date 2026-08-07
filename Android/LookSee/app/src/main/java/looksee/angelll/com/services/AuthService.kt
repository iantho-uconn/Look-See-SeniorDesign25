package looksee.angelll.com.services

import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.auth.options.AuthSignUpOptions
import com.amplifyframework.auth.result.AuthSignInResult
import com.amplifyframework.auth.result.AuthSignUpResult
import com.amplifyframework.kotlin.core.Amplify

object AuthService {

    // SIGN UP
    suspend fun signUp(username: String, password: String, email: String, group: String): AuthSignUpResult {
        val options = AuthSignUpOptions.builder()
            .userAttribute(AuthUserAttributeKey.email(), email)
            .userAttribute(AuthUserAttributeKey.custom("custom:group"), group) // store desired group
            .build()

        return Amplify.Auth.signUp(
            username = email,
            password = password,
            options = options
        )
    }

    // SIGN IN
    suspend fun signIn(username: String, password: String): AuthSignInResult {
        return Amplify.Auth.signIn(username, password)
    }

    // CONFIRM CODE
    suspend fun confirm(username: String, code: String): AuthSignUpResult {
        return Amplify.Auth.confirmSignUp(username, code)
    }

    // SIGN OUT
    suspend fun signOut() {
        Amplify.Auth.signOut()
    }
}