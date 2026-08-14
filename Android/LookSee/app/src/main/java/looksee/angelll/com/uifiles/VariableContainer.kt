package looksee.angelll.com.uifiles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object VariableContainer {

    val infoView = MutableStateFlow(false)
    val bboxCounter = MutableStateFlow(0)

    val landmarkName = MutableStateFlow("Not available")
    val landmarkConfidence = MutableStateFlow(0f)
    val landmarkCategory = MutableStateFlow("Not available")
    val landmarkDescription = MutableStateFlow("No description is available for this landmark.")
    val landmarkURL = MutableStateFlow("")
    val landmarkWebsiteUrl = MutableStateFlow("")

    val landmarkId = MutableStateFlow("")
    val landmarkClassIndex = MutableStateFlow<Int?>(null)
    val landmarkClusterId = MutableStateFlow<Int?>(null)
    val landmarkTrainingRunId = MutableStateFlow("")
    val landmarkDatasetClassName = MutableStateFlow("")

    val promoName = MutableStateFlow("No active promotion")
    val promoDescription = MutableStateFlow("")
    val promoImageUrl = MutableStateFlow("")

    val merchantName = MutableStateFlow("")
    val merchantBio = MutableStateFlow("")
    val merchantPhone = MutableStateFlow("")
    val merchantLogoUrl = MutableStateFlow("")

    init {
        resetLandmarkDisplay()
    }

    fun presentLandmark(
        context: Context,
        entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
        landmarkId.value = entry.landmarkId
        landmarkClassIndex.value = entry.classIndex
        landmarkClusterId.value = clusterId
        landmarkTrainingRunId.value = trainingRunId
        landmarkDatasetClassName.value = entry.datasetClassName

        landmarkName.value = entry.label

        val trimmedDescription = entry.shortDescription.trim()

        // Replaced with Kotlin's idiomatic ifEmpty
        landmarkDescription.value = trimmedDescription.ifEmpty {
            "No description is available for this landmark."
        }

        landmarkCategory.value = entry.datasetClassName.replace("_", " ")

        val clampedConfidence = max(0f, min(detectionConfidence, 1f))
        landmarkConfidence.value = clampedConfidence * 100f

        landmarkWebsiteUrl.value = ""
        promoName.value = "No active promotion"
        promoDescription.value = ""
        promoImageUrl.value = ""
        landmarkURL.value = ""

        val cacheKey = "cached_merchant_${entry.landmarkId}"
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val cachedDataStr = prefs.getString(cacheKey, null)

        if (cachedDataStr != null) {
            try {
                val json = JSONObject(cachedDataStr)
                merchantName.value = json.optString("merchantName", "")
                merchantBio.value = json.optString("merchantBio", "")
                merchantPhone.value = json.optString("merchantPhone", "")
                merchantLogoUrl.value = json.optString("merchantLogoUrl", "")
            } catch (_: Exception) {
                merchantName.value = ""
                merchantBio.value = ""
                merchantPhone.value = ""
                merchantLogoUrl.value = ""
            }
        } else {
            merchantName.value = ""
            merchantBio.value = ""
            merchantPhone.value = ""
            merchantLogoUrl.value = ""
        }

        infoView.value = true

        val logMessage = """
        ✅ [Local Manifest Popup] Presenting landmark
            clusterId: $clusterId
            trainingRunId: $trainingRunId
            classIndex: ${entry.classIndex}
            landmarkId: ${entry.landmarkId}
            label: ${entry.label}
            confidence: ${String.format(Locale.US, "%.1f", landmarkConfidence.value)}%
        """.trimIndent()

        Log.d("VariableContainer", logMessage)
    }

    fun dismissLandmark() {
        infoView.value = false
        landmarkURL.value = ""
    }

    fun resetLandmarkDisplay() {
        infoView.value = false
        bboxCounter.value = 0

        landmarkName.value = ""
        landmarkConfidence.value = 0.00f
        landmarkCategory.value = "Not available"
        landmarkDescription.value = "No description is available for this landmark."
        landmarkURL.value = ""
        landmarkWebsiteUrl.value = ""

        landmarkId.value = ""
        landmarkClassIndex.value = null
        landmarkClusterId.value = null
        landmarkTrainingRunId.value = ""
        landmarkDatasetClassName.value = ""

        promoName.value = "No active promotion"
        promoDescription.value = ""
        promoImageUrl.value = ""

        merchantName.value = ""
        merchantBio.value = ""
        merchantPhone.value = ""
        merchantLogoUrl.value = ""
    }

    fun getLandmarkName(): String {
        return landmarkName.value
    }
}