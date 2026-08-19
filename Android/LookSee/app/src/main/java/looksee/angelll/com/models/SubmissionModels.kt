package looksee.angelll.com.models

import com.google.gson.annotations.SerializedName

/** The media categories accepted by the submissions API. */
enum class MediaKind {
    @SerializedName("video")
    VIDEO,

    @SerializedName("photo")
    PHOTO,
}

/** Request sent before uploading positive media to the returned URL. */
data class InitSubmissionRequest(
    val userEmail: String,
    val label: String,
    val landmarkId: String? = null,
    val mediaKind: MediaKind,
    val filename: String,
    val contentType: String,
)

/** Upload destination returned by the submission initialization endpoint. */
data class InitSubmissionResponse(
    val submissionId: String,
    val uploadUrl: String,
    val s3Key: String,
)

/** Request sent to /submissions/complete after the media PUT succeeds. */
data class CompleteSubmissionRequest(
    val submissionId: String,
    val s3Key: String,
    val userEmail: String,
    val label: String,
    val landmarkId: String?,
    val landmarkLabel: String?,
    val mediaKind: MediaKind,
    val shortDescription: String?,
    val userDescription: String?,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracy: Double?,
)

/** Local result returned after a positive submission completes. */
data class PositiveSubmissionResult(
    val submissionId: String,
    val landmarkId: String?,
    val mediaKind: MediaKind,
    val s3Key: String,
)
