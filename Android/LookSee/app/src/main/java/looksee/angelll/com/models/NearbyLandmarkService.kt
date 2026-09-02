package looksee.angelll.com.models

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NearbyLandmarkServiceException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("Nearby landmarks API error $statusCode: $responseBody")

class NearbyLandmarkService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) {
    constructor() : this(UrlConnectionBusinessHttpClient(), Gson())

    internal constructor(httpClient: BusinessHttpClient) : this(httpClient, Gson())

    private val _items = MutableStateFlow<List<NearbyLandmark>>(emptyList())
    val items: StateFlow<List<NearbyLandmark>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun fetchNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = DEFAULT_RADIUS_METERS,
        limit: Int = DEFAULT_LIMIT,
    ) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val requestBody = NearbyLandmarksRequest(
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                limit = limit,
            )
            val response = httpClient.execute(
                BusinessHttpRequest(
                    method = "POST",
                    url = "$LOOKSEE_API_BASE_URL/landmarks/map",
                    body = gson.toJson(requestBody).toByteArray(Charsets.UTF_8),
                    contentType = "application/json",
                ),
            )
            if (response.statusCode !in 200..299) {
                throw NearbyLandmarkServiceException(
                    response.statusCode,
                    response.bodyText,
                )
            }
            val decoded = gson.fromJson(
                response.bodyText,
                NearbyLandmarksResponse::class.java,
            ) ?: throw IllegalStateException("The nearby-landmarks response was empty.")
            _items.value = decoded.items
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _items.value = emptyList()
            _errorMessage.value = error.message ?: "Unable to load nearby landmarks."
        } finally {
            _isLoading.value = false
        }
    }

    private companion object {
        const val DEFAULT_RADIUS_METERS = 100.0
        const val DEFAULT_LIMIT = 100
    }
}
