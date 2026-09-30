package looksee.angelll.com.models

import android.net.Uri
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


sealed class BusinessLandmarkServiceError(message: String) : Exception(message) {
    data object NotSignedIn :
        BusinessLandmarkServiceError("You must be signed in before managing landmarks.")

    data object TokensUnavailable :
        BusinessLandmarkServiceError("Cognito tokens were unavailable.")

    class BadStatus(val code: Int, val responseBody: String) :
        BusinessLandmarkServiceError("API error $code: $responseBody")

    data object InvalidUploadUrl :
        BusinessLandmarkServiceError("The upload URL returned by the server was invalid.")

    data object NoHardNegativeUploadTarget :
        BusinessLandmarkServiceError(
            "The hard-negative upload request did not return an upload target.",
        )

    data object InvalidResponse :
        BusinessLandmarkServiceError("The server returned an invalid response.")

    data object HardNegativeRetryFailed :
        BusinessLandmarkServiceError(
            "The negative media could not be queued for processing again.",
        )
}

fun interface BusinessLandmarkDataSource {
    suspend fun fetchBusinessLandmarks(): BusinessLandmarkListResponse
}

fun interface HardNegativeRetryDataSource {
    suspend fun retryHardNegativeProcessing(
        landmarkId: String,
        batchId: String,
        negativeId: String,
    ): BusinessHardNegativeCompleteResponse
}

class BusinessLandmarkService internal constructor(
    private val tokenProvider: IdTokenProvider,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) : BusinessLandmarkDataSource, HardNegativeRetryDataSource {
    constructor() : this(
        AmplifyCognitoIdTokenProvider(),
        UrlConnectionBusinessHttpClient(),
        Gson(),
    )

    internal constructor(
        tokenProvider: IdTokenProvider,
        httpClient: BusinessHttpClient,
    ) : this(tokenProvider, httpClient, Gson())

    override suspend fun fetchBusinessLandmarks(): BusinessLandmarkListResponse =
        requestJson(
            method = "GET",
            url = "$LOOKSEE_API_BASE_URL/business/landmarks",
            responseType = BusinessLandmarkListResponse::class.java,
        )

    suspend fun updateShortDescription(
        landmarkId: String,
        shortDescription: String,
    ): BusinessLandmark = patchBusinessLandmark(
        landmarkId,
        BusinessLandmarkPatchBody(shortDescription = shortDescription),
    )

    suspend fun updateLandmarkSettings(
        landmarkId: String,
        isActive: Boolean? = null,
        promotionEnabled: Boolean? = null,
    ): BusinessLandmark = patchBusinessLandmark(
        landmarkId,
        BusinessLandmarkPatchBody(
            isActive = isActive,
            promotionEnabled = promotionEnabled,
        ),
    )

    suspend fun updateWebsiteUrl(
        landmarkId: String,
        websiteUrl: String,
    ): BusinessLandmark = patchBusinessLandmark(
        landmarkId,
        BusinessLandmarkPatchBody(websiteUrl = websiteUrl),
    )

    suspend fun deleteLandmark(
        landmarkId: String,
        confirmation: String,
    ): BusinessLandmarkDeleteResponse = requestJson(
        method = "DELETE",
        url = businessLandmarkUrl(landmarkId),
        body = BusinessLandmarkDeleteBody(confirmation),
        responseType = BusinessLandmarkDeleteResponse::class.java,
    )

    suspend fun uploadBusinessMedia(
        landmarkId: String,
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        filename: String,
        contentType: String,
        data: ByteArray,
    ): BusinessMediaUploadCompleteResponse = when (datasetRole) {
        BusinessDatasetRole.POSITIVE -> uploadPositiveBusinessMedia(
            landmarkId = landmarkId,
            datasetRole = datasetRole,
            mediaKind = mediaKind,
            filename = filename,
            contentType = contentType,
            data = data,
        )

        BusinessDatasetRole.HARD_NEGATIVE -> uploadHardNegativeMedia(
            landmarkId = landmarkId,
            filename = filename,
            contentType = contentType,
            data = data,
        )
    }

    private suspend fun patchBusinessLandmark(
        landmarkId: String,
        body: BusinessLandmarkPatchBody,
    ): BusinessLandmark = requestJson(
        method = "PATCH",
        url = businessLandmarkUrl(landmarkId),
        body = body,
        responseType = BusinessLandmarkUpdateResponse::class.java,
    ).item

    private suspend fun uploadPositiveBusinessMedia(
        landmarkId: String,
        datasetRole: BusinessDatasetRole,
        mediaKind: BusinessMediaKind,
        filename: String,
        contentType: String,
        data: ByteArray,
    ): BusinessMediaUploadCompleteResponse {
        val init = requestJson(
            method = "POST",
            url = "${businessLandmarkUrl(landmarkId)}/uploads/init",
            body = PositiveUploadInitBody(
                mediaKind = mediaKind.wireValue,
                datasetRole = datasetRole.wireValue,
                filename = filename,
                contentType = contentType,
            ),
            responseType = BusinessMediaUploadInitResponse::class.java,
        )
        uploadToPresignedUrl(init.uploadUrl, contentType, filename, data)
        return requestJson(
            method = "POST",
            url = "${businessLandmarkUrl(landmarkId)}/uploads/complete",
            body = PositiveUploadCompleteBody(init.submissionId, init.s3Key),
            responseType = BusinessMediaUploadCompleteResponse::class.java,
        )
    }

    suspend fun uploadGlobalNegativeVideo(file: java.io.File) {
        val fileName = file.name
        val initPayload = mapOf(
            "filename" to fileName,
            "mediaKind" to "video",
            "contentType" to "video/mp4",
            "datasetRole" to "global_negative",
            "label" to "Global Negative Admin"
        )
        val initBody = gson.toJson(initPayload)
        
        val token = try {
            tokenProvider.idToken()
        } catch (_: Exception) { "" }

        val initRequest = BusinessHttpRequest(
            method = "POST",
            url = "$LOOKSEE_API_BASE_URL/submissions/init",
            authorization = "Bearer $token",
            contentType = "application/json",
            body = initBody.toByteArray(Charsets.UTF_8)
        )
        
        val initResponse = httpClient.execute(initRequest)
        validate(initResponse)
        
        val initJson = org.json.JSONObject(initResponse.bodyText)
        val uploadUrlObj = initJson.optJSONObject("uploadUrl")
        val url = uploadUrlObj?.optString("url") ?: ""
        val fieldsObj = uploadUrlObj?.optJSONObject("fields")
        val fieldsMap = mutableMapOf<String, String>()
        if (fieldsObj != null) {
            val keys = fieldsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                fieldsMap[key] = fieldsObj.getString(key)
            }
        }
        val s3Key = initJson.optString("s3Key")
        val submissionId = initJson.optString("submissionId")
        
        if (url.isEmpty() || s3Key.isEmpty() || submissionId.isEmpty()) {
            throw BusinessLandmarkServiceError.InvalidResponse
        }
        
        val presignedPost = S3PresignedPost(url, fieldsMap)
        uploadToPresignedUrl(presignedPost, "video/mp4", fileName, file.readBytes())
        
        val completePayload = mapOf(
            "submissionId" to submissionId,
            "s3Key" to s3Key,
            "datasetRole" to "global_negative"
        )
        val completeBody = gson.toJson(completePayload)
        
        val completeRequest = BusinessHttpRequest(
            method = "POST",
            url = "$LOOKSEE_API_BASE_URL/submissions/complete",
            authorization = "Bearer $token",
            contentType = "application/json",
            body = completeBody.toByteArray(Charsets.UTF_8)
        )
        
        val completeResponse = httpClient.execute(completeRequest)
        validate(completeResponse)
    }

    private suspend fun uploadHardNegativeMedia(
        landmarkId: String,
        filename: String,
        contentType: String,
        data: ByteArray,
    ): BusinessMediaUploadCompleteResponse {
        val endpoint = "$LOOKSEE_API_BASE_URL/landmarks/" +
            "${encodedPathSegment(landmarkId)}/hard-negatives"
        val init = requestJson(
            method = "POST",
            url = "$endpoint/init",
            body = BusinessHardNegativeInitBody(
                listOf(BusinessHardNegativeFileBody(filename, contentType)),
            ),
            responseType = BusinessHardNegativeInitResponse::class.java,
        )
        val uploadTarget = init.uploads.firstOrNull()
            ?: throw BusinessLandmarkServiceError.NoHardNegativeUploadTarget
        uploadToPresignedUrl(uploadTarget.uploadUrl, uploadTarget.contentType, filename, data)
        val completed = requestJson(
            method = "POST",
            url = "$endpoint/complete",
            body = BusinessHardNegativeCompleteBody(
                batchId = init.batchId,
                negativeIds = listOf(uploadTarget.negativeId),
            ),
            responseType = BusinessHardNegativeCompleteResponse::class.java,
        )
        return BusinessMediaUploadCompleteResponse(
            ok = completed.failedCount == 0,
            submissionId = uploadTarget.negativeId,
            status = if (completed.failedCount == 0) {
                completed.processed?.firstOrNull()?.status ?: "PROCESSING"
            } else {
                "FAILED"
            },
            datasetRole = BusinessDatasetRole.HARD_NEGATIVE.wireValue,
            landmarkId = landmarkId,
            s3Key = uploadTarget.sourceKey,
        )
    }

    /**
     * Requeues an existing hard-negative source without uploading its video again.
     * Ownership and source availability are verified by the backend.
     */
    override suspend fun retryHardNegativeProcessing(
        landmarkId: String,
        batchId: String,
        negativeId: String,
    ): BusinessHardNegativeCompleteResponse {
        val endpoint = "$LOOKSEE_API_BASE_URL/landmarks/" +
            "${encodedPathSegment(landmarkId)}/hard-negatives/complete"
        val response = requestJson(
            method = "POST",
            url = endpoint,
            body = BusinessHardNegativeCompleteBody(
                batchId = batchId,
                negativeIds = listOf(negativeId),
                forceRetry = true,
            ),
            responseType = BusinessHardNegativeCompleteResponse::class.java,
        )
        if (response.failedCount != 0 || response.processedCount != 1) {
            throw BusinessLandmarkServiceError.HardNegativeRetryFailed
        }
        return response
    }

    private suspend fun uploadToPresignedUrl(
        uploadUrl: S3PresignedPost,
        contentType: String,
        filename: String,
        data: ByteArray,
    ) {
        val boundary = "Boundary-${java.util.UUID.randomUUID()}"
        val multipartContentType = "multipart/form-data; boundary=$boundary"

        val bodyStream = java.io.ByteArrayOutputStream()
        
        for ((key, value) in uploadUrl.fields) {
            bodyStream.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            bodyStream.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            bodyStream.write("$value\r\n".toByteArray(Charsets.UTF_8))
        }
        bodyStream.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        bodyStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n".toByteArray(Charsets.UTF_8))
        bodyStream.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
        
        bodyStream.write(data)
        
        bodyStream.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "POST",
                url = uploadUrl.url,
                contentType = multipartContentType,
                accept = null,
                timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
                body = bodyStream.toByteArray()
            )
        )
        validate(response)
    }

    private suspend fun <T> requestJson(
        method: String,
        url: String,
        body: Any? = null,
        responseType: Class<T>,
    ): T {
        val token = try {
            tokenProvider.idToken()
        } catch (_: BusinessAuthenticationError.NotSignedIn) {
            throw BusinessLandmarkServiceError.NotSignedIn
        } catch (_: BusinessAuthenticationError.TokensUnavailable) {
            throw BusinessLandmarkServiceError.TokensUnavailable
        }
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = method,
                url = url,
                authorization = "Bearer $token",
                body = body?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) },
                contentType = body?.let { "application/json" },
            ),
        )
        validate(response)
        return try {
            gson.fromJson(response.bodyText, responseType)
                ?: throw BusinessLandmarkServiceError.InvalidResponse
        } catch (error: BusinessLandmarkServiceError) {
            throw error
        } catch (_: Exception) {
            throw BusinessLandmarkServiceError.InvalidResponse
        }
    }

    private fun validate(response: BusinessHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw BusinessLandmarkServiceError.BadStatus(
                response.statusCode,
                response.bodyText,
            )
        }
    }

    private fun businessLandmarkUrl(landmarkId: String): String =
        "$LOOKSEE_API_BASE_URL/business/landmarks/${encodedPathSegment(landmarkId)}"

    private data class PositiveUploadInitBody(
        val mediaKind: String,
        val datasetRole: String,
        val filename: String,
        val contentType: String,
    )

    private data class PositiveUploadCompleteBody(val submissionId: String, val s3Key: String)

    private data class BusinessLandmarkPatchBody(
        val shortDescription: String? = null,
        val websiteUrl: String? = null,
        val isActive: Boolean? = null,
        val promotionEnabled: Boolean? = null,
    )

    private data class BusinessLandmarkDeleteBody(val confirmation: String)

    private data class BusinessHardNegativeFileBody(
        val filename: String,
        val contentType: String,
    )

    private data class BusinessHardNegativeInitBody(
        val files: List<BusinessHardNegativeFileBody>,
    )

    private data class BusinessHardNegativeCompleteBody(
        val batchId: String,
        val negativeIds: List<String>,
        val forceRetry: Boolean? = null,
    )

    private companion object {
        const val MEDIA_UPLOAD_TIMEOUT_MILLIS = 300_000
    }
}


// --- Merged from BusinessLandmarkModels.kt ---
data class BusinessLandmarkListResponse(
    val items: List<BusinessLandmark> = emptyList(),
    val count: Int = 0,
)

data class BusinessLandmarkUpdateResponse(
    val ok: Boolean = false,
    val item: BusinessLandmark = BusinessLandmark(),
)

data class BusinessLandmarkDeleteResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val landmarkId: String = "",
    val status: String? = null,
)

data class BusinessLandmark(
    val landmarkId: String = "",
    val label: String = "",
    val shortDescription: String? = null,
    val websiteUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val promotion: String? = null,
    val promotionEnabled: Boolean? = null,
    val isActive: Boolean? = null,
    val userEmail: String? = null,
    val ownerUserId: String? = null,
    val createdByUserId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val ownershipUpdatedAt: String? = null,
    val status: String? = null,
    val cleanFrameCount: Int? = null,
    val requiredFrames: Int? = null,
    val secondsNeeded: Int? = null,
) {
    val id: String
        get() = landmarkId

    val displayDescription: String
        get() = shortDescription?.trim()?.takeIf(String::isNotEmpty)
            ?: "No description available."

    val displayStatus: String
        get() = when (status) {
            "NEEDS_MORE_MEDIA" -> "Action Needed"
            "PREPARING_DATA" -> "Preparing Data"
            "PENDING_TRAINING" -> "Waiting for Training"
            "TRAINING_MODEL" -> "In Training"
            "OPTIMIZING_MODEL" -> "Optimizing for Android"
            else -> if (isActive == false) "Inactive" else "Active"
        }

    val isProcessing: Boolean
        get() = status == "PREPARING_DATA" || status == "PENDING_TRAINING" || status == "TRAINING_MODEL" || status == "OPTIMIZING_MODEL"

    val displayPromotionStatus: String
        get() = if (promotionEnabled == true) {
            "Promotion enabled"
        } else {
            "No active promotion"
        }
}

data class BusinessPromotionListResponse(
    val items: List<BusinessPromotion> = emptyList(),
    val count: Int = 0,
)

data class BusinessPromotionMutationResponse(
    val ok: Boolean = false,
    val item: BusinessPromotion = BusinessPromotion(),
)

data class BusinessPromotionDeleteResponse(
    val ok: Boolean = false,
    val promotionId: String = "",
    val message: String? = null,
)

data class BusinessPromotion(
    val promotionId: String = "",
    val userEmail: String = "",
    val ownerUserId: String = "",
    val landmarkId: String = "",
    val landmarkLabel: String = "",
    val name: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val enabled: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    val id: String
        get() = promotionId
}

enum class BusinessDatasetRole(
    val wireValue: String,
    val displayName: String,
    val successMessage: String,
    val filenameComponent: String,
) {
    POSITIVE("positive", "Positive Media", "Positive media uploaded successfully.", "positive"),
    HARD_NEGATIVE(
        "hard-negative",
        "Negative Examples",
        "Negative example uploaded successfully.",
        "hard_negative",
    ),
}

enum class BusinessMediaKind(val wireValue: String) {
    PHOTO("photo"),
    VIDEO("video"),
}

data class BusinessMediaUploadInitResponse(
    val submissionId: String = "",
    val uploadUrl: S3PresignedPost,
    val s3Key: String = "",
    val bucket: String? = null,
    val datasetRole: String = "",
    val mediaKind: String = "",
    val landmarkId: String = "",
)

data class BusinessMediaUploadCompleteResponse(
    val ok: Boolean = false,
    val submissionId: String = "",
    val status: String? = null,
    val datasetRole: String? = null,
    val mediaKind: String? = null,
    val landmarkId: String? = null,
    val s3Key: String? = null,
)

internal data class BusinessHardNegativeInitResponse(
    val message: String? = null,
    val batchId: String = "",
    val landmarkId: String = "",
    val landmarkLabel: String? = null,
    val landmarkFolder: String? = null,
    val expiresInSeconds: Int? = null,
    val uploads: List<BusinessHardNegativeUploadTarget> = emptyList(),
)

internal data class BusinessHardNegativeUploadTarget(
    val negativeId: String = "",
    val uploadUrl: S3PresignedPost,
    val sourceBucket: String? = null,
    val sourceKey: String = "",
    val contentType: String = "",
)

data class BusinessHardNegativeCompleteResponse(
    val message: String? = null,
    val landmarkId: String = "",
    val batchId: String = "",
    val processedCount: Int = 0,
    val failedCount: Int = 0,
    val processed: List<BusinessHardNegativeProcessedItem>? = null,
)

data class BusinessHardNegativeProcessedItem(
    val negativeId: String = "",
    val status: String = "",
)

sealed class BusinessPromotionEditorContext {
    abstract val navigationTitle: String
    abstract val saveButtonTitle: String
    abstract val existingPromotion: BusinessPromotion?

    data class Create(
        override val navigationTitle: String = "New Promotion",
        override val saveButtonTitle: String = "Add",
        override val existingPromotion: BusinessPromotion? = null
    ) : BusinessPromotionEditorContext()

    data class Edit(
        val promotion: BusinessPromotion,
        override val navigationTitle: String = "Edit Promotion",
        override val saveButtonTitle: String = "Save",
        override val existingPromotion: BusinessPromotion? = promotion
    ) : BusinessPromotionEditorContext()
}

// --- Merged from BusinessApiSupport.kt ---
internal const val LOOKSEE_API_BASE_URL =
    "https://d11vl3v9w133rh.cloudfront.net"

sealed class BusinessAuthenticationError(message: String) : Exception(message) {
    data object NotSignedIn :
        BusinessAuthenticationError("You must be signed in to use this feature.")

    data object TokensUnavailable :
        BusinessAuthenticationError("Cognito tokens were unavailable.")
}

fun interface IdTokenProvider {
    suspend fun idToken(): String
}

class AmplifyCognitoIdTokenProvider : IdTokenProvider {
    override suspend fun idToken(): String {
        val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            ?: throw BusinessAuthenticationError.TokensUnavailable
        if (!session.isSignedIn) throw BusinessAuthenticationError.NotSignedIn
        return session.userPoolTokensResult.value?.idToken
            ?.takeIf(String::isNotBlank)
            ?: throw BusinessAuthenticationError.TokensUnavailable
    }
}

internal data class BusinessHttpRequest(
    val method: String,
    val url: String,
    val authorization: String? = null,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val accept: String? = "application/json",
    val timeoutMillis: Int = 30_000,
)

internal data class BusinessHttpResponse(
    val statusCode: Int,
    val body: ByteArray = ByteArray(0),
) {
    val bodyText: String
        get() = body.toString(Charsets.UTF_8)
}

internal fun interface BusinessHttpClient {
    suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse
}

internal class UrlConnectionBusinessHttpClient : BusinessHttpClient {
    override suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as? HttpURLConnection)
                ?: error("The URL did not create an HTTP connection.")
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.instanceFollowRedirects = true
                request.accept?.let { connection.setRequestProperty("Accept", it) }
                request.authorization?.let {
                    connection.setRequestProperty("Authorization", it)
                }
                request.contentType?.let {
                    connection.setRequestProperty("Content-Type", it)
                }
                request.body?.let { bytes ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                BusinessHttpResponse(
                    statusCode = status,
                    body = stream?.use { it.readBytes() } ?: ByteArray(0),
                )
            } finally {
                connection.disconnect()
            }
        }
}

internal fun encodedPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun urlWithQuery(baseUrl: String, parameters: Map<String, String>): String {
    val query = parameters.entries.joinToString("&") { (name, value) ->
        "${encodedQueryComponent(name)}=${encodedQueryComponent(value)}"
    }
    return URI.create("$baseUrl?$query").toASCIIString()
}

private fun encodedQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

// --- Merged from Models.kt ---
// Stubs for missing or inconsistently referenced models

data class BusinessLandmarkFailure(
    val landmarkId: String,
    val landmarkLabel: String,
    val error: String
)

data class BusinessBulkLandmarkFailure(
    val landmarkId: String,
    val landmarkLabel: String,
    val error: String
)

data class BusinessLandmarkUpdateResult(
    val landmarkId: String,
    val success: Boolean,
    val failedLandmarks: List<BusinessLandmarkFailure> = emptyList(),
    val successfulCount: Int = 0
)

data class BusinessBulkPromotionResult(
    val promotionName: String,
    val successfulLandmarkIds: Set<String>,
    val failedLandmarks: List<BusinessBulkLandmarkFailure>,
    val updatedLandmarks: List<BusinessLandmark>
)

data class BusinessBulkDeleteResult(
    val successfulLandmarkIds: Set<String>,
    val failedLandmarks: List<BusinessBulkLandmarkFailure>
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double
)

enum class UploadStage {
    IDLE, PREPARING, UPLOADING, COMPLETE, ERROR
}
