package looksee.angelll.com.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.UUID

// Assuming ArchivedMedia and CapturedNegativePhoto will be created in your models package later
import looksee.angelll.com.models.ArchivedMedia
import looksee.angelll.com.models.CapturedNegativePhoto

object OfflineMediaManager {

    // Equivalent to @Published
    val archivedItems = MutableStateFlow<List<ArchivedMedia>>(emptyList())
    val negativeCache = MutableStateFlow<MutableMap<UUID, List<CapturedNegativePhoto>>>(mutableMapOf())

    private const val LEDGER_KEY = "LookSeeArchiveLedger"
    private const val PREFS_NAME = "OfflineMediaPrefs"
    private val gson = Gson()

    // Call this once from your MainActivity or when the app starts
    fun initialize(context: Context) {
        loadArchive(context)
    }

    private fun getDocumentsDirectory(context: Context): File {
        return context.filesDir
    }

    fun getFileURL(context: Context, media: ArchivedMedia): File {
        return File(getDocumentsDirectory(context), media.fileName)
    }

    fun getThumbnailURL(context: Context, media: ArchivedMedia): File {
        return File(getDocumentsDirectory(context), media.thumbnailFileName)
    }

    fun getNegativeVideoURL(context: Context, media: ArchivedMedia): File? {
        val negName = media.negativeVideoFileName ?: return null
        return File(getDocumentsDirectory(context), negName)
    }

    // MARK: - Archive Video (Queue) - Background Optimized
    suspend fun archiveVideo(
        context: Context,
        tempFile: File,
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoFile: File?,
        isTier2: Boolean = false
    ): ArchivedMedia? {
        val uniqueID = UUID.randomUUID().toString()
        val fileName = "$uniqueID.mov"
        val thumbName = "${uniqueID}_thumb.jpg"

        val docsDir = getDocumentsDirectory(context)
        val permanentFile = File(docsDir, fileName)

        // Detach heavy file I/O to a background thread (Equivalent to Task.detached)
        val newEntry = withContext(Dispatchers.IO) {
            var permanentNegName: String? = null

            try {
                // 1. Copy Positive Video
                tempFile.copyTo(permanentFile, overwrite = true)

                // 2. Generate Thumbnail (Heavy CPU Task via MediaMetadataRetriever)
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.fromFile(permanentFile))
                // Get frame at 0 seconds
                val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()

                if (bitmap != null) {
                    val thumbFile = File(docsDir, thumbName)
                    FileOutputStream(thumbFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) // 0.8 quality
                    }
                }

                // 3. Copy Negative Video
                if (negativeVideoFile != null) {
                    val negName = "${uniqueID}_negative.mov"
                    val permanentNegFile = File(docsDir, negName)
                    negativeVideoFile.copyTo(permanentNegFile, overwrite = true)
                    permanentNegName = negName
                }

                // 4. Create Queue Record
                ArchivedMedia(
                    id = UUID.fromString(uniqueID), // Assuming your Swift model generates ID or takes it
                    title = label,
                    fileName = fileName,
                    thumbnailFileName = thumbName,
                    isVideo = true,
                    latitude = lat,
                    longitude = lon,
                    dateSaved = Date(),
                    isFavorite = false,
                    landmarkId = landmarkId,
                    savedLabel = label,
                    savedDescription = shortDesc,
                    savedUserDescription = userDesc,
                    negativeVideoFileName = permanentNegName,
                    isTier2 = isTier2
                )
            } catch (e: Exception) {
                println("❌ Failed to archive video: ${e.message}")
                null
            }
        }

        // Back on Main Thread/Flow logic: Update UI immediately
        newEntry?.let { entry ->
            val updatedList = archivedItems.value.toMutableList()
            updatedList.add(entry)
            archivedItems.value = updatedList
            saveArchive(context)
        }

        return newEntry
    }

    // MARK: - Archive Photo (Queue) - Background Optimized
    suspend fun archivePhoto(
        context: Context,
        image: Bitmap, // Replaced UIImage with Bitmap
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoFile: File?,
        isTier2: Boolean = false
    ): ArchivedMedia? {
        val uniqueID = UUID.randomUUID().toString()
        val fileName = "$uniqueID.jpg"
        val thumbName = "${uniqueID}_thumb.jpg"
        val docsDir = getDocumentsDirectory(context)

        val newEntry = withContext(Dispatchers.IO) {
            var permanentNegName: String? = null

            try {
                val file = File(docsDir, fileName)
                val thumbFile = File(docsDir, thumbName)

                // Compress Image (0.8) and Thumbnail (0.3)
                FileOutputStream(file).use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                FileOutputStream(thumbFile).use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, 30, out)
                }

                // Copy Negative Video
                if (negativeVideoFile != null) {
                    val negName = "${uniqueID}_negative.mov"
                    val permanentNegFile = File(docsDir, negName)
                    negativeVideoFile.copyTo(permanentNegFile, overwrite = true)
                    permanentNegName = negName
                }

                ArchivedMedia(
                    id = UUID.fromString(uniqueID),
                    title = label,
                    fileName = fileName,
                    thumbnailFileName = thumbName,
                    isVideo = false,
                    latitude = lat,
                    longitude = lon,
                    dateSaved = Date(),
                    isFavorite = false,
                    landmarkId = landmarkId,
                    savedLabel = label,
                    savedDescription = shortDesc,
                    savedUserDescription = userDesc,
                    negativeVideoFileName = permanentNegName,
                    isTier2 = isTier2
                )
            } catch (e: Exception) {
                println("❌ Failed to archive photo: ${e.message}")
                null
            }
        }

        newEntry?.let { entry ->
            val updatedList = archivedItems.value.toMutableList()
            updatedList.add(entry)
            archivedItems.value = updatedList
            saveArchive(context)
        }

        return newEntry
    }

    // MARK: - Draft Updates
    fun updateDraft(context: Context, media: ArchivedMedia, label: String, shortDesc: String, userDesc: String?) {
        val currentList = archivedItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == media.id }
        if (index != -1) {
            val updatedMedia = currentList[index].copy(
                savedLabel = label,
                savedDescription = shortDesc,
                savedUserDescription = userDesc,
                title = label
            )
            currentList[index] = updatedMedia
            archivedItems.value = currentList
            saveArchive(context)
        }
    }

    fun renameArchive(context: Context, media: ArchivedMedia, newTitle: String) {
        val currentList = archivedItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == media.id }
        if (index != -1) {
            val updatedMedia = currentList[index].copy(title = newTitle)
            currentList[index] = updatedMedia
            archivedItems.value = currentList
            saveArchive(context)
        }
    }

    fun toggleFavorite(context: Context, media: ArchivedMedia) {
        val currentList = archivedItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == media.id }
        if (index != -1) {
            val currentFav = currentList[index].isFavorite ?: false
            val updatedMedia = currentList[index].copy(isFavorite = !currentFav)
            currentList[index] = updatedMedia
            archivedItems.value = currentList
            saveArchive(context)
        }
    }

    // MARK: - Delete (Background Optimized)
    fun deleteArchive(context: Context, media: ArchivedMedia) {
        val fileURL = getFileURL(context, media)
        val thumbURL = getThumbnailURL(context, media)
        val negURL = getNegativeVideoURL(context, media)

        // Tell hard drive to delete in background
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            fileURL.delete()
            thumbURL.delete()
            negURL?.delete()
        }

        // Update UI immediately
        val currentCache = negativeCache.value.toMutableMap()
        currentCache.remove(media.id)
        negativeCache.value = currentCache

        val currentList = archivedItems.value.toMutableList()
        currentList.removeAll { it.id == media.id }
        archivedItems.value = currentList

        saveArchive(context)
    }

    private fun saveArchive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(archivedItems.value)
        prefs.edit().putString(LEDGER_KEY, json).apply()
    }

    private fun loadArchive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(LEDGER_KEY, null)

        if (json != null) {
            try {
                val type = object : TypeToken<List<ArchivedMedia>>() {}.type
                val decoded: List<ArchivedMedia> = gson.fromJson(json, type)
                archivedItems.value = decoded
            } catch (e: Exception) {
                println("❌ Failed to load archive JSON")
            }
        }
    }
}