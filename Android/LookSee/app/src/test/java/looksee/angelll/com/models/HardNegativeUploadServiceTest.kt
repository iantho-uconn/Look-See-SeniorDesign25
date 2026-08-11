package looksee.angelll.com.models

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HardNegativeUploadServiceTest {
    @Test
    fun uploadInitializesPutsAndCompletesOneNegativeVideo(): Unit = runBlocking {
        val videoFile = File.createTempFile("negative", ".mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val http = RecordingUploadHttpClient(
            UploadHttpResponse(200, initResponseJson(uploadCount = 1)),
            UploadHttpResponse(200),
            UploadHttpResponse(200, completeResponseJson(processed = 1, failed = 0)),
        )
        val service = HardNegativeUploadService(http)

        val response = service.upload(
            landmarkId = "landmark/with space",
            idToken = "id-token",
            video = CapturedNegativeVideo(videoFile),
        )

        assertEquals(1, response.processedCount)
        assertEquals(1.0, service.progress.value, 0.0)
        assertEquals("Negative video uploaded ✅", service.status.value)
        assertFalse(service.isUploading.value)

        assertTrue(http.calls[0].url.contains("landmark%2Fwith%20space"))
        assertEquals("id-token", http.calls[0].authorization)
        assertTrue(http.calls[0].body.contains("\"contentType\":\"video/mp4\""))
        assertEquals("video/mp4", http.calls[1].contentType)
        assertTrue(http.calls[2].url.endsWith("/hard-negatives/complete"))
        assertTrue(http.calls[2].body.contains("\"negativeIds\":[\"negative-1\"]"))
        videoFile.delete()
    }

    @Test
    fun initResponseMustContainExactlyOneUploadTarget(): Unit = runBlocking {
        val videoFile = File.createTempFile("negative", ".mov")
        val service = HardNegativeUploadService(
            RecordingUploadHttpClient(
                UploadHttpResponse(200, initResponseJson(uploadCount = 0)),
            ),
        )

        assertHardNegativeError<HardNegativeUploadError.ResponseCountMismatch> {
            service.upload("landmark-1", "token", CapturedNegativeVideo(videoFile))
        }
        assertTrue(service.status.value.startsWith("Negative upload failed:"))
        assertFalse(service.isUploading.value)
        videoFile.delete()
    }

    @Test
    fun completionCountsMustReportExactlyOneSuccessAndNoFailures(): Unit = runBlocking {
        val videoFile = File.createTempFile("negative", ".mov")
        val service = HardNegativeUploadService(
            RecordingUploadHttpClient(
                UploadHttpResponse(200, initResponseJson(uploadCount = 1)),
                UploadHttpResponse(200),
                UploadHttpResponse(200, completeResponseJson(processed = 0, failed = 1)),
            ),
        )

        assertHardNegativeError<HardNegativeUploadError.IncompleteUpload> {
            service.upload("landmark-1", "token", CapturedNegativeVideo(videoFile))
        }
        assertEquals(0.85, service.progress.value, 0.0)
        assertTrue(service.status.value.contains("failed to complete"))
        videoFile.delete()
    }

    private fun initResponseJson(uploadCount: Int): String {
        val uploads = if (uploadCount == 1) {
            """[{"negativeId":"negative-1","uploadUrl":"https://s3.test/negative","sourceBucket":"source","sourceKey":"key","contentType":"video/mp4"}]"""
        } else {
            "[]"
        }
        return """{"message":"ready","batchId":"batch-1","landmarkId":"landmark-1","landmarkLabel":"Clock Tower","landmarkFolder":"clock-tower","expiresInSeconds":900,"uploads":$uploads}"""
    }

    private fun completeResponseJson(processed: Int, failed: Int): String =
        """{"message":"complete","landmarkId":"landmark-1","batchId":"batch-1","processedCount":$processed,"failedCount":$failed,"dirtyMarked":true,"processed":[],"failed":[]}"""

    private suspend inline fun <reified T : Throwable> assertHardNegativeError(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue(
                "Expected ${T::class.java.simpleName}, received ${error::class.java.simpleName}",
                error is T,
            )
        }
    }
}
