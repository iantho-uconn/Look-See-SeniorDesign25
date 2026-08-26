package looksee.angelll.com.services

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object OfflineMediaManager {

    private const val PREFS_NAME = "LookSeeOfflinePrefs"
    private const val LEDGER_KEY = "LookSeeArchiveLedger"

    // 🚀 Reactive States for Compose UI
    private val _archivedItems = MutableStateFlow<List<ArchivedMedia>>(emptyList())
    val archivedItems: StateFlow<List<ArchivedMedia>> = _archivedItems.asStateFlow()

    // In-memory cache to keep negative photos alive during your app session
    private val _negativeCache = MutableStateFlow<Map<String, List<CapturedNegativePhoto>>>(emptyMap())
    val negativeCache: StateFlow<Map<String, List<CapturedNegativePhoto>>> = _negativeCache.asStateFlow()

    // Call this once from your MainActivity or Application class: OfflineMediaManager.initialize(context)
    fun initialize(context: Context) {
        loadArchive(context)
    }

    private fun getDocumentsDirectory(context: Context): File {
        return context.filesDir
    }

    fun getFile(context: Context, media: ArchivedMedia): File {
        return File(getDocumentsDirectory(context), media.fileName)
    }

    fun getThumbnail(context: Context, media: ArchivedMedia): File {
        return File(getDocumentsDirectory(context), media.thumbnailFileName)
    }

    fun getNegativeVideo(context: Context, media: ArchivedMedia): File? {
        val negName = media.negativeVideoFileName ?: return null
        return File(getDocumentsDirectory(context), negName)
    }

    // 🚀 THE FIX: Bumps an item to the absolute front of the queue by spoofing an old date
    fun prioritizeAndRetry(context: Context, media: ArchivedMedia) {
        _archivedItems.update { currentList ->
            val mutableList = currentList.toMutableList()
            val index = mutableList.indexOfFirst { it.id == media.id }
            if (index != -1) {
                // 0L makes it the oldest possible item (1970), forcing it to the front!
                val updatedItem = mutableList[index].copy(dateSaved = 0L)
                mutableList[index] = updatedItem
                mutableList.sortBy { it.dateSaved }
            }
            mutableList
        }
        saveArchive(context)
    }

    // MARK: - Archive Video (Queue) - Background Optimized
    suspend fun archiveVideo(
        context: Context,
        tempUri: Uri,
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoUri: Uri?,
        isTier2: Boolean = false
    ): ArchivedMedia? = withContext(Dispatchers.IO) {
        val uniqueID = UUID.randomUUID().toString()
        val fileName = "$uniqueID.mov"
        val thumbName = "${uniqueID}_thumb.jpg"

        val docsDir = getDocumentsDirectory(context)
        val permanentFile = File(docsDir, fileName)
        var permanentNegName: String? = null

        try {
            // 1. Copy Positive Video
            context.contentResolver.openInputStream(tempUri)?.use { input ->
                permanentFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 2. Generate Thumbnail (Heavy CPU Task)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, tempUri)
                val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val thumbFile = File(docsDir, thumbName)
                    thumbFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }

            // 3. Copy Negative Video
            if (negativeVideoUri != null) {
                val negName = "${uniqueID}_negative.mov"
                val permanentNegFile = File(docsDir, negName)
                context.contentResolver.openInputStream(negativeVideoUri)?.use { input ->
                    permanentNegFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                permanentNegName = negName
            }

            // 4. Create Queue Record
            val newEntry = ArchivedMedia(
                id = uniqueID,
                title = label,
                fileName = fileName,
                thumbnailFileName = thumbName,
                isVideo = true,
                latitude = lat,
                longitude = lon,
                dateSaved = System.currentTimeMillis(),
                isFavorite = false,
                landmarkId = landmarkId,
                savedLabel = label,
                savedDescription = shortDesc,
                savedUserDescription = userDesc,
                negativeVideoFileName = permanentNegName,
                isTier2 = isTier2
            )

            // Update state flow on main thread equivalent
            _archivedItems.update { current ->
                val newList = current.toMutableList()
                newList.add(newEntry)
                newList.sortBy { it.dateSaved } // 🚀 Keep chronological
                newList
            }
            saveArchive(context)

            return@withContext newEntry

        } catch (e: Exception) {
            println("❌ Failed to archive video: ${e.localizedMessage}")
            return@withContext null
        }
    }

    // MARK: - Archive Photo (Queue) - Background Optimized
    suspend fun archivePhoto(
        context: Context,
        image: Bitmap,
        lat: Double,
        lon: Double,
        landmarkId: String?,
        label: String,
        shortDesc: String,
        userDesc: String?,
        negativeVideoUri: Uri?,
        isTier2: Boolean = false
    ): ArchivedMedia? = withContext(Dispatchers.IO) {
        val uniqueID = UUID.randomUUID().toString()
        val fileName = "$uniqueID.jpg"
        val thumbName = "${uniqueID}_thumb.jpg"
        val docsDir = getDocumentsDirectory(context)
        var permanentNegName: String? = null

        try {
            val permanentFile = File(docsDir, fileName)
            val thumbFile = File(docsDir, thumbName)

            // Write full image
            permanentFile.outputStream().use { out ->
                image.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }

            // Generate and write thumbnail
            val ratio = Math.min(400f / image.width, 400f / image.height)
            val width = Math.round(ratio * image.width)
            val height = Math.round(ratio * image.height)
            val thumbBitmap = Bitmap.createScaledBitmap(image, width, height, true)

            thumbFile.outputStream().use { out ->
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 30, out)
            }

            // Copy Negative Video
            if (negativeVideoUri != null) {
                val negName = "${uniqueID}_negative.mov"
                val permanentNegFile = File(docsDir, negName)
                context.contentResolver.openInputStream(negativeVideoUri)?.use { input ->
                    permanentNegFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                permanentNegName = negName
            }

            val newEntry = ArchivedMedia(
                id = uniqueID,
                title = label,
                fileName = fileName,
                thumbnailFileName = thumbName,
                isVideo = false,
                latitude = lat,
                longitude = lon,
                dateSaved = System.currentTimeMillis(),
                isFavorite = false,
                landmarkId = landmarkId,
                savedLabel = label,
                savedDescription = shortDesc,
                savedUserDescription = userDesc,
                negativeVideoFileName = permanentNegName,
                isTier2 = isTier2
            )

            _archivedItems.update { current ->
                val newList = current.toMutableList()
                newList.add(newEntry)
                newList.sortBy { it.dateSaved } // 🚀 Keep chronological
                newList
            }
            saveArchive(context)

            return@withContext newEntry

        } catch (e: Exception) {
            println("❌ Failed to archive photo: ${e.localizedMessage}")
            return@withContext null
        }
    }

    // MARK: - Draft Updates
    fun updateDraft(context: Context, media: ArchivedMedia, label: String, shortDesc: String, userDesc: String?) {
        _archivedItems.update { currentList ->
            currentList.map { item ->
                if (item.id == media.id) {
                    item.copy(
                        savedLabel = label,
                        savedDescription = shortDesc,
                        savedUserDescription = userDesc,
                        title = label
                    )
                } else item
            }
        }
        saveArchive(context)
    }

    fun renameArchive(context: Context, media: ArchivedMedia, newTitle: String) {
        _archivedItems.update { currentList ->
            currentList.map { item ->
                if (item.id == media.id) item.copy(title = newTitle) else item
            }
        }
        saveArchive(context)
    }

    fun toggleFavorite(context: Context, media: ArchivedMedia) {
        _archivedItems.update { currentList ->
            currentList.map { item ->
                if (item.id == media.id) item.copy(isFavorite = !item.isFavorite) else item
            }
        }
        saveArchive(context)
    }

    // MARK: - Delete (Background Optimized)
    fun deleteArchive(context: Context, media: ArchivedMedia) {
        val file = getFile(context, media)
        val thumb = getThumbnail(context, media)
        val negFile = getNegativeVideo(context, media)

        // Tell the hard drive to delete the heavy files in the background
        CoroutineScope(Dispatchers.IO).launch {
            file.delete()
            thumb.delete()
            negFile?.delete()
        }

        // Remove from the UI immediately so it feels instant
        _negativeCache.update { currentMap ->
            val newMap = currentMap.toMutableMap()
            newMap.remove(media.id)
            newMap
        }

        _archivedItems.update { currentList ->
            currentList.filter { it.id != media.id }
        }

        saveArchive(context)
    }

    // MARK: - Native JSON Persistence
    private fun saveArchive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        _archivedItems.value.forEach { media ->
            val obj = JSONObject().apply {
                put("id", media.id)
                put("title", media.title)
                put("fileName", media.fileName)
                put("thumbnailFileName", media.thumbnailFileName)
                put("isVideo", media.isVideo)
                put("latitude", media.latitude)
                put("longitude", media.longitude)
                put("dateSaved", media.dateSaved)
                put("isFavorite", media.isFavorite)
                put("landmarkId", media.landmarkId ?: JSONObject.NULL)
                put("savedLabel", media.savedLabel)
                put("savedDescription", media.savedDescription)
                put("savedUserDescription", media.savedUserDescription ?: JSONObject.NULL)
                put("negativeVideoFileName", media.negativeVideoFileName ?: JSONObject.NULL)
                put("isTier2", media.isTier2)
            }
            jsonArray.put(obj)
        }

        prefs.edit().putString(LEDGER_KEY, jsonArray.toString()).apply()
    }

    private fun loadArchive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(LEDGER_KEY, null) ?: return

        try {
            val jsonArray = JSONArray(jsonString)
            val loadedItems = mutableListOf<ArchivedMedia>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                loadedItems.add(
                    ArchivedMedia(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        fileName = obj.getString("fileName"),
                        thumbnailFileName = obj.getString("thumbnailFileName"),
                        isVideo = obj.getBoolean("isVideo"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        dateSaved = obj.getLong("dateSaved"),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        landmarkId = if (obj.isNull("landmarkId")) null else obj.getString("landmarkId"),
                        savedLabel = obj.getString("savedLabel"),
                        savedDescription = obj.getString("savedDescription"),
                        savedUserDescription = if (obj.isNull("savedUserDescription")) null else obj.getString("savedUserDescription"),
                        negativeVideoFileName = if (obj.isNull("negativeVideoFileName")) null else obj.getString("negativeVideoFileName"),
                        isTier2 = obj.optBoolean("isTier2", false)
                    )
                )
            }

            // 🚀 THE FIX: Ensure it is sorted oldest-first on load
            _archivedItems.value = loadedItems.sortedBy { it.dateSaved }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// MARK: - Stubs (Ensure these match your actual data models)
data class ArchivedMedia(
    val id: String,
    val title: String,
    val fileName: String,
    val thumbnailFileName: String,
    val isVideo: Boolean,
    val latitude: Double,
    val longitude: Double,
    val dateSaved: Long,
    val isFavorite: Boolean,
    val landmarkId: String?,
    val savedLabel: String,
    val savedDescription: String,
    val savedUserDescription: String?,
    val negativeVideoFileName: String?,
    val isTier2: Boolean
)

data class CapturedNegativePhoto(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri
)