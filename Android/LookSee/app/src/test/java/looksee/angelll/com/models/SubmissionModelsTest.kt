package looksee.angelll.com.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionModelsTest {
    private val gson = Gson()

    @Test
    fun initRequestUsesExactBackendFieldNamesAndLowercaseMediaKind() {
        val json = gson.toJson(
            InitSubmissionRequest(
                userEmail = "person@example.com",
                label = "City Hall",
                mediaKind = MediaKind.VIDEO,
                filename = "clip.mov",
                contentType = "video/quicktime",
            ),
        )

        assertTrue(json.contains("\"userEmail\":\"person@example.com\""))
        assertTrue(json.contains("\"mediaKind\":\"video\""))
        assertTrue(json.contains("\"contentType\":\"video/quicktime\""))
        assertFalse(json.contains("VIDEO"))
    }

    @Test
    fun decodesInitResponseUsingBackendContract() {
        val response = gson.fromJson(
            """{"submissionId":"sub-1","uploadUrl":"https://upload","s3Key":"key-1"}""",
            InitSubmissionResponse::class.java,
        )

        assertEquals("sub-1", response.submissionId)
        assertEquals("https://upload", response.uploadUrl)
        assertEquals("key-1", response.s3Key)
    }

    @Test
    fun completeRequestKeepsOptionalMetadataNullable() {
        val request = CompleteSubmissionRequest(
            submissionId = "sub-1",
            s3Key = "key-1",
            userEmail = "person@example.com",
            label = "City Hall",
            landmarkId = null,
            landmarkLabel = null,
            mediaKind = MediaKind.PHOTO,
            shortDescription = null,
            userDescription = null,
            latitude = null,
            longitude = null,
            horizontalAccuracy = null,
        )

        assertEquals(MediaKind.PHOTO, request.mediaKind)
        assertNull(request.landmarkId)
        assertNull(request.horizontalAccuracy)
    }
}
