package looksee.angelll.com.models

import com.google.gson.Gson

sealed class BusinessMediaHistoryServiceError(message: String) : Exception(message) {
    data object NotSignedIn :
        BusinessMediaHistoryServiceError(
            "You must be signed in before viewing media history.",
        )

    data object TokensUnavailable :
        BusinessMediaHistoryServiceError("Cognito tokens were unavailable.")

    class BadStatus(val code: Int, val responseBody: String) :
        BusinessMediaHistoryServiceError("Media history API error $code: $responseBody")

    data object InvalidResponse :
        BusinessMediaHistoryServiceError("The media-history response was invalid.")
}

interface BusinessMediaHistoryDataSource {
    suspend fun fetchHistory(
        landmarkId: String,
        limit: Int = 25,
        nextToken: String? = null,
    ): BusinessMediaHistoryResponse
}

class BusinessMediaHistoryService internal constructor(
    private val tokenProvider: IdTokenProvider,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) : BusinessMediaHistoryDataSource {
    constructor() : this(
        AmplifyCognitoIdTokenProvider(),
        UrlConnectionBusinessHttpClient(),
        Gson(),
    )

    internal constructor(
        tokenProvider: IdTokenProvider,
        httpClient: BusinessHttpClient,
    ) : this(tokenProvider, httpClient, Gson())

    override suspend fun fetchHistory(
        landmarkId: String,
        limit: Int,
        nextToken: String?,
    ): BusinessMediaHistoryResponse {
        val token = try {
            tokenProvider.idToken()
        } catch (_: BusinessAuthenticationError.NotSignedIn) {
            throw BusinessMediaHistoryServiceError.NotSignedIn
        } catch (_: BusinessAuthenticationError.TokensUnavailable) {
            throw BusinessMediaHistoryServiceError.TokensUnavailable
        }
        val parameters = linkedMapOf("limit" to limit.coerceIn(1, 100).toString())
        nextToken?.trim()?.takeIf(String::isNotEmpty)?.let {
            parameters["nextToken"] = it
        }
        val endpoint = "$LOOKSEE_API_BASE_URL/business/landmarks/" +
            "${encodedPathSegment(landmarkId)}/media-history"
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "GET",
                url = urlWithQuery(endpoint, parameters),
                authorization = "Bearer $token",
            ),
        )
        if (response.statusCode !in 200..299) {
            throw BusinessMediaHistoryServiceError.BadStatus(
                response.statusCode,
                response.bodyText,
            )
        }
        return try {
            gson.fromJson(response.bodyText, BusinessMediaHistoryResponse::class.java)
                ?: throw BusinessMediaHistoryServiceError.InvalidResponse
        } catch (error: BusinessMediaHistoryServiceError) {
            throw error
        } catch (_: Exception) {
            throw BusinessMediaHistoryServiceError.InvalidResponse
        }
    }
}
