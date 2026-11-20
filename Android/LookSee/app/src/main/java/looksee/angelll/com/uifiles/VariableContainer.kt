package looksee.angelll.com.uifiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import looksee.angelll.com.models.BusinessLandmark
import looksee.angelll.com.models.LandmarkManifestEntry
import looksee.angelll.com.models.NearbyLandmark

/**
 * Shared display state used by the landmark information popup, map filter, and AR scanner.
 */
class VariableContainer private constructor() {

    companion object {
        val shared = VariableContainer()

        @Volatile
        private var appContext: Context? = null

        @Volatile
        private var hasHydratedFromCache = false

        fun initialize(context: Context) {
            val ctx = context.applicationContext
            appContext = ctx
            if (!hasHydratedFromCache) {
                synchronized(this) {
                    if (!hasHydratedFromCache) {
                        shared.hydrateFromCache(ctx)
                        hasHydratedFromCache = true
                    }
                }
            }
        }

        fun isLandmarkInactive(landmarkId: String): Boolean {
            val id = landmarkId.trim()
            if (id.isEmpty()) return false
            if (shared.isLocallyModified(id)) {
                return shared.inactiveSnapshot.contains(id)
            }
            if (shared.inactiveSnapshot.contains(id)) return true
            val context = appContext
            if (context != null && looksee.angelll.com.models.SuppressionCache.shared(context).isInactive(id)) {
                return true
            }
            return shared.allLandmarks.firstOrNull { it.landmarkId.trim() == id }?.isActive == false
        }

        fun isLandmarkHiddenOnMap(landmarkId: String): Boolean {
            val id = landmarkId.trim()
            if (id.isEmpty()) return false
            if (shared.isLocallyModified(id)) {
                return shared.hiddenSnapshot.contains(id)
            }
            if (shared.hiddenSnapshot.contains(id)) return true
            val context = appContext
            if (context != null && looksee.angelll.com.models.SuppressionCache.shared(context).isHidden(id)) {
                return true
            }
            return shared.allLandmarks.firstOrNull { it.landmarkId.trim() == id }?.isVisibleOnMap == false
        }
    }

    fun isLocallyModified(landmarkId: String): Boolean {
        val id = landmarkId.trim()
        if (id.isEmpty()) return false
        if (locallyModifiedSnapshot.contains(id)) return true
        val context = appContext ?: return false
        return looksee.angelll.com.models.SuppressionCache.shared(context).isLocallyModified(id)
    }

    var infoView by mutableStateOf(false)
    var bboxCounter by mutableStateOf(0)

    var landmarkName by mutableStateOf("Not available")
    var landmarkConfidence by mutableStateOf(0.00f)
    var landmarkCategory by mutableStateOf("Not available")
    var landmarkDescription by mutableStateOf("No description is available for this landmark.")
    var landmarkURL by mutableStateOf("")
    var landmarkWebsiteUrl by mutableStateOf("")

    var landmarkId by mutableStateOf("")
    var landmarkClassIndex by mutableStateOf<Int?>(null)
    var landmarkClusterId by mutableStateOf<Int?>(null)
    var landmarkTrainingRunId by mutableStateOf("")
    var landmarkDatasetClassName by mutableStateOf("")

    var promoName by mutableStateOf("No active promotion")
    var promoDescription by mutableStateOf("")
    var promoImageUrl by mutableStateOf("")

    var merchantName by mutableStateOf("")
    var merchantBio by mutableStateOf("")
    var merchantPhone by mutableStateOf("")
    var merchantWebsite by mutableStateOf("")
    var merchantAddress by mutableStateOf("")
    var merchantLogoUrl by mutableStateOf("")

    var isMapPin by mutableStateOf(false)
    var mapLatitude by mutableStateOf<Double?>(null)
    var mapLongitude by mutableStateOf<Double?>(null)
    var reportedOwnerId by mutableStateOf<String?>(null)

    private var isUpdatingNearbyLocally = false

    @Volatile
    private var inactiveSnapshot: Set<String> = emptySet()

    @Volatile
    private var hiddenSnapshot: Set<String> = emptySet()

    @Volatile
    private var locallyModifiedSnapshot: Set<String> = emptySet()

    var allLandmarks: List<NearbyLandmark> = emptyList()
        set(value) {
            field = value
            if (!isUpdatingNearbyLocally) {
                syncSuppressionSetsFromNearby()
            }
        }

    var inactiveLandmarkIds by mutableStateOf<Set<String>>(emptySet())
    var hiddenMapLandmarkIds by mutableStateOf<Set<String>>(emptySet())
    private var locallyModifiedIds = mutableSetOf<String>()

    init {
        resetLandmarkDisplay()
    }

    private fun hydrateFromCache(ctx: Context) {
        val cache = looksee.angelll.com.models.SuppressionCache.shared(ctx)
        val cachedInactive = cache.getInitialInactive()
        val cachedHidden = cache.getInitialHidden()
        val cachedModified = cache.getInitialModified()

        val mergedModified = (cachedModified + locallyModifiedIds).toMutableSet()
        val mergedInactive = cachedInactive.toMutableSet()
        val mergedHidden = cachedHidden.toMutableSet()

        for (id in locallyModifiedIds) {
            if (inactiveLandmarkIds.contains(id)) mergedInactive.add(id) else mergedInactive.remove(id)
            if (hiddenMapLandmarkIds.contains(id)) mergedHidden.add(id) else mergedHidden.remove(id)
        }

        locallyModifiedIds = mergedModified
        inactiveLandmarkIds = mergedInactive
        hiddenMapLandmarkIds = mergedHidden
        inactiveSnapshot = mergedInactive.toSet()
        hiddenSnapshot = mergedHidden.toSet()
        locallyModifiedSnapshot = mergedModified.toSet()
    }

    private fun persistSuppressionSets() {
        inactiveSnapshot = inactiveLandmarkIds.toSet()
        hiddenSnapshot = hiddenMapLandmarkIds.toSet()
        locallyModifiedSnapshot = locallyModifiedIds.toSet()

        val context = appContext ?: return
        looksee.angelll.com.models.SuppressionCache.shared(context).update(
            inactive = inactiveSnapshot,
            hidden = hiddenSnapshot,
            modified = locallyModifiedSnapshot
        )
    }

    fun syncSuppressionSetsFromNearby() {
        val updatedInactive = inactiveLandmarkIds.toMutableSet()
        val updatedHidden = hiddenMapLandmarkIds.toMutableSet()
        val updatedModified = locallyModifiedIds.toMutableSet()

        for (item in allLandmarks) {
            val id = item.landmarkId.trim()
            if (id.isEmpty()) continue

            if (updatedModified.contains(id)) {
                val localIsActive = !updatedInactive.contains(id)
                val localIsVisible = !updatedHidden.contains(id)
                if (item.isActive == localIsActive && (item.isVisibleOnMap == null || item.isVisibleOnMap == localIsVisible)) {
                    updatedModified.remove(id)
                } else {
                    continue
                }
            }

            if (item.isActive == false) {
                updatedInactive.add(id)
            } else if (item.isActive == true) {
                updatedInactive.remove(id)
            }

            if (item.isVisibleOnMap == false) {
                updatedHidden.add(id)
            } else if (item.isVisibleOnMap == true) {
                updatedHidden.remove(id)
            }
        }

        inactiveLandmarkIds = updatedInactive
        hiddenMapLandmarkIds = updatedHidden
        locallyModifiedIds = updatedModified
        persistSuppressionSets()
    }

    fun syncFromBusinessLandmarks(landmarks: List<BusinessLandmark>) {
        if (landmarks.isEmpty()) return

        val updatedInactive = inactiveLandmarkIds.toMutableSet()
        val updatedHidden = hiddenMapLandmarkIds.toMutableSet()
        val updatedModified = locallyModifiedIds.toMutableSet()

        for (item in landmarks) {
            val id = item.landmarkId.trim()
            if (id.isEmpty()) continue

            val backendActive = item.isActive ?: true
            val backendVisible = item.isVisibleOnMap ?: backendActive

            if (updatedModified.contains(id)) {
                val localIsActive = !updatedInactive.contains(id)
                if (backendActive == localIsActive) {
                    updatedModified.remove(id)
                } else {
                    continue
                }
            }

            if (!backendActive) {
                updatedInactive.add(id)
            } else {
                updatedInactive.remove(id)
            }

            if (!backendVisible) {
                updatedHidden.add(id)
            } else {
                updatedHidden.remove(id)
            }
        }

        inactiveLandmarkIds = updatedInactive
        hiddenMapLandmarkIds = updatedHidden
        locallyModifiedIds = updatedModified
        persistSuppressionSets()
    }

    fun setLandmarkState(landmarkId: String, isActive: Boolean, isVisibleOnMap: Boolean) {
        val id = landmarkId.trim()
        if (id.isEmpty()) return

        locallyModifiedIds.add(id)

        val updatedInactive = inactiveLandmarkIds.toMutableSet()
        if (isActive) {
            updatedInactive.remove(id)
        } else {
            updatedInactive.add(id)
            if (this.landmarkId == id) {
                dismissLandmark()
            }
        }
        inactiveLandmarkIds = updatedInactive

        val updatedHidden = hiddenMapLandmarkIds.toMutableSet()
        if (isVisibleOnMap) {
            updatedHidden.remove(id)
        } else {
            updatedHidden.add(id)
        }
        hiddenMapLandmarkIds = updatedHidden

        if (allLandmarks.any { it.landmarkId == id }) {
            isUpdatingNearbyLocally = true
            try {
                allLandmarks = allLandmarks.map { item ->
                    if (item.landmarkId == id) {
                        item.copy(isActive = isActive, isVisibleOnMap = isVisibleOnMap)
                    } else {
                        item
                    }
                }
            } finally {
                isUpdatingNearbyLocally = false
            }
        }

        persistSuppressionSets()
    }

    fun presentLandmark(
        entry: LandmarkManifestEntry,
        clusterId: Int,
        trainingRunId: String,
        detectionConfidence: Float
    ) {
        if (isLandmarkInactive(entry.landmarkId)) return

        resetLandmarkDisplay()

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

        landmarkCategory = entry.datasetClassName.replace("_", " ")

        val clampedConfidence = detectionConfidence.coerceIn(0f, 1f)
        landmarkConfidence = clampedConfidence * 100

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("LookSeeMerchantCache", Context.MODE_PRIVATE)
            val cacheKeyPrefix = "cached_merchant_${entry.landmarkId}_"

            merchantName = prefs.getString("${cacheKeyPrefix}merchantName", "") ?: ""
            merchantBio = prefs.getString("${cacheKeyPrefix}merchantBio", "") ?: ""
            merchantPhone = prefs.getString("${cacheKeyPrefix}merchantPhone", "") ?: ""
            merchantWebsite = prefs.getString("${cacheKeyPrefix}merchantWebsite", "") ?: ""
            merchantAddress = prefs.getString("${cacheKeyPrefix}merchantAddress", "") ?: ""
            merchantLogoUrl = prefs.getString("${cacheKeyPrefix}merchantLogoUrl", "") ?: ""
        }

        infoView = true

        println(
            """
            ✅ [Local Manifest Popup] Presenting landmark
                clusterId: $clusterId
                trainingRunId: $trainingRunId
                classIndex: ${entry.classIndex}
                landmarkId: ${entry.landmarkId}
                label: ${entry.label}
                confidence: ${String.format("%.1f", landmarkConfidence)}%
            """.trimIndent()
        )
    }

    fun presentNearbyLandmark(
        landmark: NearbyLandmark,
        allowInactivePreview: Boolean = false
    ) {
        if (!allowInactivePreview && isLandmarkInactive(landmark.landmarkId)) {
            return
        }

        resetLandmarkDisplay()

        landmarkId = landmark.landmarkId
        landmarkName = landmark.label
        landmarkDescription = landmark.shortDescription.ifEmpty { "No description is available for this landmark." }

        isMapPin = true
        mapLatitude = landmark.latitude
        mapLongitude = landmark.longitude
        reportedOwnerId = landmark.createdBy

        landmarkWebsiteUrl = landmark.websiteUrl ?: ""

        promoName = if (landmark.promotionEnabled) {
            landmark.promoName ?: landmark.promotion ?: "Special Promotion Available!"
        } else {
            "No active promotion"
        }
        promoDescription = if (landmark.promotionEnabled) landmark.promoDescription ?: "" else ""
        promoImageUrl = if (landmark.promotionEnabled) landmark.promoImageUrl ?: "" else ""

        val prefs = appContext?.getSharedPreferences("LookSeeMerchantCache", Context.MODE_PRIVATE)
        val cacheKeyPrefix = "cached_merchant_${landmark.landmarkId}_"

        val resolvedName = landmark.merchantName?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs?.getString("${cacheKeyPrefix}merchantName", "")?.trim().orEmpty()
        val resolvedBio = landmark.merchantBio?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs?.getString("${cacheKeyPrefix}merchantBio", "")?.trim().orEmpty()
        val resolvedPhone = landmark.merchantPhone?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs?.getString("${cacheKeyPrefix}merchantPhone", "")?.trim().orEmpty()
        val resolvedAddress = landmark.merchantAddress?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs?.getString("${cacheKeyPrefix}merchantAddress", "")?.trim().orEmpty()
        val resolvedLogo = landmark.merchantLogoUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs?.getString("${cacheKeyPrefix}merchantLogoUrl", "")?.trim().orEmpty()

        merchantName = resolvedName
        merchantBio = resolvedBio
        merchantPhone = resolvedPhone
        merchantAddress = resolvedAddress
        merchantLogoUrl = resolvedLogo

        if (prefs != null && (resolvedName.isNotEmpty() || resolvedLogo.isNotEmpty())) {
            prefs.edit()
                .putString("${cacheKeyPrefix}merchantName", resolvedName)
                .putString("${cacheKeyPrefix}merchantBio", resolvedBio)
                .putString("${cacheKeyPrefix}merchantPhone", resolvedPhone)
                .putString("${cacheKeyPrefix}merchantAddress", resolvedAddress)
                .putString("${cacheKeyPrefix}merchantLogoUrl", resolvedLogo)
                .apply()
        }

        infoView = true

        println("✅ [Nearby Landmark Popup] Presenting landmark: ${landmark.label} (logo=$resolvedLogo)")
    }

    fun dismissLandmark() {
        infoView = false
        landmarkURL = ""
    }

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

        isMapPin = false
        mapLatitude = null
        mapLongitude = null
        reportedOwnerId = null
    }
}