package looksee.angelll.com.models

import java.util.Date
import java.util.UUID

/** Media saved locally for the archive and offline-upload queue. */
data class ArchivedMedia(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val fileName: String,
    val thumbnailFileName: String,
    val isVideo: Boolean,
    val latitude: Double,
    val longitude: Double,
    val dateSaved: Date,
    val isFavorite: Boolean? = null,
    val landmarkId: String? = null,
    val savedLabel: String? = null,
    val savedDescription: String? = null,
    val savedUserDescription: String? = null,
    val negativeVideoFileName: String? = null,
    val isTier2: Boolean? = null,
)
