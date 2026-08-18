package looksee.angelll.com.models

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BusinessLocation(
    val landmarkId: String = "",
    val label: String = "",
    val shortDescription: String? = null,
) {
    val id: String
        get() = landmarkId
}

private data class BusinessLocationListResponse(
    val items: List<BusinessLocation> = emptyList(),
)

class LandmarkService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) {
    constructor() : this(UrlConnectionBusinessHttpClient(), Gson())

    internal constructor(httpClient: BusinessHttpClient) : this(httpClient, Gson())

    private val _landmarks = MutableStateFlow<List<BusinessLocation>>(emptyList())
    val landmarks: StateFlow<List<BusinessLocation>> = _landmarks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun fetchLandmarks(userEmail: String) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val response = execute(
                urlWithQuery(
                    "$LOOKSEE_API_BASE_URL/landmarks/by-user",
                    mapOf("userEmail" to userEmail),
                ),
                BusinessLocationListResponse::class.java,
            )
            _landmarks.value = response.items
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = "Failed to load locations: " +
                (error.message ?: "Unknown error")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchLandmarkById(landmarkId: String): BusinessLocation? = runCatching {
        execute(
            "$LOOKSEE_API_BASE_URL/landmarks/${encodedPathSegment(landmarkId)}",
            BusinessLocation::class.java,
        )
    }.getOrNull()

    suspend fun fetchLandmarkByLabel(label: String): BusinessLocation? = runCatching {
        execute(
            urlWithQuery(
                "$LOOKSEE_API_BASE_URL/landmarks/by-label",
                mapOf("label" to label),
            ),
            BusinessLocationListResponse::class.java,
        ).items.firstOrNull()
    }.getOrNull()

    private suspend fun <T> execute(url: String, responseType: Class<T>): T {
        val response = httpClient.execute(BusinessHttpRequest(method = "GET", url = url))
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Server error ${response.statusCode}: ${response.bodyText}")
        }
        return gson.fromJson(response.bodyText, responseType)
            ?: throw IllegalStateException("The landmark response was empty.")
    }
}
