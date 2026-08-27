package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLandmarkServiceTest {
    @Test
    fun postsMapRequestAndDecodesNewestFilterFields(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{
                  "items":[{
                    "landmarkId":"landmark-1",
                    "label":"Clock Tower",
                    "shortDescription":"Historic tower",
                    "latitude":41.1,
                    "longitude":-72.1,
                    "distanceMeters":14.5,
                    "createdBy":"owner@example.com",
                    "createdAt":"2026-08-01",
                    "promotionEnabled":true,
                    "promotion":"Summer",
                    "clusterId":"7"
                  }],
                  "count":1,
                  "radiusMeters":125.0
                }""".trimIndent(),
            ),
        )
        val service = NearbyLandmarkService(http)

        service.fetchNearby(41.1, -72.1, radiusMeters = 125.0, limit = 40)

        val request = http.requests.single()
        val body = request.body!!.toString(Charsets.UTF_8)
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/landmarks/map"))
        assertTrue(body.contains("\"radiusMeters\":125.0"))
        assertTrue(body.contains("\"limit\":40"))
        assertEquals("7", service.items.value.single().clusterId)
        assertTrue(service.items.value.single().promotionEnabled)
        assertFalse(service.isLoading.value)
    }

    @Test
    fun failedRefreshClearsStaleNearbyItemsAndPublishesError(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"items":[{"landmarkId":"old","label":"Old","shortDescription":"","latitude":0.0,"longitude":0.0,"distanceMeters":1.0,"promotionEnabled":false}],"count":1,"radiusMeters":100.0}""",
            ),
            jsonResponse("backend unavailable", statusCode = 503),
        )
        val service = NearbyLandmarkService(http)
        service.fetchNearby(0.0, 0.0)

        service.fetchNearby(0.0, 0.0)

        assertTrue(service.items.value.isEmpty())
        assertTrue(service.errorMessage.value.orEmpty().contains("503"))
        assertFalse(service.isLoading.value)
    }
}
