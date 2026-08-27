package looksee.angelll.com.models

import com.google.gson.Gson

sealed class BusinessPromotionServiceError(message: String) : Exception(message) {
    data object NotSignedIn :
        BusinessPromotionServiceError("You must be signed in before managing promotions.")

    data object TokensUnavailable :
        BusinessPromotionServiceError("Cognito tokens were unavailable.")

    class BadStatus(val code: Int, val responseBody: String) :
        BusinessPromotionServiceError("API error $code: $responseBody")

    data object InvalidRequestBody :
        BusinessPromotionServiceError("Could not build the promotion request.")

    data object InvalidResponse :
        BusinessPromotionServiceError("The promotion response was invalid.")
}

class BusinessPromotionService internal constructor(
    private val tokenProvider: IdTokenProvider,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) {
    constructor() : this(
        AmplifyCognitoIdTokenProvider(),
        UrlConnectionBusinessHttpClient(),
        Gson(),
    )

    internal constructor(
        tokenProvider: IdTokenProvider,
        httpClient: BusinessHttpClient,
    ) : this(tokenProvider, httpClient, Gson())

    suspend fun fetchPromotions(landmarkId: String): BusinessPromotionListResponse =
        request(
            method = "GET",
            url = promotionsUrl(landmarkId),
            responseType = BusinessPromotionListResponse::class.java,
        )

    suspend fun createPromotion(
        landmarkId: String,
        name: String,
        description: String,
        imageUrl: String,
        startDate: String,
        endDate: String,
        enabled: Boolean,
    ): BusinessPromotion = request(
        method = "POST",
        url = promotionsUrl(landmarkId),
        body = mapOf(
            "name" to name,
            "description" to description,
            "imageUrl" to imageUrl,
            "startDate" to startDate,
            "endDate" to endDate,
            "enabled" to enabled,
        ),
        responseType = BusinessPromotionMutationResponse::class.java,
    ).item

    suspend fun updatePromotion(
        landmarkId: String,
        promotionId: String,
        name: String? = null,
        description: String? = null,
        imageUrl: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        enabled: Boolean? = null,
    ): BusinessPromotion {
        val body = linkedMapOf<String, Any>()
        name?.let { body["name"] = it }
        description?.let { body["description"] = it }
        imageUrl?.let { body["imageUrl"] = it }
        startDate?.let { body["startDate"] = it }
        endDate?.let { body["endDate"] = it }
        enabled?.let { body["enabled"] = it }
        if (body.isEmpty()) throw BusinessPromotionServiceError.InvalidRequestBody
        return request(
            method = "PATCH",
            url = promotionUrl(landmarkId, promotionId),
            body = body,
            responseType = BusinessPromotionMutationResponse::class.java,
        ).item
    }

    suspend fun deletePromotion(
        landmarkId: String,
        promotionId: String,
    ) {
        requestRaw(
            method = "DELETE",
            url = promotionUrl(landmarkId, promotionId),
        )
    }

    private suspend fun <T> request(
        method: String,
        url: String,
        body: Any? = null,
        responseType: Class<T>,
    ): T {
        val response = requestRaw(method, url, body)
        return try {
            gson.fromJson(response.bodyText, responseType)
                ?: throw BusinessPromotionServiceError.InvalidResponse
        } catch (error: BusinessPromotionServiceError) {
            throw error
        } catch (_: Exception) {
            throw BusinessPromotionServiceError.InvalidResponse
        }
    }

    private suspend fun requestRaw(
        method: String,
        url: String,
        body: Any? = null,
    ): BusinessHttpResponse {
        val token = try {
            tokenProvider.idToken()
        } catch (_: BusinessAuthenticationError.NotSignedIn) {
            throw BusinessPromotionServiceError.NotSignedIn
        } catch (_: BusinessAuthenticationError.TokensUnavailable) {
            throw BusinessPromotionServiceError.TokensUnavailable
        }
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = method,
                url = url,
                authorization = "Bearer $token",
                body = body?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) },
                contentType = body?.let { "application/json" },
            ),
        )
        if (response.statusCode !in 200..299) {
            throw BusinessPromotionServiceError.BadStatus(
                response.statusCode,
                response.bodyText,
            )
        }
        return response
    }

    private fun promotionsUrl(landmarkId: String): String =
        "$LOOKSEE_API_BASE_URL/business/landmarks/" +
            "${encodedPathSegment(landmarkId)}/promotions"

    private fun promotionUrl(landmarkId: String, promotionId: String): String =
        "${promotionsUrl(landmarkId)}/${encodedPathSegment(promotionId)}"
}
