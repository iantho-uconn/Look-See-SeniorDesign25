package looksee.angelll.com.models

import android.util.Log
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class BusinessLandmarkServiceError(message: String) : Exception(message) {
    data object NotSignedIn : BusinessLandmarkServiceError("You must be signed in before managing landmarks.")
    data object TokensUnavailable : BusinessLandmarkServiceError("Cognito tokens were unavailable.")
    class BadStatus(val code: Int, val responseBody: String) : BusinessLandmarkServiceError("API error $code: $responseBody")
    data object InvalidUploadUrl : BusinessLandmarkServiceError("The upload URL returned by the server was invalid.")
    data object NoHardNegativeUploadTarget : BusinessLandmarkServiceError("The hard-negative upload request did not return an upload target.")
    data object InvalidResponse : BusinessLandmarkServiceError("The server returned an invalid response.")
    data object HardNegativeRetryFailed : BusinessLandmarkServiceError("The negative media could not be queued for processing again.")
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

sealed class BusinessMediaUploadTarget {
    data class Put(val url: String) : BusinessMediaUploadTarget()
    data class Post(val postData: S3PresignedPost) : BusinessMediaUploadTarget()
}

class BusinessMediaUploadTargetDeserializer : JsonDeserializer<BusinessMediaUploadTarget> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BusinessMediaUploadTarget {
        return if (json.isJsonPrimitive) {
            BusinessMediaUploadTarget.Put(json.asString)
        } else {
            val postData = context.deserialize<S3PresignedPost>(json, S3PresignedPost::class.java)
            BusinessMediaUploadTarget.Post(postData)
        }
    }
}

class BusinessLandmarkService internal constructor(
    private val tokenProvider: IdTokenProvider,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) : BusinessLandmarkDataSource, HardNegativeRetryDataSource {

    constructor() : this(
        AmplifyCognitoIdTokenProvider(),
        UrlConnectionBusinessHttpClient(),
        GsonBuilder()
            .registerTypeAdapter(BusinessMediaUploadTarget::class.java, BusinessMediaUploadTargetDeserializer())
            .create()
    )

    internal constructor(
        tokenProvider: IdTokenProvider,
        httpClient: BusinessHttpClient,
    ) : this(tokenProvider, httpClient, GsonBuilder().registerTypeAdapter(BusinessMediaUploadTarget::class.java, BusinessMediaUploadTargetDeserializer()).create())

    override suspend fun fetchBusinessLandmarks(): BusinessLandmarkListResponse =
        requestJson(
            method = "GET",
            url = "$LOOKSEE_API_BASE_URL/business/landmarks",
            responseType = BusinessLandmarkListResponse::class.java,
        )

    suspend fun updateShortDescription(landmarkId: String, shortDescription: String): BusinessLandmark = patchBusinessLandmark(
        landmarkId, BusinessLandmarkPatchBody(shortDescription = shortDescription),
    )

    suspend fun updateLandmarkSettings(landmarkId: String, isActive: Boolean? = null, promotionEnabled: Boolean? = null): BusinessLandmark = patchBusinessLandmark(
        landmarkId, BusinessLandmarkPatchBody(isActive = isActive, promotionEnabled = promotionEnabled),
    )

    suspend fun updateWebsiteUrl(landmarkId: String, websiteUrl: String): BusinessLandmark = patchBusinessLandmark(
        landmarkId, BusinessLandmarkPatchBody(websiteUrl = websiteUrl),
    )

    suspend fun deleteLandmark(landmarkId: String, confirmation: String): BusinessLandmarkDeleteResponse = requestJson(
        method = "DELETE", url = businessLandmarkUrl(landmarkId), body = BusinessLandmarkDeleteBody(confirmation), responseType = BusinessLandmarkDeleteResponse::class.java,
    )

    suspend fun uploadBusinessMedia(
        landmarkId: String, datasetRole: BusinessDatasetRole, mediaKind: BusinessMediaKind, filename: String, contentType: String, data: ByteArray,
    ): BusinessMediaUploadCompleteResponse = when (datasetRole) {
        BusinessDatasetRole.POSITIVE -> uploadPositiveBusinessMedia(landmarkId = landmarkId, datasetRole = datasetRole, mediaKind = mediaKind, filename = filename, contentType = contentType, data = data)
        BusinessDatasetRole.HARD_NEGATIVE -> uploadHardNegativeMedia(landmarkId = landmarkId, filename = filename, contentType = contentType, data = data)
    }

    // 🚀 THE FIX: Match iOS Global Negative specific /submissions endpoint perfectly
    suspend fun uploadGlobalNegativeVideo(file: java.io.File): BusinessMediaUploadCompleteResponse {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
        val contentType = if (file.extension.equals("mov", ignoreCase = true)) "video/quicktime" else "video/mp4"

        Log.d("LookSee_Debug_Upload", "[GLOBAL NEGATIVE] Initiating network request for: ${file.name}")
        val init = requestJson(
            method = "POST",
            url = "$LOOKSEE_API_BASE_URL/submissions/init",
            body = GlobalNegativeInitBody(filename = file.name, contentType = contentType),
            responseType = BusinessMediaUploadInitResponse::class.java
        )

        Log.d("LookSee_Debug_Upload", "[GLOBAL NEGATIVE] Got Presigned URL. Uploading payload to S3...")
        uploadToPresignedUrl(init.uploadUrl, contentType, file.name, bytes)

        Log.d("LookSee_Debug_Upload", "[GLOBAL NEGATIVE] S3 Upload Complete. Finalizing submission...")
        return requestJson(
            method = "POST",
            url = "$LOOKSEE_API_BASE_URL/submissions/complete",
            body = GlobalNegativeCompleteBody(submissionId = init.submissionId, s3Key = init.s3Key),
            responseType = BusinessMediaUploadCompleteResponse::class.java
        )
    }

    private suspend fun patchBusinessLandmark(landmarkId: String, body: BusinessLandmarkPatchBody): BusinessLandmark = requestJson(
        method = "PATCH", url = businessLandmarkUrl(landmarkId), body = body, responseType = BusinessLandmarkUpdateResponse::class.java,
    ).item

    private suspend fun uploadPositiveBusinessMedia(
        landmarkId: String, datasetRole: BusinessDatasetRole, mediaKind: BusinessMediaKind, filename: String, contentType: String, data: ByteArray,
    ): BusinessMediaUploadCompleteResponse {
        Log.d("LookSee_Debug_Upload", "Initiating POSITIVE upload for $filename")
        val init = requestJson(
            method = "POST",
            url = "${businessLandmarkUrl(landmarkId)}/uploads/init",
            body = PositiveUploadInitBody(mediaKind = mediaKind.wireValue, datasetRole = datasetRole.wireValue, filename = filename, contentType = contentType),
            responseType = BusinessMediaUploadInitResponse::class.java,
        )

        Log.d("LookSee_Debug_Upload", "Got Presigned URL. Uploading payload to S3...")
        uploadToPresignedUrl(init.uploadUrl, contentType, filename, data)

        Log.d("LookSee_Debug_Upload", "S3 Upload Complete. Finalizing submission...")
        return requestJson(
            method = "POST",
            url = "${businessLandmarkUrl(landmarkId)}/uploads/complete",
            body = PositiveUploadCompleteBody(init.submissionId, init.s3Key),
            responseType = BusinessMediaUploadCompleteResponse::class.java,
        )
    }

    private suspend fun uploadHardNegativeMedia(
        landmarkId: String, filename: String, contentType: String, data: ByteArray,
    ): BusinessMediaUploadCompleteResponse {
        Log.d("LookSee_Debug_Upload", "Initiating NEGATIVE upload for $filename")

        // 🚀 THE FIX: Omit the "/business" path component to match iOS
        val endpoint = "$LOOKSEE_API_BASE_URL/landmarks/${encodedPathSegment(landmarkId)}/hard-negatives"

        val init = requestJson(
            method = "POST",
            url = "$endpoint/init",
            body = BusinessHardNegativeInitBody(listOf(BusinessHardNegativeFileBody(filename, contentType))),
            responseType = BusinessHardNegativeInitResponse::class.java,
        )

        val uploadTarget = init.uploads.firstOrNull() ?: throw BusinessLandmarkServiceError.NoHardNegativeUploadTarget

        Log.d("LookSee_Debug_Upload", "Got Presigned URL for Negative. Uploading payload to S3...")
        uploadToPresignedUrl(uploadTarget.uploadUrl, uploadTarget.contentType, filename, data)

        Log.d("LookSee_Debug_Upload", "S3 Negative Upload Complete. Finalizing submission...")
        val completed = requestJson(
            method = "POST",
            url = "$endpoint/complete",
            body = BusinessHardNegativeCompleteBody(batchId = init.batchId, negativeIds = listOf(uploadTarget.negativeId)),
            responseType = BusinessHardNegativeCompleteResponse::class.java,
        )
        return BusinessMediaUploadCompleteResponse(
            ok = completed.failedCount == 0,
            submissionId = uploadTarget.negativeId,
            status = if (completed.failedCount == 0) { completed.processed?.firstOrNull()?.status ?: "PROCESSING" } else { "FAILED" },
            datasetRole = BusinessDatasetRole.HARD_NEGATIVE.wireValue,
            landmarkId = landmarkId,
            s3Key = uploadTarget.sourceKey,
        )
    }

    override suspend fun retryHardNegativeProcessing(landmarkId: String, batchId: String, negativeId: String): BusinessHardNegativeCompleteResponse {
        // 🚀 THE FIX: Omit the "/business" path component to match iOS
        val endpoint = "$LOOKSEE_API_BASE_URL/landmarks/${encodedPathSegment(landmarkId)}/hard-negatives/complete"
        val response = requestJson(
            method = "POST",
            url = endpoint,
            body = BusinessHardNegativeCompleteBody(batchId = batchId, negativeIds = listOf(negativeId), forceRetry = true),
            responseType = BusinessHardNegativeCompleteResponse::class.java,
        )
        if (response.failedCount != 0 || response.processedCount != 1) { throw BusinessLandmarkServiceError.HardNegativeRetryFailed }
        return response
    }

    private suspend fun uploadToPresignedUrl(
        uploadTarget: BusinessMediaUploadTarget,
        contentType: String,
        filename: String,
        data: ByteArray,
    ) {
        when (uploadTarget) {
            is BusinessMediaUploadTarget.Put -> {
                val response = httpClient.execute(
                    BusinessHttpRequest(
                        method = "PUT",
                        url = uploadTarget.url,
                        contentType = contentType,
                        accept = null,
                        timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
                        body = data
                    )
                )
                if (response.statusCode !in 200..299) {
                    Log.e("LookSee_Debug_Upload", "❌ S3 PUT FAILED! HTTP ${response.statusCode}: ${response.bodyText}")
                    throw BusinessLandmarkServiceError.BadStatus(response.statusCode, response.bodyText)
                } else {
                    Log.d("LookSee_Debug_Upload", "✅ S3 PUT SUCCESS! HTTP ${response.statusCode}")
                }
            }
            is BusinessMediaUploadTarget.Post -> {
                val uploadUrl = uploadTarget.postData
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
                bodyStream.write("\r\n".toByteArray(Charsets.UTF_8))
                bodyStream.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))

                val finalBytes = bodyStream.toByteArray()
                Log.d("LookSee_Debug_Upload", "Executing S3 POST to ${uploadUrl.url}. Payload size: ${finalBytes.size} bytes.")

                val response = httpClient.execute(
                    BusinessHttpRequest(
                        method = "POST",
                        url = uploadUrl.url,
                        contentType = multipartContentType,
                        accept = null,
                        timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
                        body = finalBytes
                    )
                )

                if (response.statusCode !in 200..299) {
                    Log.e("LookSee_Debug_Upload", "❌ S3 POST FAILED! HTTP ${response.statusCode}: ${response.bodyText}")
                    throw BusinessLandmarkServiceError.BadStatus(response.statusCode, response.bodyText)
                } else {
                    Log.d("LookSee_Debug_Upload", "✅ S3 POST SUCCESS! HTTP ${response.statusCode}")
                }
            }
        }
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

        if (response.statusCode !in 200..299) {
            Log.e("LookSee_Debug_Network", "❌ API ERROR ($method $url) - HTTP ${response.statusCode}: ${response.bodyText}")
        }

        validate(response)
        return try {
            gson.fromJson(response.bodyText, responseType)
                ?: throw BusinessLandmarkServiceError.InvalidResponse
        } catch (error: BusinessLandmarkServiceError) {
            throw error
        } catch (e: Exception) {
            Log.e("LookSee_Debug_Network", "Gson parse error: ${e.localizedMessage}", e)
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

    private data class GlobalNegativeInitBody(
        val filename: String,
        val mediaKind: String = "video",
        val contentType: String,
        val datasetRole: String = "global_negative",
        val label: String = "Global Negative Admin"
    )

    private data class GlobalNegativeCompleteBody(
        val submissionId: String,
        val s3Key: String,
        val datasetRole: String = "global_negative"
    )

    private companion object {
        const val MEDIA_UPLOAD_TIMEOUT_MILLIS = 300_000
    }
}

data class BusinessLandmarkListResponse(val items: List<BusinessLandmark> = emptyList(), val count: Int = 0)
data class BusinessLandmarkUpdateResponse(val ok: Boolean = false, val item: BusinessLandmark = BusinessLandmark())
data class BusinessLandmarkDeleteResponse(val ok: Boolean = false, val message: String? = null, val landmarkId: String = "", val status: String? = null)

data class BusinessLandmark(
    val landmarkId: String = "", val label: String = "", val shortDescription: String? = null, val websiteUrl: String? = null,
    val latitude: Double? = null, val longitude: Double? = null, val promotion: String? = null, val promotionEnabled: Boolean? = null,
    val isActive: Boolean? = null, val userEmail: String? = null, val ownerUserId: String? = null, val createdByUserId: String? = null,
    val createdAt: String? = null, val updatedAt: String? = null, val ownershipUpdatedAt: String? = null,
    val status: String? = null, val cleanFrameCount: Int? = null, val requiredFrames: Int? = null, val secondsNeeded: Int? = null,
) {
    val id: String get() = landmarkId
    val displayDescription: String get() = shortDescription?.trim()?.takeIf(String::isNotEmpty) ?: "No description available."
    val displayStatus: String get() = when (status) {
        "NEEDS_MORE_MEDIA" -> "Action Needed"
        "PREPARING_DATA" -> "Preparing Data"
        "PENDING_TRAINING" -> "Waiting for Training"
        "TRAINING_MODEL" -> "In Training"
        "OPTIMIZING_MODEL" -> "Optimizing for Android"
        else -> if (isActive == false) "Inactive" else "Active"
    }
    val isProcessing: Boolean get() = status == "PREPARING_DATA" || status == "PENDING_TRAINING" || status == "TRAINING_MODEL" || status == "OPTIMIZING_MODEL"
}

data class BusinessPromotionListResponse(val items: List<BusinessPromotion> = emptyList(), val count: Int = 0)
data class BusinessPromotionMutationResponse(val ok: Boolean = false, val item: BusinessPromotion = BusinessPromotion())
data class BusinessPromotionDeleteResponse(val ok: Boolean = false, val promotionId: String = "", val message: String? = null)
data class BusinessPromotion(
    val promotionId: String = "", val userEmail: String = "", val ownerUserId: String = "", val landmarkId: String = "",
    val landmarkLabel: String = "", val name: String = "", val description: String = "", val imageUrl: String = "",
    val startDate: String = "", val endDate: String = "", val enabled: Boolean = false, val createdAt: Long? = null, val updatedAt: Long? = null,
) { val id: String get() = promotionId }

enum class BusinessDatasetRole(val wireValue: String, val displayName: String, val successMessage: String, val filenameComponent: String) {
    POSITIVE("positive", "Positive Media", "Positive media uploaded successfully.", "positive"),
    HARD_NEGATIVE("hard-negative", "Negative Examples", "Negative example uploaded successfully.", "hard_negative"),
}

enum class BusinessMediaKind(val wireValue: String) { PHOTO("photo"), VIDEO("video") }

data class BusinessMediaUploadInitResponse(val submissionId: String = "", val uploadUrl: BusinessMediaUploadTarget, val s3Key: String = "", val bucket: String? = null, val datasetRole: String = "", val mediaKind: String = "", val landmarkId: String = "")
data class BusinessMediaUploadCompleteResponse(val ok: Boolean = false, val submissionId: String = "", val status: String? = null, val datasetRole: String? = null, val mediaKind: String? = null, val landmarkId: String? = null, val s3Key: String? = null)
internal data class BusinessHardNegativeInitResponse(val message: String? = null, val batchId: String = "", val landmarkId: String = "", val landmarkLabel: String? = null, val landmarkFolder: String? = null, val expiresInSeconds: Int? = null, val uploads: List<BusinessHardNegativeUploadTarget> = emptyList())
internal data class BusinessHardNegativeUploadTarget(val negativeId: String = "", val uploadUrl: BusinessMediaUploadTarget, val sourceBucket: String? = null, val sourceKey: String = "", val contentType: String = "")
data class BusinessHardNegativeCompleteResponse(val message: String? = null, val landmarkId: String = "", val batchId: String = "", val processedCount: Int = 0, val failedCount: Int = 0, val processed: List<BusinessHardNegativeProcessedItem>? = null)
data class BusinessHardNegativeProcessedItem(val negativeId: String = "", val status: String = "")

sealed class BusinessPromotionEditorContext {
    abstract val navigationTitle: String
    abstract val saveButtonTitle: String
    abstract val existingPromotion: BusinessPromotion?
    data class Create(override val navigationTitle: String = "New Promotion", override val saveButtonTitle: String = "Add", override val existingPromotion: BusinessPromotion? = null) : BusinessPromotionEditorContext()
    data class Edit(val promotion: BusinessPromotion, override val navigationTitle: String = "Edit Promotion", override val saveButtonTitle: String = "Save", override val existingPromotion: BusinessPromotion? = promotion) : BusinessPromotionEditorContext()
}

internal const val LOOKSEE_API_BASE_URL = "https://d11vl3v9w133rh.cloudfront.net"

sealed class BusinessAuthenticationError(message: String) : Exception(message) {
    data object NotSignedIn : BusinessAuthenticationError("You must be signed in to use this feature.")
    data object TokensUnavailable : BusinessAuthenticationError("Cognito tokens were unavailable.")
}

fun interface IdTokenProvider { suspend fun idToken(): String }
class AmplifyCognitoIdTokenProvider : IdTokenProvider {
    override suspend fun idToken(): String {
        val session = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession ?: throw BusinessAuthenticationError.TokensUnavailable
        if (!session.isSignedIn) throw BusinessAuthenticationError.NotSignedIn
        return session.userPoolTokensResult.value?.idToken?.takeIf(String::isNotBlank) ?: throw BusinessAuthenticationError.TokensUnavailable
    }
}

internal data class BusinessHttpRequest(val method: String, val url: String, val authorization: String? = null, val body: ByteArray? = null, val contentType: String? = null, val accept: String? = "application/json", val timeoutMillis: Int = 30_000)
internal data class BusinessHttpResponse(val statusCode: Int, val body: ByteArray = ByteArray(0)) { val bodyText: String get() = body.toString(Charsets.UTF_8) }

internal fun interface BusinessHttpClient { suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse }

internal class UrlConnectionBusinessHttpClient : BusinessHttpClient {
    override suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as? HttpURLConnection) ?: error("The URL did not create an HTTP connection.")
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.instanceFollowRedirects = true
                request.accept?.let { connection.setRequestProperty("Accept", it) }
                request.authorization?.let { connection.setRequestProperty("Authorization", it) }
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }

                request.body?.let { bytes ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) { connection.inputStream } else { connection.errorStream }
                BusinessHttpResponse(statusCode = status, body = stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                connection.disconnect()
            }
        }
}

fun urlWithQuery(baseUrl: String, parameters: Map<String, String>): String {
    val query = parameters.entries.joinToString("&") { (name, value) ->
        "${encodedQueryComponent(name)}=${encodedQueryComponent(value)}"
    }
    return URI.create("$baseUrl?$query").toASCIIString()
}

fun encodedQueryComponent(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
fun encodedPathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")