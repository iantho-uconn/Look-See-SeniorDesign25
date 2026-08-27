package looksee.angelll.com.models

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class LiveLandmarkInfoResponse(
    val ok: Boolean? = null,
    val landmarkId: String = "",
    val label: String = "",
    val shortDescription: String = "",
    val websiteUrl: String? = null,
    val isActive: Boolean = false,
    val promotionEnabled: Boolean = false,
    val activePromotion: LiveLandmarkPromotion? = null,
    val activePromotions: List<LiveLandmarkPromotion>? = null,
    val activePromotionCount: Int? = null,
    val reason: String? = null,
    val merchantName: String? = null,
    val merchantBio: String? = null,
    val merchantPhone: String? = null,
    val merchantWebsite: String? = null,
    val merchantAddress: String? = null,
    val merchantLogoUrl: String? = null,
)

data class LiveLandmarkPromotion(
    val promotionId: String = "",
    val landmarkId: String = "",
    val landmarkLabel: String = "",
    val name: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val enabled: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    val id: String
        get() = promotionId
}

/** Merchant fields cached independently so the scan popup can render immediately. */
data class LiveLandmarkMerchantProfile(
    val landmarkId: String,
    val merchantName: String = "",
    val merchantBio: String = "",
    val merchantPhone: String = "",
    val merchantWebsite: String = "",
    val merchantAddress: String = "",
    val merchantLogoUrl: String = "",
)

class LiveLandmarkInfoServiceException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("Live landmark info API error $statusCode: $responseBody")

internal interface LiveLandmarkMerchantCache {
    fun read(landmarkId: String): LiveLandmarkMerchantProfile?
    fun write(profile: LiveLandmarkMerchantProfile)
}

/**
 * Android translation of LiveLandmarkInfoService.swift.
 *
 * The latest response and merchant profile are exposed as StateFlow values for
 * Compose. Call [loadCachedMerchantProfile] before the network request to show
 * the last merchant details immediately, then [fetchLiveInfo] to refresh them.
 */
class LiveLandmarkInfoService private constructor(
    private val httpClient: BusinessHttpClient,
    private val merchantCache: LiveLandmarkMerchantCache,
    private val gson: Gson,
) {
    constructor(context: Context) : this(
        httpClient = UrlConnectionBusinessHttpClient(),
        merchantCache = SharedPreferencesLiveLandmarkMerchantCache(
            context.applicationContext,
            Gson(),
        ),
        gson = Gson(),
    )

    internal constructor(
        httpClient: BusinessHttpClient,
        merchantCache: LiveLandmarkMerchantCache,
    ) : this(httpClient, merchantCache, Gson())

    private val _latestInfo = MutableStateFlow<LiveLandmarkInfoResponse?>(null)
    val latestInfo: StateFlow<LiveLandmarkInfoResponse?> = _latestInfo.asStateFlow()

    private val _merchantProfile = MutableStateFlow<LiveLandmarkMerchantProfile?>(null)
    val merchantProfile: StateFlow<LiveLandmarkMerchantProfile?> =
        _merchantProfile.asStateFlow()

    fun loadCachedMerchantProfile(landmarkId: String): LiveLandmarkMerchantProfile? =
        merchantCache.read(landmarkId).also { cached ->
            _merchantProfile.value = cached
        }

    suspend fun fetchLiveInfo(
        landmarkId: String,
        timeoutSeconds: Double = DEFAULT_TIMEOUT_SECONDS,
    ): LiveLandmarkInfoResponse {
        require(timeoutSeconds.isFinite() && timeoutSeconds > 0.0) {
            "timeoutSeconds must be finite and positive."
        }
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "GET",
                url = "$LOOKSEE_API_BASE_URL/landmarks/" +
                    "${encodedPathSegment(landmarkId)}/live-info",
                timeoutMillis = (timeoutSeconds * 1_000.0).roundToInt().coerceAtLeast(1),
            ),
        )
        if (response.statusCode !in 200..299) {
            throw LiveLandmarkInfoServiceException(response.statusCode, response.bodyText)
        }

        val decoded = gson.fromJson(response.bodyText, LiveLandmarkInfoResponse::class.java)
            ?: throw IllegalStateException("The live landmark response was empty.")
        val profile = decoded.toMerchantProfile()
        merchantCache.write(profile)
        _merchantProfile.value = profile
        _latestInfo.value = decoded
        return decoded
    }

    private fun LiveLandmarkInfoResponse.toMerchantProfile() =
        LiveLandmarkMerchantProfile(
            landmarkId = landmarkId,
            merchantName = merchantName.orEmpty(),
            merchantBio = merchantBio.orEmpty(),
            merchantPhone = merchantPhone.orEmpty(),
            merchantWebsite = merchantWebsite.orEmpty(),
            merchantAddress = merchantAddress.orEmpty(),
            merchantLogoUrl = merchantLogoUrl.orEmpty(),
        )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 3.5
    }
}

private class SharedPreferencesLiveLandmarkMerchantCache(
    context: Context,
    private val gson: Gson,
) : LiveLandmarkMerchantCache {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(landmarkId: String): LiveLandmarkMerchantProfile? {
        val json = preferences.getString(cacheKey(landmarkId), null) ?: return null
        return runCatching {
            gson.fromJson(json, LiveLandmarkMerchantProfile::class.java)
        }.getOrNull()
    }

    override fun write(profile: LiveLandmarkMerchantProfile) {
        preferences.edit()
            .putString(cacheKey(profile.landmarkId), gson.toJson(profile))
            .apply()
    }

    private fun cacheKey(landmarkId: String): String = "$CACHE_KEY_PREFIX$landmarkId"

    private companion object {
        const val PREFERENCES_NAME = "looksee_live_landmark_info"
        const val CACHE_KEY_PREFIX = "cached_merchant_"
    }
}
