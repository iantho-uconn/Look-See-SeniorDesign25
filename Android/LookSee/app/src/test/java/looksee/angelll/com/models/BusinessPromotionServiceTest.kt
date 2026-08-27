package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BusinessPromotionServiceTest {
    @Test
    fun updateSendsOnlyProvidedPromotionFields(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"ok":true,"item":{"promotionId":"p1","landmarkId":"l1","name":"New","enabled":true}}""",
            ),
        )
        val service = BusinessPromotionService(fixedToken(), http)

        val promotion = service.updatePromotion(
            landmarkId = "l1",
            promotionId = "p1",
            name = "New",
            enabled = true,
        )
        val request = http.requests.single()
        val body = request.body!!.toString(Charsets.UTF_8)

        assertEquals("New", promotion.name)
        assertEquals("PATCH", request.method)
        assertTrue(body.contains("\"name\":\"New\""))
        assertTrue(body.contains("\"enabled\":true"))
        assertFalse(body.contains("description"))
    }

    @Test
    fun updateRejectsAnEmptyPatchBeforeNetworking(): Unit = runBlocking {
        val service = BusinessPromotionService(
            fixedToken(),
            RecordingBusinessHttpClient(),
        )

        try {
            service.updatePromotion("l1", "p1")
            fail("Expected InvalidRequestBody")
        } catch (error: Throwable) {
            assertTrue(error is BusinessPromotionServiceError.InvalidRequestBody)
        }
    }

    @Test
    fun deleteAcceptsAnEmptySuccessBody(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(BusinessHttpResponse(204))
        val service = BusinessPromotionService(fixedToken(), http)

        service.deletePromotion("l1", "p1")

        assertEquals("DELETE", http.requests.single().method)
    }
}
