package looksee.angelll.com.models

import android.net.Uri
import java.io.File

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
