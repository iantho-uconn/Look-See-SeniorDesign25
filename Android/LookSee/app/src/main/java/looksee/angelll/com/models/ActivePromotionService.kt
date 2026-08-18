package looksee.angelll.com.models

import com.google.gson.Gson

data class ActivePromotionResponse(
    val items: List<ActivePromotion> = emptyList(),
    val count: Int = 0,
    val landmarkId: String? = null,
    val landmarkLabel: String? = null,
    val reason: String? = null,
)

data class ActivePromotion(
    val promotionId: String = "",
    val landmarkId: String = "",
    val landmarkLabel: String = "",
    val name: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val enabled: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    val id: String
        get() = promotionId
}

class ActivePromotionServiceException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("Active promotion API error $statusCode: $responseBody")

class ActivePromotionService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) {
    constructor() : this(UrlConnectionBusinessHttpClient(), Gson())

    internal constructor(httpClient: BusinessHttpClient) : this(httpClient, Gson())

    suspend fun fetchActivePromotions(landmarkId: String): List<ActivePromotion> {
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "GET",
                url = "$LOOKSEE_API_BASE_URL/landmarks/" +
                    "${encodedPathSegment(landmarkId)}/promotions/active",
            ),
        )
        if (response.statusCode !in 200..299) {
            throw ActivePromotionServiceException(response.statusCode, response.bodyText)
        }
        return gson.fromJson(response.bodyText, ActivePromotionResponse::class.java)?.items
            ?: emptyList()
    }

    suspend fun fetchTopActivePromotion(landmarkId: String): ActivePromotion? =
        fetchActivePromotions(landmarkId).firstOrNull()
}
