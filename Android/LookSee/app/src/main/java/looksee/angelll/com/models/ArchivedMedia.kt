package looksee.angelll.com.models

import java.util.UUID

/** Media saved locally for the archive and offline-upload queue. */
data class ArchivedMedia(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val fileName: String,
    val thumbnailFileName: String,
    val isVideo: Boolean,
    val latitude: Double,
    val longitude: Double,
    val dateSaved: Long,
    val isFavorite: Boolean? = null,
    val landmarkId: String? = null,
    val savedLabel: String? = null,
    val savedDescription: String? = null,
    val savedUserDescription: String? = null,
    val negativeVideoFileName: String? = null,
    val isTier2: Boolean? = null,
)
