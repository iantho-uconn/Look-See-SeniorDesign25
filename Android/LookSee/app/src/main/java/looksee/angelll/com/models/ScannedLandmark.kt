package looksee.angelll.com.models

/** One landmark saved in the user's scan history/library. */
data class ScannedLandmark(
    val id: Int,
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val category: String,
    val confidence: String,
    val detectionTime: Double,
)
