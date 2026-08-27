package looksee.angelll.com.models

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
        get() = when {
            status == "NEEDS_MORE_MEDIA" -> "Action Needed"
            isActive == false -> "Inactive"
            else -> "Active"
        }

    val displayPromotionStatus: String
        get() = if (promotionEnabled == true) {
            "Promotion enabled"
        } else {
            "No active promotion"
        }
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
    val uploadUrl: String = "",
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
    val uploadUrl: String = "",
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
