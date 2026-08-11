package looksee.angelll.com.models

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UploadServiceTest {
    @Test
    fun photoUploadUsesExactThreeStepContractAndNormalizesText(): Unit = runBlocking {
        val http = RecordingUploadHttpClient(
            UploadHttpResponse(
                200,
                """{"submissionId":"submission-1","uploadUrl":"https://s3.test/photo","s3Key":"positive/photo.jpg"}""",
            ),
            UploadHttpResponse(200),
            UploadHttpResponse(200),
        )
        val service = UploadService(http, unusedVideoMerger())

        val result = service.upload(
            userEmail = "  owner@example.com  ",
            idToken = "id-token",
            label = "  Clock Tower  ",
            landmarkId = "landmark-1",
            landmarkLabel = "Clock Tower",
            shortDescription = "   ",
            userDescription = "  Near the library  ",
            latitude = 40.1,
            longitude = -73.9,
            horizontalAccuracy = 10.0,
            videoFiles = emptyList(),
            imageJpegData = byteArrayOf(1, 2, 3),
        )

        assertEquals("submission-1", result.submissionId)
        assertEquals(MediaKind.PHOTO, result.mediaKind)
        assertEquals(PositiveUploadStage.COMPLETE, service.stage.value)
        assertEquals(1.0, service.progress.value, 0.0)
        assertFalse(service.isUploading.value)

        assertEquals("POST", http.calls[0].method)
        assertEquals("id-token", http.calls[0].authorization)
        assertTrue(http.calls[0].url.endsWith("/submissions/init"))
        assertTrue(http.calls[0].body.contains("\"userEmail\":\"owner@example.com\""))
        assertTrue(http.calls[0].body.contains("\"label\":\"Clock Tower\""))
        assertTrue(http.calls[0].body.contains("\"mediaKind\":\"photo\""))

        assertEquals("PUT_BYTES", http.calls[1].method)
        assertEquals("image/jpeg", http.calls[1].contentType)
        assertTrue(http.calls[2].url.endsWith("/submissions/complete"))
        assertTrue(http.calls[2].body.contains("\"shortDescription\":null"))
        assertTrue(http.calls[2].body.contains("\"userDescription\":\"Near the library\""))
    }

    @Test
    fun videoUploadUsesMp4ForAndroidOutputAndDeletesOwnedTemporaryFile(): Unit = runBlocking {
        val input = File.createTempFile("looksee_input", ".mp4").apply { writeBytes(byteArrayOf(1)) }
        val merged = File.createTempFile("looksee_merged", ".mp4").apply { writeBytes(byteArrayOf(2)) }
        val merger = PositiveVideoMerger { clips, minimumDuration ->
            assertEquals(listOf(input), clips)
            assertEquals(15.0, minimumDuration, 0.0)
            MergedVideo(merged, deleteAfterUpload = true)
        }
        val http = RecordingUploadHttpClient(
            UploadHttpResponse(
                200,
                """{"submissionId":"submission-2","uploadUrl":"https://s3.test/video","s3Key":"positive/video.mp4"}""",
            ),
            UploadHttpResponse(204),
            UploadHttpResponse(200),
        )
        val service = UploadService(http, merger)

        val result = service.upload(
            userEmail = "owner@example.com",
            idToken = "id-token",
            label = "Clock Tower",
            shortDescription = null,
            userDescription = null,
            latitude = null,
            longitude = null,
            horizontalAccuracy = null,
            videoFiles = listOf(input),
            imageJpegData = null,
        )

        assertEquals(MediaKind.VIDEO, result.mediaKind)
        assertTrue(http.calls[0].body.contains("\"filename\":\"video.mp4\""))
        assertTrue(http.calls[0].body.contains("\"contentType\":\"video/mp4\""))
        assertEquals("video/mp4", http.calls[1].contentType)
        assertFalse("UploadService owns and removes merged output", merged.exists())
        input.delete()
    }

    @Test
    fun mediaValidationRejectsNoSelectionAndMixedSelection(): Unit = runBlocking {
        val service = UploadService(RecordingUploadHttpClient(), unusedVideoMerger())

        assertUploadError<PositiveUploadError.NoMediaSelected> {
            service.upload(
                userEmail = "owner@example.com",
                idToken = "token",
                label = "Landmark",
                shortDescription = null,
                userDescription = null,
                latitude = null,
                longitude = null,
                horizontalAccuracy = null,
                videoFiles = emptyList(),
                imageJpegData = null,
            )
        }

        val video = File.createTempFile("looksee", ".mov")
        assertUploadError<PositiveUploadError.MultipleMediaSelected> {
            service.upload(
                userEmail = "owner@example.com",
                idToken = "token",
                label = "Landmark",
                shortDescription = null,
                userDescription = null,
                latitude = null,
                longitude = null,
                horizontalAccuracy = null,
                videoFiles = listOf(video),
                imageJpegData = byteArrayOf(1),
            )
        }
        video.delete()
    }

    @Test
    fun serverFailureIsRethrownAndPublishedAsFailedStage(): Unit = runBlocking {
        val http = RecordingUploadHttpClient(UploadHttpResponse(503, "maintenance"))
        val service = UploadService(http, unusedVideoMerger())

        assertUploadError<PositiveUploadError.BadStatus> {
            service.upload(
                userEmail = "owner@example.com",
                idToken = "token",
                label = "Landmark",
                shortDescription = null,
                userDescription = null,
                latitude = null,
                longitude = null,
                horizontalAccuracy = null,
                videoFiles = emptyList(),
                imageJpegData = byteArrayOf(1),
            )
        }

        assertEquals(PositiveUploadStage.FAILED, service.stage.value)
        assertTrue(service.detail.value.contains("temporarily unavailable"))
        assertFalse(service.isUploading.value)
    }

    private fun unusedVideoMerger() = PositiveVideoMerger { _, _ ->
        error("Video merger should not be called")
    }

    private suspend inline fun <reified T : Throwable> assertUploadError(
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

internal data class RecordedUploadCall(
    val method: String,
    val url: String,
    val authorization: String? = null,
    val contentType: String? = null,
    val body: String = "",
    val timeoutMillis: Int,
)

internal class RecordingUploadHttpClient(
    vararg responses: UploadHttpResponse,
) : UploadHttpClient {
    private val scriptedResponses = ArrayDeque(responses.toList())
    val calls = mutableListOf<RecordedUploadCall>()

    override suspend fun postJson(
        url: String,
        authorization: String,
        jsonBody: String,
        timeoutMillis: Int,
    ): UploadHttpResponse {
        calls += RecordedUploadCall(
            method = "POST",
            url = url,
            authorization = authorization,
            body = jsonBody,
            timeoutMillis = timeoutMillis,
        )
        return nextResponse()
    }

    override suspend fun putFile(
        url: String,
        contentType: String,
        file: File,
        timeoutMillis: Int,
    ): UploadHttpResponse {
        calls += RecordedUploadCall(
            method = "PUT_FILE",
            url = url,
            contentType = contentType,
            body = file.name,
            timeoutMillis = timeoutMillis,
        )
        return nextResponse()
    }

    override suspend fun putBytes(
        url: String,
        contentType: String,
        bytes: ByteArray,
        timeoutMillis: Int,
    ): UploadHttpResponse {
        calls += RecordedUploadCall(
            method = "PUT_BYTES",
            url = url,
            contentType = contentType,
            body = bytes.size.toString(),
            timeoutMillis = timeoutMillis,
        )
        return nextResponse()
    }

    private fun nextResponse(): UploadHttpResponse =
        if (scriptedResponses.isEmpty()) {
            error("No scripted HTTP response remains")
        } else {
            scriptedResponses.removeFirst()
        }
}
