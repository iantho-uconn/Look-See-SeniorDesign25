package looksee.angelll.com.models

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class PositiveUploadStage {
    IDLE,
    VALIDATING,
    PREPARING_UPLOAD,
    UPLOADING_MEDIA,
    FINALIZING,
    COMPLETE,
    FAILED,
}

sealed class PositiveUploadError(message: String) : Exception(message) {
    data object UploadAlreadyInProgress :
        PositiveUploadError("An upload is already in progress.")

    data object MissingLabel :
        PositiveUploadError("Please enter a landmark label before uploading.")

    data object MissingUserEmail :
        PositiveUploadError("We could not verify your account. Please sign in again and retry.")

    data object NotSignedIn :
        PositiveUploadError("You must be signed in before uploading.")

    data object TokensUnavailable :
        PositiveUploadError(
            "We could not access your secure login token. " +
                "Please sign out, sign back in, and try again.",
        )

    data object NoMediaSelected :
        PositiveUploadError(
            "Please record one or more videos (totaling at least 15 seconds) " +
                "or take one landmark photo.",
        )

    data object MultipleMediaSelected :
        PositiveUploadError("Please select either video(s) or a photo, not both.")

    data object InvalidUrl :
        PositiveUploadError("The server returned an invalid upload link. Please try again.")

    data object InvalidResponse :
        PositiveUploadError("The server returned an unexpected response. Please try again.")

    data object MissingImageData :
        PositiveUploadError("The selected photo could not be prepared for upload.")

    data class BadStatus(val code: Int, val responseBody: String) :
        PositiveUploadError(messageForStatus(code))

    companion object {
        private fun messageForStatus(code: Int): String = when {
            code == 401 || code == 403 ->
                "Your session is no longer authorized. Please sign in again."
            code == 404 -> "The upload service could not find the requested resource."
            code == 408 -> "The upload request timed out. Please try again."
            code == 413 -> "The selected media file is too large to upload."
            code == 429 ->
                "Too many upload attempts were made. Please wait a moment and try again."
            code >= 500 ->
                "The LookSee service is temporarily unavailable. Please try again shortly."
            else -> "The upload could not be completed. Server error $code."
        }
    }
}

/**
 * Android translation of UploadService.swift.
 *
 * Construct this service with an Android Context in production. The internal constructor keeps
 * network and media preparation deterministic in local unit tests.
 */
class UploadService private constructor(
    private val httpClient: UploadHttpClient,
    private val videoMerger: PositiveVideoMerger,
    private val gson: Gson,
) {
    constructor(context: Context) : this(
        httpClient = UrlConnectionUploadHttpClient(),
        videoMerger = Media3VideoMerger(context.applicationContext),
        gson = Gson(),
    )

    internal constructor(
        httpClient: UploadHttpClient,
        videoMerger: PositiveVideoMerger,
    ) : this(httpClient, videoMerger, Gson())

    private val uploadGuard = AtomicBoolean(false)

    private val _status = MutableStateFlow("Ready to upload")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _detail = MutableStateFlow("Your landmark media has not been uploaded yet.")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _stage = MutableStateFlow(PositiveUploadStage.IDLE)
    val stage: StateFlow<PositiveUploadStage> = _stage.asStateFlow()

    suspend fun upload(
        userEmail: String,
        idToken: String,
        label: String,
        landmarkId: String? = null,
        landmarkLabel: String? = null,
        shortDescription: String?,
        userDescription: String?,
        latitude: Double?,
        longitude: Double?,
        horizontalAccuracy: Double?,
        videoFiles: List<File>,
        imageJpegData: ByteArray?,
    ): PositiveSubmissionResult {
        if (!uploadGuard.compareAndSet(false, true)) {
            throw PositiveUploadError.UploadAlreadyInProgress
        }
        _isUploading.value = true

        try {
            updateStage(
                PositiveUploadStage.VALIDATING,
                0.05,
                "Checking your landmark details",
                "Making sure the required information and media are ready.",
            )

            val trimmedLabel = label.trim()
            if (trimmedLabel.isEmpty()) throw PositiveUploadError.MissingLabel

            val trimmedUserEmail = userEmail.trim()
            if (trimmedUserEmail.isEmpty()) throw PositiveUploadError.MissingUserEmail

            val hasVideo = videoFiles.isNotEmpty()
            val hasImage = imageJpegData != null
            if (!hasVideo && !hasImage) throw PositiveUploadError.NoMediaSelected
            if (hasVideo && hasImage) throw PositiveUploadError.MultipleMediaSelected

            val normalizedShortDescription = shortDescription.normalizedOptionalText()
            val normalizedUserDescription = userDescription.normalizedOptionalText()

            return if (imageJpegData != null) {
                uploadPhoto(
                    userEmail = trimmedUserEmail,
                    idToken = idToken,
                    label = trimmedLabel,
                    landmarkId = landmarkId,
                    landmarkLabel = landmarkLabel,
                    shortDescription = normalizedShortDescription,
                    userDescription = normalizedUserDescription,
                    latitude = latitude,
                    longitude = longitude,
                    horizontalAccuracy = horizontalAccuracy,
                    imageJpegData = imageJpegData,
                )
            } else {
                uploadVideo(
                    userEmail = trimmedUserEmail,
                    idToken = idToken,
                    label = trimmedLabel,
                    landmarkId = landmarkId,
                    landmarkLabel = landmarkLabel,
                    shortDescription = normalizedShortDescription,
                    userDescription = normalizedUserDescription,
                    latitude = latitude,
                    longitude = longitude,
                    horizontalAccuracy = horizontalAccuracy,
                    videoFiles = videoFiles,
                )
            }
        } catch (error: Throwable) {
            updateStage(
                PositiveUploadStage.FAILED,
                _progress.value,
                "Upload couldn’t be completed",
                userFriendlyMessage(error),
            )
            throw error
        } finally {
            _isUploading.value = false
            uploadGuard.set(false)
        }
    }

    fun reset() {
        if (_isUploading.value) return
        updateStage(
            PositiveUploadStage.IDLE,
            0.0,
            "Ready to upload",
            "Your landmark media has not been uploaded yet.",
        )
    }

    private suspend fun uploadPhoto(
        userEmail: String,
        idToken: String,
        label: String,
        landmarkId: String?,
        landmarkLabel: String?,
        shortDescription: String?,
        userDescription: String?,
        latitude: Double?,
        longitude: Double?,
        horizontalAccuracy: Double?,
        imageJpegData: ByteArray,
    ): PositiveSubmissionResult {
        if (imageJpegData.isEmpty()) throw PositiveUploadError.MissingImageData

        val mediaKind = MediaKind.PHOTO
        val filename = "photo.jpg"
        val contentType = "image/jpeg"

        updateStage(
            PositiveUploadStage.PREPARING_UPLOAD,
            0.12,
            "Preparing a secure upload",
            "This should only take a moment.",
        )
        val initResponse = initSubmission(
            InitSubmissionRequest(
                userEmail = userEmail,
                label = label,
                landmarkId = landmarkId,
                mediaKind = mediaKind,
                filename = filename,
                contentType = contentType,
            ),
            idToken,
        )

        updateStage(
            PositiveUploadStage.UPLOADING_MEDIA,
            0.20,
            "Uploading your landmark photo",
            "Keep LookSee open while your photo is uploaded.",
        )
        validateS3Response(
            httpClient.putBytes(
                url = initResponse.uploadUrl,
                contentType = contentType,
                bytes = imageJpegData,
                timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
            ),
        )

        val completeRequest = CompleteSubmissionRequest(
            submissionId = initResponse.submissionId,
            s3Key = initResponse.s3Key,
            userEmail = userEmail,
            label = label,
            landmarkId = landmarkId,
            landmarkLabel = landmarkLabel,
            mediaKind = mediaKind,
            shortDescription = shortDescription,
            userDescription = userDescription,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracy = horizontalAccuracy,
        )
        finalizeSubmission(completeRequest, idToken)

        updateStage(
            PositiveUploadStage.COMPLETE,
            1.0,
            "Landmark media uploaded",
            "Your positive landmark media was saved successfully.",
        )
        return PositiveSubmissionResult(
            initResponse.submissionId,
            landmarkId,
            mediaKind,
            initResponse.s3Key,
        )
    }

    private suspend fun uploadVideo(
        userEmail: String,
        idToken: String,
        label: String,
        landmarkId: String?,
        landmarkLabel: String?,
        shortDescription: String?,
        userDescription: String?,
        latitude: Double?,
        longitude: Double?,
        horizontalAccuracy: Double?,
        videoFiles: List<File>,
    ): PositiveSubmissionResult {
        updateStage(
            PositiveUploadStage.PREPARING_UPLOAD,
            0.08,
            "Combining your clips",
            "Stitching ${videoFiles.size} clip(s) into one video.",
        )

        val mergedVideo = videoMerger.mergeAndValidate(
            videoFiles,
            MINIMUM_COMBINED_VIDEO_DURATION_SECONDS,
        )
        try {
            val descriptor = videoDescriptor(mergedVideo.file)
            val mediaKind = MediaKind.VIDEO

            updateStage(
                PositiveUploadStage.PREPARING_UPLOAD,
                0.15,
                "Preparing a secure upload",
                "This should only take a moment.",
            )
            val initResponse = initSubmission(
                InitSubmissionRequest(
                    userEmail = userEmail,
                    label = label,
                    landmarkId = landmarkId,
                    mediaKind = mediaKind,
                    filename = descriptor.uploadFilename,
                    contentType = descriptor.contentType,
                ),
                idToken,
            )

            updateStage(
                PositiveUploadStage.UPLOADING_MEDIA,
                0.20,
                "Uploading your landmark video",
                "Videos can take a little longer. Keep LookSee open until the upload finishes.",
            )
            validateS3Response(
                httpClient.putFile(
                    url = initResponse.uploadUrl,
                    contentType = descriptor.contentType,
                    file = mergedVideo.file,
                    timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
                ),
            )

            val completeRequest = CompleteSubmissionRequest(
                submissionId = initResponse.submissionId,
                s3Key = initResponse.s3Key,
                userEmail = userEmail,
                label = label,
                landmarkId = landmarkId,
                landmarkLabel = landmarkLabel,
                mediaKind = mediaKind,
                shortDescription = shortDescription,
                userDescription = userDescription,
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracy = horizontalAccuracy,
            )
            finalizeSubmission(completeRequest, idToken)

            updateStage(
                PositiveUploadStage.COMPLETE,
                1.0,
                "Landmark media uploaded",
                "Your combined video was saved successfully.",
            )
            return PositiveSubmissionResult(
                initResponse.submissionId,
                landmarkId,
                mediaKind,
                initResponse.s3Key,
            )
        } finally {
            if (mergedVideo.deleteAfterUpload) mergedVideo.file.delete()
        }
    }

    private suspend fun initSubmission(
        request: InitSubmissionRequest,
        token: String,
    ): InitSubmissionResponse {
        val response = httpClient.postJson(
            url = "$BASE_URL/submissions/init",
            authorization = token,
            jsonBody = gson.toJson(request),
            timeoutMillis = API_TIMEOUT_MILLIS,
        )
        validateApiResponse(response)
        return try {
            gson.fromJson(response.body, InitSubmissionResponse::class.java)
                ?: throw PositiveUploadError.InvalidResponse
        } catch (error: PositiveUploadError) {
            throw error
        } catch (_: Exception) {
            throw PositiveUploadError.InvalidResponse
        }
    }

    private suspend fun finalizeSubmission(
        request: CompleteSubmissionRequest,
        token: String,
    ) {
        updateStage(
            PositiveUploadStage.FINALIZING,
            0.88,
            "Saving your landmark",
            "Your media is uploaded. We’re attaching its information and location.",
        )
        validateApiResponse(
            httpClient.postJson(
                url = "$BASE_URL/submissions/complete",
                authorization = token,
                jsonBody = gson.toJson(request),
                timeoutMillis = API_TIMEOUT_MILLIS,
            ),
        )
    }

    private fun validateApiResponse(response: UploadHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw PositiveUploadError.BadStatus(response.statusCode, response.body)
        }
    }

    private fun validateS3Response(response: UploadHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw PositiveUploadError.BadStatus(response.statusCode, "The S3 media upload failed.")
        }
    }

    private fun updateStage(
        stage: PositiveUploadStage,
        progress: Double,
        status: String,
        detail: String,
    ) {
        _stage.value = stage
        _progress.value = progress.coerceIn(0.0, 1.0)
        _status.value = status
        _detail.value = detail
    }

    private fun userFriendlyMessage(error: Throwable): String = when (error) {
        is VideoMergeError -> error.message.orEmpty()
        is PositiveUploadError -> error.message.orEmpty()
        is UnknownHostException ->
            "No internet connection was found. Your information is still on this screen, " +
                "so reconnect and try again."
        is SocketTimeoutException ->
            "The upload took too long. Check your connection and try again."
        is ConnectException ->
            "LookSee could not connect to the upload service. " +
                "Please check your connection and try again."
        is SocketException ->
            "The connection was interrupted. Your information is still on this screen, " +
                "so you can retry."
        else -> error.message ?: "The upload could not be completed."
    }

    private data class VideoDescriptor(
        val uploadFilename: String,
        val contentType: String,
    )

    private fun videoDescriptor(file: File): VideoDescriptor =
        if (file.extension.equals("mov", ignoreCase = true)) {
            VideoDescriptor("video.mov", "video/quicktime")
        } else {
            VideoDescriptor("video.mp4", "video/mp4")
        }

    companion object {
        private const val BASE_URL =
            "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev"
        private const val API_TIMEOUT_MILLIS = 60_000
        private const val MEDIA_UPLOAD_TIMEOUT_MILLIS = 300_000
        private const val MINIMUM_COMBINED_VIDEO_DURATION_SECONDS = 1.0
    }
}

private fun String?.normalizedOptionalText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

internal data class UploadHttpResponse(
    val statusCode: Int,
    val body: String = "",
)

internal interface UploadHttpClient {
    suspend fun postJson(
        url: String,
        authorization: String,
        jsonBody: String,
        timeoutMillis: Int,
    ): UploadHttpResponse

    suspend fun putFile(
        url: String,
        contentType: String,
        file: File,
        timeoutMillis: Int,
    ): UploadHttpResponse

    suspend fun putBytes(
        url: String,
        contentType: String,
        bytes: ByteArray,
        timeoutMillis: Int,
    ): UploadHttpResponse
}

internal class UrlConnectionUploadHttpClient : UploadHttpClient {
    override suspend fun postJson(
        url: String,
        authorization: String,
        jsonBody: String,
        timeoutMillis: Int,
    ): UploadHttpResponse = withContext(Dispatchers.IO) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        execute(url, "POST", timeoutMillis) { connection ->
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
    }

    override suspend fun putFile(
        url: String,
        contentType: String,
        file: File,
        timeoutMillis: Int,
    ): UploadHttpResponse = withContext(Dispatchers.IO) {
        if (!file.isFile) throw PositiveUploadError.NoMediaSelected
        execute(url, "PUT", timeoutMillis) { connection ->
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    override suspend fun putBytes(
        url: String,
        contentType: String,
        bytes: ByteArray,
        timeoutMillis: Int,
    ): UploadHttpResponse = withContext(Dispatchers.IO) {
        execute(url, "PUT", timeoutMillis) { connection ->
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
    }

    private fun execute(
        urlString: String,
        method: String,
        timeoutMillis: Int,
        configureAndWrite: (HttpURLConnection) -> Unit,
    ): UploadHttpResponse {
        val url = try {
            URL(urlString)
        } catch (_: Exception) {
            throw PositiveUploadError.InvalidUrl
        }
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw PositiveUploadError.InvalidResponse
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = true
            configureAndWrite(connection)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            UploadHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
