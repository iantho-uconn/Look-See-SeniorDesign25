package looksee.angelll.com.models

import java.time.Instant

data class BusinessMediaHistoryResponse(
    val landmarkId: String = "",
    val landmarkLabel: String = "",
    val items: List<BusinessMediaHistoryItem> = emptyList(),
    val count: Int = 0,
    val nextToken: String? = null,
)

data class BusinessMediaHistoryUploader(
    val displayName: String? = null,
    val email: String? = null,
    val userId: String? = null,
) {
    val displayText: String
        get() = displayName.nonBlank()
            ?: email.nonBlank()
            ?: userId.nonBlank()
            ?: "Unknown uploader"
}

data class BusinessMediaHistoryItem(
    val id: String = "",
    val submissionId: String = "",
    val batchId: String? = null,
    val datasetRole: String = "",
    val mediaKind: String = "",
    val originalFilename: String? = null,
    val contentType: String? = null,
    val status: String? = null,
    val uploadedBy: BusinessMediaHistoryUploader = BusinessMediaHistoryUploader(),
    val uploadedAt: Long = 0,
    val uploadedAtISO: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailSource: String? = null,
) {
    val isPositive: Boolean
        get() = datasetRole.equals("positive", ignoreCase = true)

    val isVideo: Boolean
        get() = mediaKind.equals("video", ignoreCase = true)

    val roleTitle: String
        get() = if (isPositive) "Positive" else "Negative"

    val mediaTitle: String
        get() = if (isVideo) "Video" else "Image"

    val roleAndMediaTitle: String
        get() = "$roleTitle • $mediaTitle"

    val normalizedStatus: String
        get() = status.nonBlank()?.replace('_', ' ')?.replace('-', ' ')
            ?.lowercase()?.split(' ')?.filter(String::isNotBlank)
            ?.joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
            ?: "Unknown"

    val uploadInstant: Instant
        get() = Instant.ofEpochSecond(uploadedAt)

    val displayFilename: String
        get() = originalFilename.nonBlank() ?: "Unnamed media"
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
