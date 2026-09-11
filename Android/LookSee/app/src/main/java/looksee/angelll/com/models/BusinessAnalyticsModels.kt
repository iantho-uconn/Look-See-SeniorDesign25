package looksee.angelll.com.models

import com.google.gson.annotations.SerializedName

data class AnalyticsResponse(
    @SerializedName("analytics") val analytics: List<LandmarkAnalyticsData> = emptyList()
)

data class LandmarkAnalyticsData(
    @SerializedName("landmarkId") val landmarkId: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("totalClicks") val totalClicks: Int = 0,
    @SerializedName("dailyData") val dailyData: List<DailyClickPoint> = emptyList()
) {
    val id: String get() = landmarkId
}

data class DailyClickPoint(
    @SerializedName("date") val date: String = "",
    @SerializedName("clicks") val clicks: Int = 0
) {
    val id: String get() = date
}
