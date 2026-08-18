package looksee.angelll.com.models

import com.google.gson.Gson
import java.net.URL

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
}

fun interface BusinessLandmarkDataSource {
    suspend fun fetchBusinessLandmarks(): BusinessLandmarkListResponse
}

class BusinessLandmarkService internal constructor(
    private val tokenProvider: IdTokenProvider,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
) : BusinessLandmarkDataSource {
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
        uploadToPresignedUrl(init.uploadUrl, contentType, data)
        return requestJson(
            method = "POST",
            url = "${businessLandmarkUrl(landmarkId)}/uploads/complete",
            body = PositiveUploadCompleteBody(init.submissionId, init.s3Key),
            responseType = BusinessMediaUploadCompleteResponse::class.java,
        )
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
        uploadToPresignedUrl(uploadTarget.uploadUrl, uploadTarget.contentType, data)
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
            status = if (completed.failedCount == 0) "PROCESSING" else "FAILED",
            datasetRole = BusinessDatasetRole.HARD_NEGATIVE.wireValue,
            landmarkId = landmarkId,
            s3Key = uploadTarget.sourceKey,
        )
    }

    private suspend fun uploadToPresignedUrl(
        uploadUrl: String,
        contentType: String,
        data: ByteArray,
    ) {
        try {
            URL(uploadUrl)
        } catch (_: Exception) {
            throw BusinessLandmarkServiceError.InvalidUploadUrl
        }
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "PUT",
                url = uploadUrl,
                body = data,
                contentType = contentType,
                accept = null,
                timeoutMillis = MEDIA_UPLOAD_TIMEOUT_MILLIS,
            ),
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
    )

    private companion object {
        const val MEDIA_UPLOAD_TIMEOUT_MILLIS = 300_000
    }
}
