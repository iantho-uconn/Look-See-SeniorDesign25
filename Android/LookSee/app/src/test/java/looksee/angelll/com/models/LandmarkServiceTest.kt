package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkServiceTest {
    @Test
    fun fetchByUserUpdatesObservableStateAndEncodesEmail(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"items":[{"landmarkId":"l1","label":"Clock"}]}""",
            ),
        )
        val service = LandmarkService(http)

        service.fetchLandmarks("person+test@example.com")

        assertEquals("Clock", service.landmarks.value.single().label)
        assertTrue(http.requests.single().url.contains("person%2Btest%40example.com"))
        assertNull(service.errorMessage.value)
    }

    @Test
    fun fetchByIdReturnsNullForAnErrorResponse(): Unit = runBlocking {
        val service = LandmarkService(
            RecordingBusinessHttpClient(jsonResponse("not found", statusCode = 404)),
        )

        assertNull(service.fetchLandmarkById("missing"))
    }
}
