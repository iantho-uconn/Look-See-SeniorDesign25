package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePromotionServiceTest {
    @Test
    fun fetchesActivePromotionsAndEncodesLandmarkId(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"items":[{"promotionId":"p1","landmarkId":"l1","landmarkLabel":"Tower","name":"Ten off","description":"Today","enabled":true}],"count":1}""",
            ),
        )
        val service = ActivePromotionService(http)

        val items = service.fetchActivePromotions("landmark/with space")

        assertEquals("p1", items.single().promotionId)
        assertTrue(http.requests.single().url.contains("landmark%2Fwith%20space"))
    }

    @Test
    fun topPromotionIsNullForAnEmptyResponse(): Unit = runBlocking {
        val service = ActivePromotionService(
            RecordingBusinessHttpClient(jsonResponse("""{"items":[],"count":0}""")),
        )

        assertNull(service.fetchTopActivePromotion("l1"))
    }
}
