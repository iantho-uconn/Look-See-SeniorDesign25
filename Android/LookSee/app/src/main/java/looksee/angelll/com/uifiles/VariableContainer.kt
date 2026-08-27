package looksee.angelll.com.uifiles // Added your requested package name

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared display state used by the landmark information popup.
 *
 * The manifest-based detection flow resolves a `LandmarkManifestEntry`, then
 * calls `presentLandmark(...)` to populate the same fields already consumed by
 * `LandmarkInfo` and `PopUp`.
 */
class VariableContainer private constructor() {

    companion object {
        val shared = VariableContainer()

        // Hold a reference to the app context for SharedPreferences access
        private var appContext: Context? = null

        fun initialize(context: Context) {
            appContext = context.applicationContext
        }
    }

    var infoView by mutableStateOf(false)
    var bboxCounter by mutableStateOf(0)

    var landmarkName by mutableStateOf("Not available")
    var landmarkConfidence by mutableStateOf(0.00f)
    var landmarkCategory by mutableStateOf("Not available")
    var landmarkDescription by mutableStateOf("No description is available for this landmark.")
    var landmarkURL by mutableStateOf("")
    var landmarkWebsiteUrl by mutableStateOf("")

    // Manifest/debugging identity. These are useful when confirming that a
    // popup was resolved from the same cluster release as the active model.
    var landmarkId by mutableStateOf("")
    var landmarkClassIndex by mutableStateOf<Int?>(null)
    var landmarkClusterId by mutableStateOf<Int?>(null)
    var landmarkTrainingRunId by mutableStateOf("")
    var landmarkDatasetClassName by mutableStateOf("")

    var promoName by mutableStateOf("No active promotion")
    var promoDescription by mutableStateOf("")
    var promoImageUrl by mutableStateOf("")

    // 🚀 NEW: Enterprise Merchant Profile Fields
    var merchantName by mutableStateOf("")
    var merchantBio by mutableStateOf("")
    var merchantPhone by mutableStateOf("")
    var merchantWebsite by mutableStateOf("")
    var merchantAddress by mutableStateOf("")
    var merchantLogoUrl by mutableStateOf("")

    init {
        resetLandmarkDisplay()
    }

    /**
     * Populates and opens the popup using information resolved from a local
     * cluster landmark manifest.
     *
     * @param entry The manifest entry associated with the detected class index.
     * @param clusterId Cluster whose model produced the detection.
     * @param trainingRunId Immutable release identifier paired with the model.
     * @param detectionConfidence Raw model confidence in the range 0...1.
     */
    fun presentLandmark(
        entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
        landmarkId = entry.landmarkId
        landmarkClassIndex = entry.classIndex
        landmarkClusterId = clusterId
        landmarkTrainingRunId = trainingRunId
        landmarkDatasetClassName = entry.datasetClassName

        landmarkName = entry.label

        val trimmedDescription = entry.shortDescription.trim()

        landmarkDescription = if (trimmedDescription.isEmpty()) {
            "No description is available for this landmark."
        } else {
            trimmedDescription
        }

        // Keep the existing UI field populated without exposing the S3 folder
        // formatting directly to the user.
        landmarkCategory = entry.datasetClassName.replace("_", " ")

        val clampedConfidence = detectionConfidence.coerceIn(0f, 1f)
        landmarkConfidence = clampedConfidence * 100

        // Live website/promotion data is fetched from the backend after the
        // popup opens. Reset these so a previous landmark cannot leak into the
        // newly displayed popup.
        landmarkWebsiteUrl = ""
        promoName = "No active promotion"
        promoDescription = ""
        promoImageUrl = ""
        landmarkURL = ""

        // 🚀 INSTANT CACHE LOAD: Load cached merchant details immediately to eliminate delay
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("LookSeeMerchantCache", Context.MODE_PRIVATE)
            val cacheKeyPrefix = "cached_merchant_${entry.landmarkId}_"

            merchantName = prefs.getString("${cacheKeyPrefix}merchantName", "") ?: ""
            merchantBio = prefs.getString("${cacheKeyPrefix}merchantBio", "") ?: ""
            merchantPhone = prefs.getString("${cacheKeyPrefix}merchantPhone", "") ?: ""
            merchantWebsite = prefs.getString("${cacheKeyPrefix}merchantWebsite", "") ?: ""
            merchantAddress = prefs.getString("${cacheKeyPrefix}merchantAddress", "") ?: ""
            merchantLogoUrl = prefs.getString("${cacheKeyPrefix}merchantLogoUrl", "") ?: ""
        } ?: run {
            merchantName = ""
            merchantBio = ""
            merchantPhone = ""
            merchantWebsite = ""
            merchantAddress = ""
            merchantLogoUrl = ""
        }

        infoView = true

        println("""
        ✅ [Local Manifest Popup] Presenting landmark
            clusterId: $clusterId
            trainingRunId: $trainingRunId
            classIndex: ${entry.classIndex}
            landmarkId: ${entry.landmarkId}
            label: ${entry.label}
            confidence: ${String.format("%.1f", landmarkConfidence)}%
        """.trimIndent())
    }

    /**
     * Closes the popup while retaining the most recently displayed landmark
     * values for diagnostics.
     */
    fun dismissLandmark() {
        infoView = false
        landmarkURL = ""
    }

    /**
     * Clears all landmark-specific state. This can be used when changing
     * active cluster releases or resetting the scan screen.
     */
    fun resetLandmarkDisplay() {
        infoView = false
        bboxCounter = 0

        landmarkName = ""
        landmarkConfidence = 0.00f
        landmarkCategory = "Not available"
        landmarkDescription = "No description is available for this landmark."
        landmarkURL = ""
        landmarkWebsiteUrl = ""

        landmarkId = ""
        landmarkClassIndex = null
        landmarkClusterId = null
        landmarkTrainingRunId = ""
        landmarkDatasetClassName = ""

        promoName = "No active promotion"
        promoDescription = ""
        promoImageUrl = ""

        merchantName = ""
        merchantBio = ""
        merchantPhone = ""
        merchantWebsite = ""
        merchantAddress = ""
        merchantLogoUrl = ""
    }
}

// Stub for LandmarkManifestEntry to prevent compiler errors
data class LandmarkManifestEntry(
    val landmarkId: String,
    val classIndex: Int,
    val datasetClassName: String,
    val label: String,
    val shortDescription: String
)
