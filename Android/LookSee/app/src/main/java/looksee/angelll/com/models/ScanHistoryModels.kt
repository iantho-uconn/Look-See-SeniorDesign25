package looksee.angelll.com.models

import com.google.gson.annotations.SerializedName

data class ScanHistoryItem(
    @SerializedName("scannedAt") val scannedAt: String = "",
    @SerializedName("landmarkId") val landmarkId: String = "",
    @SerializedName("landmarkLabel") val landmarkLabel: String = "",
    @SerializedName("locationString") val locationString: String = "",
    @SerializedName("latitude") val latitude: String? = null,
    @SerializedName("longitude") val longitude: String? = null,
    @SerializedName("imageUrl") val imageUrl: String? = null
) {
    val id: String
        get() = scannedAt

    val latAsDouble: Double?
        get() = latitude?.toDoubleOrNull()

    val lonAsDouble: Double?
        get() = longitude?.toDoubleOrNull()
}
