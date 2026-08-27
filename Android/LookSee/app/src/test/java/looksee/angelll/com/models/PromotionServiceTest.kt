package looksee.angelll.com.models

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionServiceTest {
    @Test
    fun fetchesUserPromotionsWithEncodedQueryAndPublishesState(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(promotionList("promotion-1", "Original"))
        val service = PromotionService(http)

        service.fetchPromotions("owner+test@example.com")

        assertTrue(http.requests.single().url.contains("userEmail=owner%2Btest%40example.com"))
        assertEquals("promotion-1", service.promotions.value.single().id)
        assertEquals("Original", service.promotions.value.single().name)
        assertNullOrEmpty(service.errorMessage.value)
        assertFalse(service.isLoading.value)
    }

    @Test
    fun landmarkAndLabelLookupsUseTheirDistinctPublicRoutes(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            promotionList("p1", "Landmark"),
            promotionList("p2", "Label"),
        )
        val service = PromotionService(http)

        assertEquals("p1", service.fetchPromotionsForLandmark("landmark/1").single().id)
        assertEquals("p2", service.fetchPromotionsByLabel("Clock Tower").single().id)

        assertTrue(http.requests[0].url.contains("/promotions/by-landmark"))
        assertTrue(http.requests[0].url.contains("landmarkId=landmark%2F1"))
        assertTrue(http.requests[1].url.contains("/promotions/by-label"))
        assertTrue(http.requests[1].url.contains("landmarkLabel=Clock%20Tower"))
    }

    @Test
    fun createFormatsDatesAndAppendsReturnedPromotion(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(promotion("created", "Created"))
        val service = PromotionService(http)

        service.createPromotion(
            userEmail = "owner@example.com",
            landmarkId = "landmark-1",
            landmarkLabel = "Clock Tower",
            name = "Created",
            description = "Description",
            startDate = LocalDate.of(2026, 8, 3),
            endDate = LocalDate.of(2026, 9, 4),
        )

        val request = http.requests.single()
        val body = request.body!!.toString(Charsets.UTF_8)
        assertEquals("POST", request.method)
        assertTrue(body.contains("\"startDate\":\"2026-08-03\""))
        assertTrue(body.contains("\"endDate\":\"2026-09-04\""))
        assertEquals("created", service.promotions.value.single().id)
    }

    @Test
    fun updateReplacesMatchingPromotionAndEncodesItsId(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            promotionList("promotion/1", "Old"),
            promotion("promotion/1", "Updated"),
        )
        val service = PromotionService(http)
        service.fetchPromotions("owner@example.com")

        service.updatePromotion(
            promotionId = "promotion/1",
            userEmail = "owner@example.com",
            landmarkId = "landmark-1",
            landmarkLabel = "Clock Tower",
            name = "Updated",
            description = "New",
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 8, 31),
            enabled = true,
        )

        assertEquals("PATCH", http.requests[1].method)
        assertTrue(http.requests[1].url.endsWith("/promotions/promotion%2F1"))
        assertEquals("Updated", service.promotions.value.single().name)
    }

    @Test
    fun deleteRemovesMatchingPromotionAndIncludesOwnerEmail(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            promotionList("promotion-1", "Old"),
            BusinessHttpResponse(204),
        )
        val service = PromotionService(http)
        service.fetchPromotions("owner@example.com")

        service.deletePromotion("promotion-1", "owner@example.com")

        assertEquals("DELETE", http.requests[1].method)
        assertTrue(http.requests[1].url.contains("userEmail=owner%40example.com"))
        assertTrue(service.promotions.value.isEmpty())
    }

    private fun promotionList(id: String, name: String) = jsonResponse(
        """{"items":[${promotionJson(id, name)}]}""",
    )

    private fun promotion(id: String, name: String) = jsonResponse(promotionJson(id, name))

    private fun promotionJson(id: String, name: String) =
        """{"promotionId":"$id","userEmail":"owner@example.com","landmarkId":"landmark-1","landmarkLabel":"Clock Tower","name":"$name","description":"Description","startDate":"2026-08-01","endDate":"2026-08-31","enabled":true,"createdAt":"2026-08-01T00:00:00Z"}"""

    private fun assertNullOrEmpty(value: String?) {
        assertTrue(value.isNullOrEmpty())
    }
}
