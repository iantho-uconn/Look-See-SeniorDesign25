package looksee.angelll.com.models

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PromotionPayload(
    @SerializedName("promotionId")
    val id: String = "",
    val userEmail: String = "",
    val landmarkId: String = "",
    val landmarkLabel: String = "",
    val name: String = "",
    val description: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val enabled: Boolean = false,
    val createdAt: String? = null,
)

private data class PromotionListResponse(
    val items: List<PromotionPayload> = emptyList(),
)

private data class PromotionMutationRequest(
    val userEmail: String,
    val landmarkId: String,
    val landmarkLabel: String,
    val name: String,
    val description: String,
    val startDate: String,
    val endDate: String,
    val enabled: Boolean,
)

class PromotionServiceException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("Promotion API error $statusCode: $responseBody")

/** Legacy/public promotion API retained separately from BusinessPromotionService. */
class PromotionService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) {
    constructor() : this(UrlConnectionBusinessHttpClient(), Gson())

    internal constructor(httpClient: BusinessHttpClient) : this(httpClient, Gson())

    private val _promotions = MutableStateFlow<List<PromotionPayload>>(emptyList())
    val promotions: StateFlow<List<PromotionPayload>> = _promotions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun fetchPromotions(userEmail: String) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            _promotions.value = fetchList(
                urlWithQuery(
                    "$LOOKSEE_API_BASE_URL/promotions",
                    mapOf("userEmail" to userEmail),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = "Failed to load promotions: " +
                (error.message ?: "Unknown error")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchPromotionsForLandmark(landmarkId: String): List<PromotionPayload> =
        fetchListOrEmpty(
            urlWithQuery(
                "$LOOKSEE_API_BASE_URL/promotions/by-landmark",
                mapOf("landmarkId" to landmarkId),
            ),
        )

    suspend fun fetchPromotionsByLabel(label: String): List<PromotionPayload> =
        fetchListOrEmpty(
            urlWithQuery(
                "$LOOKSEE_API_BASE_URL/promotions/by-label",
                mapOf("landmarkLabel" to label),
            ),
        )

    suspend fun createPromotion(
        userEmail: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        startDate: LocalDate,
        endDate: LocalDate,
        enabled: Boolean = true,
    ) {
        _errorMessage.value = null
        try {
            val created = mutate(
                method = "POST",
                url = "$LOOKSEE_API_BASE_URL/promotions",
                body = mutationBody(
                    userEmail,
                    landmarkId,
                    landmarkLabel,
                    name,
                    description,
                    startDate,
                    endDate,
                    enabled,
                ),
            )
            _promotions.value = _promotions.value + created
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = "Failed to create promotion: " +
                (error.message ?: "Unknown error")
        }
    }

    suspend fun updatePromotion(
        promotionId: String,
        userEmail: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        startDate: LocalDate,
        endDate: LocalDate,
        enabled: Boolean,
    ) {
        _errorMessage.value = null
        try {
            val updated = mutate(
                method = "PATCH",
                url = "$LOOKSEE_API_BASE_URL/promotions/${encodedPathSegment(promotionId)}",
                body = mutationBody(
                    userEmail,
                    landmarkId,
                    landmarkLabel,
                    name,
                    description,
                    startDate,
                    endDate,
                    enabled,
                ),
            )
            _promotions.value = _promotions.value.map { current ->
                if (current.id == promotionId) updated else current
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = "Failed to update promotion: " +
                (error.message ?: "Unknown error")
        }
    }

    suspend fun deletePromotion(promotionId: String, userEmail: String) {
        _errorMessage.value = null
        try {
            execute(
                BusinessHttpRequest(
                    method = "DELETE",
                    url = urlWithQuery(
                        "$LOOKSEE_API_BASE_URL/promotions/" +
                            encodedPathSegment(promotionId),
                        mapOf("userEmail" to userEmail),
                    ),
                ),
            )
            _promotions.value = _promotions.value.filterNot { it.id == promotionId }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = "Failed to delete promotion: " +
                (error.message ?: "Unknown error")
        }
    }

    private suspend fun fetchList(url: String): List<PromotionPayload> {
        val response = execute(BusinessHttpRequest(method = "GET", url = url))
        return gson.fromJson(response.bodyText, PromotionListResponse::class.java)?.items
            ?: emptyList()
    }

    private suspend fun fetchListOrEmpty(url: String): List<PromotionPayload> = try {
        fetchList(url)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun mutate(
        method: String,
        url: String,
        body: PromotionMutationRequest,
    ): PromotionPayload {
        val response = execute(
            BusinessHttpRequest(
                method = method,
                url = url,
                body = gson.toJson(body).toByteArray(Charsets.UTF_8),
                contentType = "application/json",
            ),
        )
        return gson.fromJson(response.bodyText, PromotionPayload::class.java)
            ?: throw IllegalStateException("The promotion response was empty.")
    }

    private suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse {
        val response = httpClient.execute(request)
        if (response.statusCode !in 200..299) {
            throw PromotionServiceException(response.statusCode, response.bodyText)
        }
        return response
    }

    private fun mutationBody(
        userEmail: String,
        landmarkId: String,
        landmarkLabel: String,
        name: String,
        description: String,
        startDate: LocalDate,
        endDate: LocalDate,
        enabled: Boolean,
    ) = PromotionMutationRequest(
        userEmail = userEmail,
        landmarkId = landmarkId,
        landmarkLabel = landmarkLabel,
        name = name,
        description = description,
        startDate = DATE_FORMATTER.format(startDate),
        endDate = DATE_FORMATTER.format(endDate),
        enabled = enabled,
    )

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
