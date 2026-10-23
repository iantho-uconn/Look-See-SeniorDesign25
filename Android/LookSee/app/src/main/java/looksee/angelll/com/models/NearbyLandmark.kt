package looksee.angelll.com.models

data class NearbyLandmark(
    val landmarkId: String = "",
    val label: String = "",
    val shortDescription: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val promotionEnabled: Boolean = false,
    val promotion: String? = null,
    val clusterId: String? = null,
    val websiteUrl: String? = null,
    val promoName: String? = null,
    val promoDescription: String? = null,
    val promoImageUrl: String? = null,
    val merchantName: String? = null,
    val merchantBio: String? = null,
    val merchantPhone: String? = null,
    val merchantAddress: String? = null,
    val merchantLogoUrl: String? = null
) {
    val id: String
        get() = landmarkId
}

data class NearbyLandmarksRequest(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val limit: Int? = 100,
)

data class NearbyLandmarksResponse(
    val items: List<NearbyLandmark> = emptyList(),
    val count: Int = items.size,
    val radiusMeters: Double = 0.0,
)
