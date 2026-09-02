package looksee.angelll.com.models

import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LOOKSEE_API_BASE_URL =
    "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"

sealed class BusinessAuthenticationError(message: String) : Exception(message) {
    data object NotSignedIn :
        BusinessAuthenticationError("You must be signed in to use this feature.")

    data object TokensUnavailable :
        BusinessAuthenticationError("Cognito tokens were unavailable.")
}

fun interface IdTokenProvider {
    suspend fun idToken(): String
}

class AmplifyCognitoIdTokenProvider : IdTokenProvider {
    override suspend fun idToken(): String {
        val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            ?: throw BusinessAuthenticationError.TokensUnavailable
        if (!session.isSignedIn) throw BusinessAuthenticationError.NotSignedIn
        return session.userPoolTokensResult.value?.idToken
            ?.takeIf(String::isNotBlank)
            ?: throw BusinessAuthenticationError.TokensUnavailable
    }
}

internal data class BusinessHttpRequest(
    val method: String,
    val url: String,
    val authorization: String? = null,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val accept: String? = "application/json",
    val timeoutMillis: Int = 30_000,
)

internal data class BusinessHttpResponse(
    val statusCode: Int,
    val body: ByteArray = ByteArray(0),
) {
    val bodyText: String
        get() = body.toString(Charsets.UTF_8)
}

internal fun interface BusinessHttpClient {
    suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse
}

internal class UrlConnectionBusinessHttpClient : BusinessHttpClient {
    override suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as? HttpURLConnection)
                ?: error("The URL did not create an HTTP connection.")
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.instanceFollowRedirects = true
                request.accept?.let { connection.setRequestProperty("Accept", it) }
                request.authorization?.let {
                    connection.setRequestProperty("Authorization", it)
                }
                request.contentType?.let {
                    connection.setRequestProperty("Content-Type", it)
                }
                request.body?.let { bytes ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                BusinessHttpResponse(
                    statusCode = status,
                    body = stream?.use { it.readBytes() } ?: ByteArray(0),
                )
            } finally {
                connection.disconnect()
            }
        }
}

internal fun encodedPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun urlWithQuery(baseUrl: String, parameters: Map<String, String>): String {
    val query = parameters.entries.joinToString("&") { (name, value) ->
        "${encodedQueryComponent(name)}=${encodedQueryComponent(value)}"
    }
    return URI.create("$baseUrl?$query").toASCIIString()
}

private fun encodedQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
