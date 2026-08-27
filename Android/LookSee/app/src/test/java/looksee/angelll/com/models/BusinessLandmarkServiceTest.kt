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
    fun fetchDecodesMediaRequirementsAndActionNeededStatus(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"items":[{"landmarkId":"l1","label":"Clock","status":"NEEDS_MORE_MEDIA","cleanFrameCount":12,"requiredFrames":30,"secondsNeeded":18}],"count":1}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val landmark = service.fetchBusinessLandmarks().items.single()

        assertEquals("Action Needed", landmark.displayStatus)
        assertEquals(12, landmark.cleanFrameCount)
        assertEquals(30, landmark.requiredFrames)
        assertEquals(18, landmark.secondsNeeded)
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

    @Test
    fun hardNegativeUploadUsesBackendProcessedStatus(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"batchId":"batch-1","landmarkId":"l1","uploads":[{"negativeId":"negative-1","uploadUrl":"https://s3.test/negative","sourceKey":"source/key.mov","contentType":"video/quicktime"}]}""",
            ),
            BusinessHttpResponse(200),
            jsonResponse(
                """{"landmarkId":"l1","batchId":"batch-1","processedCount":1,"failedCount":0,"processed":[{"negativeId":"negative-1","status":"READY"}]}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val completed = service.uploadBusinessMedia(
            landmarkId = "l1",
            datasetRole = BusinessDatasetRole.HARD_NEGATIVE,
            mediaKind = BusinessMediaKind.VIDEO,
            filename = "negative.mov",
            contentType = "video/quicktime",
            data = byteArrayOf(4, 5),
        )

        assertTrue(completed.ok)
        assertEquals("READY", completed.status)
        assertEquals("negative-1", completed.submissionId)
        assertEquals(listOf("POST", "PUT", "POST"), http.requests.map { it.method })
    }

    @Test
    fun retryHardNegativeProcessingForcesBackendRetryWithoutUploading(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"landmarkId":"l1","batchId":"batch-1","processedCount":1,"failedCount":0,"processed":[{"negativeId":"negative-1","status":"PROCESSING"}]}""",
            ),
        )
        val service = BusinessLandmarkService(fixedToken(), http)

        val response = service.retryHardNegativeProcessing(
            landmarkId = "l1",
            batchId = "batch-1",
            negativeId = "negative-1",
        )
        val request = http.requests.single()
        val body = request.body!!.toString(Charsets.UTF_8)

        assertEquals(1, response.processedCount)
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/landmarks/l1/hard-negatives/complete"))
        assertTrue(body.contains("\"batchId\":\"batch-1\""))
        assertTrue(body.contains("\"negativeIds\":[\"negative-1\"]"))
        assertTrue(body.contains("\"forceRetry\":true"))
    }
}
