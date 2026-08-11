package looksee.angelll.com.models

import com.google.gson.Gson
import java.io.File
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class HardNegativeUploadError(message: String) : Exception(message) {
    data object NoVideo : HardNegativeUploadError("No negative video was provided.")
    data object InvalidUrl :
        HardNegativeUploadError("The upload service returned an invalid URL.")
    data object InvalidResponse :
        HardNegativeUploadError("The server returned an invalid response.")
    data class BadStatus(val code: Int, val responseBody: String) :
        HardNegativeUploadError("HTTP $code: $responseBody")
    data object ResponseCountMismatch :
        HardNegativeUploadError("Expected 1 upload URL, but received a different amount.")
    data class MissingLocalFile(val filename: String) :
        HardNegativeUploadError("The local video could not be found: $filename")
    data object IncompleteUpload :
        HardNegativeUploadError("The upload failed to complete successfully.")
}

class HardNegativeUploadService internal constructor(
    private val httpClient: UploadHttpClient,
    private val gson: Gson,
) {
    constructor() : this(UrlConnectionUploadHttpClient(), Gson())

    internal constructor(httpClient: UploadHttpClient) : this(httpClient, Gson())

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    suspend fun upload(
        landmarkId: String,
        idToken: String,
        video: CapturedNegativeVideo,
    ): HardNegativeCompleteResponse {
        _isUploading.value = true
        _progress.value = 0.0
        _status.value = "Preparing negative video…"

        try {
            val initResponse = initializeUpload(landmarkId, idToken, video)
            if (initResponse.uploads.size != 1) {
                throw HardNegativeUploadError.ResponseCountMismatch
            }
            val uploadTarget = initResponse.uploads.single()

            _progress.value = 0.1
            _status.value = "Uploading negative video to S3…"
            uploadVideo(video, uploadTarget)

            _progress.value = 0.85
            _status.value = "Finalizing negative video…"
            val completeResponse = completeUpload(
                landmarkId = landmarkId,
                batchId = initResponse.batchId,
                negativeIds = listOf(uploadTarget.negativeId),
                token = idToken,
            )

            if (completeResponse.failedCount != 0 || completeResponse.processedCount != 1) {
                throw HardNegativeUploadError.IncompleteUpload
            }

            _progress.value = 1.0
            _status.value = "Negative video uploaded ✅"
            return completeResponse
        } catch (error: Throwable) {
            _status.value = "Negative upload failed: ${error.message.orEmpty()}"
            throw error
        } finally {
            _isUploading.value = false
        }
    }

    fun reset() {
        _status.value = "Idle"
        _progress.value = 0.0
        _isUploading.value = false
    }

    private suspend fun initializeUpload(
        landmarkId: String,
        token: String,
        video: CapturedNegativeVideo,
    ): HardNegativeInitResponse {
        if (!video.file.isFile) {
            throw HardNegativeUploadError.MissingLocalFile(video.filename)
        }
        val contentType = videoContentType(video.file)
        val body = HardNegativeInitRequest(
            files = listOf(HardNegativeFileRequest(video.filename, contentType)),
        )
        val response = httpClient.postJson(
            url = hardNegativeUrl(landmarkId, "init"),
            authorization = token,
            jsonBody = gson.toJson(body),
            timeoutMillis = API_TIMEOUT_MILLIS,
        )
        validateApiResponse(response)
        return decode(response.body, HardNegativeInitResponse::class.java)
    }

    private suspend fun uploadVideo(
        video: CapturedNegativeVideo,
        target: HardNegativeUploadTarget,
    ) {
        if (!video.file.isFile) {
            throw HardNegativeUploadError.MissingLocalFile(video.filename)
        }
        try {
            URL(target.uploadUrl)
        } catch (_: Exception) {
            throw HardNegativeUploadError.InvalidUrl
        }
        val response = httpClient.putFile(
            url = target.uploadUrl,
            contentType = target.contentType,
            file = video.file,
            timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
        )
        if (response.statusCode !in 200..299) {
            throw HardNegativeUploadError.BadStatus(
                response.statusCode,
                "S3 PUT failed for ${video.filename}",
            )
        }
    }

    private suspend fun completeUpload(
        landmarkId: String,
        batchId: String,
        negativeIds: List<String>,
        token: String,
    ): HardNegativeCompleteResponse {
        val response = httpClient.postJson(
            url = hardNegativeUrl(landmarkId, "complete"),
            authorization = token,
            jsonBody = gson.toJson(HardNegativeCompleteRequest(batchId, negativeIds)),
            timeoutMillis = API_TIMEOUT_MILLIS,
        )
        validateApiResponse(response)
        return decode(response.body, HardNegativeCompleteResponse::class.java)
    }

    private fun hardNegativeUrl(landmarkId: String, action: String): String {
        val encodedId = URLEncoder.encode(
            landmarkId,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        return "$BASE_URL/landmarks/$encodedId/hard-negatives/$action"
    }

    private fun validateApiResponse(response: UploadHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw HardNegativeUploadError.BadStatus(response.statusCode, response.body)
        }
    }

    private fun <T> decode(json: String, type: Class<T>): T = try {
        gson.fromJson(json, type) ?: throw HardNegativeUploadError.InvalidResponse
    } catch (error: HardNegativeUploadError) {
        throw error
    } catch (_: Exception) {
        throw HardNegativeUploadError.InvalidResponse
    }

    private fun videoContentType(file: File): String =
        if (file.extension.equals("mov", ignoreCase = true)) {
            "video/quicktime"
        } else {
            "video/mp4"
        }

    companion object {
        private const val BASE_URL =
            "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"
        private const val API_TIMEOUT_MILLIS = 60_000
        private const val MEDIA_UPLOAD_TIMEOUT_MILLIS = 300_000
    }
}

private data class HardNegativeInitRequest(
    val files: List<HardNegativeFileRequest>,
)

private data class HardNegativeFileRequest(
    val filename: String,
    val contentType: String,
)

private data class HardNegativeInitResponse(
    val message: String,
    val batchId: String,
    val landmarkId: String,
    val landmarkLabel: String,
    val landmarkFolder: String,
    val expiresInSeconds: Int,
    val uploads: List<HardNegativeUploadTarget>,
)

private data class HardNegativeUploadTarget(
    val negativeId: String,
    val uploadUrl: String,
    val sourceBucket: String,
    val sourceKey: String,
    val contentType: String,
)

private data class HardNegativeCompleteRequest(
    val batchId: String,
    val negativeIds: List<String>,
)

data class HardNegativeCompleteResponse(
    val message: String,
    val landmarkId: String,
    val batchId: String,
    val processedCount: Int,
    val failedCount: Int,
    val dirtyMarked: Boolean,
    val processed: List<HardNegativeProcessedItem>,
    val failed: List<HardNegativeFailedItem>,
)

data class HardNegativeProcessedItem(
    val negativeId: String,
    val status: String,
    val datasetImageKey: String,
    val datasetLabelKey: String,
)

data class HardNegativeFailedItem(
    val negativeId: String,
    val reason: String,
)
