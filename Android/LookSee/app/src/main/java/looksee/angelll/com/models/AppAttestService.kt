package looksee.angelll.com.models

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import android.util.Base64

object AppAttestService {
    private var isBypassed = false

    suspend fun generateAssertionHeader(context: Context, payload: ByteArray): String? {
        if (isBypassed) {
            Log.w("LookSee_Security", "Play Integrity is bypassed due to previous failure.")
            return null
        }

        return try {
            // Hash the payload
            val messageDigest = MessageDigest.getInstance("SHA-256")
            val hashBytes = messageDigest.digest(payload)
            val nonce = Base64.encodeToString(hashBytes, Base64.NO_WRAP or Base64.URL_SAFE)

            // Request integrity token
            val integrityManager = IntegrityManagerFactory.create(context.applicationContext)
            val tokenRequest = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            val response = integrityManager.requestIntegrityToken(tokenRequest).await()
            response.token()
        } catch (e: Exception) {
            Log.e("LookSee_Security", "Play Integrity token generation failed", e)
            isBypassed = true // Fail fast on first error to prevent lagging out network requests
            null
        }
    }
}