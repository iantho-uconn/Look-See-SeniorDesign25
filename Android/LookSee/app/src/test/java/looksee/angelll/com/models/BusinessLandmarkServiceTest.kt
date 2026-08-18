package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessLandmarkServiceTest {
    @Test
    fun fetchUsesBearerTokenAndDecodesLandmarks(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"items":[{"landmarkId":"l1","label":"Clock"}],"count":1}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val response = service.fetchBusinessLandmarks()

        assertEquals("Clock", response.items.single().label)
        assertEquals("Bearer id-token", http.requests.single().authorization)
    }

    @Test
    fun descriptionPatchOmitsUnchangedFields(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"ok":true,"item":{"landmarkId":"l1","label":"Clock","shortDescription":"New"}}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val result = service.updateShortDescription("l1", "New")
        val body = http.requests.single().body!!.toString(Charsets.UTF_8)

        assertEquals("New", result.shortDescription)
        assertTrue(body.contains("\"shortDescription\":\"New\""))
        assertFalse(body.contains("websiteUrl"))
        assertFalse(body.contains("isActive"))
    }

    @Test
    fun positiveMediaUsesInitPutAndCompleteFlow(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"submissionId":"s1","uploadUrl":"https://s3.test/u","s3Key":"key","datasetRole":"positive","mediaKind":"photo","landmarkId":"l1"}""",
            ),
            BusinessHttpResponse(200),
            jsonResponse(
                """{"ok":true,"submissionId":"s1","status":"complete","landmarkId":"l1","s3Key":"key"}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val completed = service.uploadBusinessMedia(
            landmarkId = "l1",
            datasetRole = BusinessDatasetRole.POSITIVE,
            mediaKind = BusinessMediaKind.PHOTO,
            filename = "photo.jpg",
            contentType = "image/jpeg",
            data = byteArrayOf(1, 2),
        )

        assertTrue(completed.ok)
        assertEquals(listOf("POST", "PUT", "POST"), http.requests.map { it.method })
        assertEquals(null, http.requests[1].authorization)
        assertEquals("image/jpeg", http.requests[1].contentType)
    }
}
