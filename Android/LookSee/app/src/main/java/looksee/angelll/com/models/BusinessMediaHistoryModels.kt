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
        get() = sequenceOf(displayName, email, userId)
            .mapNotNull { it.nonBlank() }
            .firstOrNull()
            ?: "Unknown uploader"
}

enum class BusinessMediaLifecycleState(val displayTitle: String) {
    PROCESSING("Processing"),
    READY("Ready"),
    FAILED("Failed"),
    UNKNOWN("Unknown");

    companion object {
        fun fromBackend(value: String?): BusinessMediaLifecycleState =
            when (value.normalizedBackendValue()) {
                "ready", "complete", "completed", "success", "succeeded" -> READY
                "processing", "retrying", "pending", "upload pending", "initiated",
                "uploaded",
                -> PROCESSING
                "failed", "failure", "error", "rejected" -> FAILED
                else -> UNKNOWN
            }
    }
}

enum class BusinessMediaHistoryRole(val displayTitle: String) {
    POSITIVE("Positive"),
    HARD_NEGATIVE("Negative"),
    UNKNOWN("Unknown role");

    companion object {
        fun fromBackend(value: String): BusinessMediaHistoryRole = when (
            value.trim().lowercase()
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
        ) {
            "positive" -> POSITIVE
            "negative", "hardnegative" -> HARD_NEGATIVE
            else -> UNKNOWN
        }
    }
}

enum class BusinessMediaHistoryKind(
    val displayTitle: String,
    val iconKey: String,
) {
    VIDEO("Video", "video"),
    PHOTO("Image", "photo"),
    UNKNOWN("Unknown media", "unknown");

    companion object {
        fun fromBackend(value: String): BusinessMediaHistoryKind = when (
            value.trim().lowercase()
        ) {
            "video", "movie" -> VIDEO
            "photo", "image" -> PHOTO
            else -> UNKNOWN
        }
    }
}

data class BusinessMediaHistoryItem(
    val id: String = "",
    val submissionId: String = "",
    val batchId: String? = null,
    val datasetRole: String = "",
    val mediaKind: String = "",
    val originalFilename: String? = null,
    val contentType: String? = null,
    /** Canonical lifecycle status from the newest history API. */
    val status: String? = null,
    /** Original writer status retained for diagnostics. */
    val rawStatus: String? = null,
    val uploadedBy: BusinessMediaHistoryUploader = BusinessMediaHistoryUploader(),
    val uploadedAt: Long? = null,
    val uploadedAtISO: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailSource: String? = null,
    val retryCount: Int? = null,
    val lastRetryAt: Long? = null,
    val failureReason: String? = null,
) {
    val role: BusinessMediaHistoryRole
        get() = BusinessMediaHistoryRole.fromBackend(datasetRole)

    val kind: BusinessMediaHistoryKind
        get() = BusinessMediaHistoryKind.fromBackend(mediaKind)

    val lifecycleState: BusinessMediaLifecycleState
        get() = BusinessMediaLifecycleState.fromBackend(status ?: rawStatus)

    val isPositive: Boolean
        get() = role == BusinessMediaHistoryRole.POSITIVE

    val isVideo: Boolean
        get() = kind == BusinessMediaHistoryKind.VIDEO

    val roleAndMediaTitle: String
        get() = "${role.displayTitle} • ${kind.displayTitle}"

    /** Compatibility aliases retained for already-translated UI code. */
    val roleTitle: String
        get() = role.displayTitle

    val mediaTitle: String
        get() = kind.displayTitle

    val mediaIconKey: String
        get() = kind.iconKey

    val normalizedStatus: String
        get() = lifecycleState.displayTitle

    val backendStatusText: String
        get() = (rawStatus ?: status).nonBlank() ?: "unknown"

    val uploadInstant: Instant?
        get() {
            uploadedAt?.takeIf { it > 0 }?.let { return Instant.ofEpochSecond(it) }
            return uploadedAtISO.nonBlank()?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
            }
        }

    val lastRetryInstant: Instant?
        get() = lastRetryAt?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)

    val isProcessingDelayed: Boolean
        get() = isProcessingDelayedAt(Instant.now())

    fun isProcessingDelayedAt(now: Instant): Boolean {
        if (lifecycleState != BusinessMediaLifecycleState.PROCESSING) return false
        val activity = lastRetryInstant ?: uploadInstant ?: return false
        return !now.isBefore(activity.plusSeconds(PROCESSING_DELAY_SECONDS))
    }

    val canRetryProcessing: Boolean
        get() = canRetryProcessingAt(Instant.now())

    fun canRetryProcessingAt(now: Instant): Boolean {
        if (role != BusinessMediaHistoryRole.HARD_NEGATIVE ||
            batchId.nonBlank() == null || submissionId.isBlank()
        ) {
            return false
        }
        return lifecycleState == BusinessMediaLifecycleState.FAILED ||
            isProcessingDelayedAt(now)
    }

    val displayStatus: String
        get() = if (isProcessingDelayed) "Delayed" else lifecycleState.displayTitle

    val displayFilename: String
        get() = originalFilename.nonBlank() ?: "Unnamed media"

    private companion object {
        const val PROCESSING_DELAY_SECONDS = 60L * 60L
    }
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.normalizedBackendValue(): String = this
    ?.trim()
    ?.replace('_', ' ')
    ?.replace('-', ' ')
    ?.lowercase()
    .orEmpty()
