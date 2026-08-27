package looksee.angelll.com.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android translation of OfflineMediaManager.swift.
 *
 * Positive media, thumbnails, and optional negative-reference videos live in the app's private
 * files directory. The queue ledger remains a single JSON value, matching the Swift manager.
 */
class OfflineMediaManager internal constructor(
    private val fileStore: ArchiveFileStore,
    private val ledger: ArchiveLedger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Date = { Date() },
) {
    private val mutationMutex = Mutex()

    private val _archivedItems = MutableStateFlow(loadArchive())
    val archivedItems: StateFlow<List<ArchivedMedia>> = _archivedItems.asStateFlow()

    /** Session-only cache that keeps captured negative-photo files alive with their identities. */
    private val _negativeCache =
        MutableStateFlow<Map<UUID, List<CapturedNegativePhoto>>>(emptyMap())
    val negativeCache: StateFlow<Map<UUID, List<CapturedNegativePhoto>>> =
        _negativeCache.asStateFlow()

    constructor(context: Context) : this(
        fileStore = AndroidArchiveFileStore(context.applicationContext),
        ledger = SharedPreferencesArchiveLedger(context.applicationContext),
    )

    fun getDocumentsDirectory(): File = fileStore.rootDirectory

    fun getFile(media: ArchivedMedia): File = fileStore.file(media.fileName)

    fun getThumbnailFile(media: ArchivedMedia): File = fileStore.file(media.thumbnailFileName)

    fun getNegativeVideoFile(media: ArchivedMedia): File? =
        media.negativeVideoFileName?.let(fileStore::file)

    suspend fun archiveVideo(
        tempFile: File,
        latitude: Double,
        longitude: Double,
        landmarkId: String?,
        label: String,
        shortDescription: String,
        userDescription: String?,
        negativeVideoFile: File?,
        isTier2: Boolean = false,
    ): ArchivedMedia? {
        val uniqueName = UUID.randomUUID().toString()
        val fileName = "$uniqueName.${videoExtension(tempFile)}"
        val thumbnailFileName = "${uniqueName}_thumb.jpg"
        val negativeFileName = negativeVideoFile?.let {
            "${uniqueName}_negative.${videoExtension(it)}"
        }

        val archived = withContext(ioDispatcher) {
            try {
                fileStore.archiveVideo(tempFile, fileName, thumbnailFileName)
                if (negativeVideoFile != null && negativeFileName != null) {
                    fileStore.copy(negativeVideoFile, negativeFileName)
                }
                true
            } catch (error: Exception) {
                fileStore.deleteQuietly(fileName)
                fileStore.deleteQuietly(thumbnailFileName)
                negativeFileName?.let(fileStore::deleteQuietly)
                System.err.println("Failed to archive video: ${error.message}")
                false
            }
        }
        if (!archived) return null

        val entry = ArchivedMedia(
            title = label,
            fileName = fileName,
            thumbnailFileName = thumbnailFileName,
            isVideo = true,
            latitude = latitude,
            longitude = longitude,
            dateSaved = now(),
            isFavorite = false,
            landmarkId = landmarkId,
            savedLabel = label,
            savedDescription = shortDescription,
            savedUserDescription = userDescription,
            negativeVideoFileName = negativeFileName,
            isTier2 = isTier2,
        )
        appendAndPersist(entry)
        return entry
    }

    /** Accepts a complete JPEG and stores 80%- and 30%-quality archive copies. */
    suspend fun archivePhoto(
        imageJpegData: ByteArray,
        latitude: Double,
        longitude: Double,
        landmarkId: String?,
        label: String,
        shortDescription: String,
        userDescription: String?,
        negativeVideoFile: File?,
        isTier2: Boolean = false,
    ): ArchivedMedia? {
        val uniqueName = UUID.randomUUID().toString()
        val fileName = "$uniqueName.jpg"
        val thumbnailFileName = "${uniqueName}_thumb.jpg"
        val negativeFileName = negativeVideoFile?.let {
            "${uniqueName}_negative.${videoExtension(it)}"
        }

        val archived = withContext(ioDispatcher) {
            try {
                fileStore.archivePhoto(imageJpegData, fileName, thumbnailFileName)
                if (negativeVideoFile != null && negativeFileName != null) {
                    fileStore.copy(negativeVideoFile, negativeFileName)
                }
                true
            } catch (error: Exception) {
                fileStore.deleteQuietly(fileName)
                fileStore.deleteQuietly(thumbnailFileName)
                negativeFileName?.let(fileStore::deleteQuietly)
                System.err.println("Failed to archive photo: ${error.message}")
                false
            }
        }
        if (!archived) return null

        val entry = ArchivedMedia(
            title = label,
            fileName = fileName,
            thumbnailFileName = thumbnailFileName,
            isVideo = false,
            latitude = latitude,
            longitude = longitude,
            dateSaved = now(),
            isFavorite = false,
            landmarkId = landmarkId,
            savedLabel = label,
            savedDescription = shortDescription,
            savedUserDescription = userDescription,
            negativeVideoFileName = negativeFileName,
            isTier2 = isTier2,
        )
        appendAndPersist(entry)
        return entry
    }

    suspend fun updateDraft(
        media: ArchivedMedia,
        label: String,
        shortDescription: String,
        userDescription: String?,
    ) {
        updateAndPersist(media.id) {
            it.copy(
                title = label,
                savedLabel = label,
                savedDescription = shortDescription,
                savedUserDescription = userDescription,
            )
        }
    }

    suspend fun renameArchive(media: ArchivedMedia, newTitle: String) {
        updateAndPersist(media.id) { it.copy(title = newTitle) }
    }

    suspend fun toggleFavorite(media: ArchivedMedia) {
        updateAndPersist(media.id) { it.copy(isFavorite = !(it.isFavorite ?: false)) }
    }

    suspend fun cacheNegativePhotos(
        mediaId: UUID,
        photos: List<CapturedNegativePhoto>,
    ) {
        mutationMutex.withLock {
            _negativeCache.value = _negativeCache.value + (mediaId to photos.toList())
        }
    }

    /** Removes the queue item immediately, then deletes its files on the I/O dispatcher. */
    suspend fun deleteArchive(media: ArchivedMedia) {
        mutationMutex.withLock {
            _negativeCache.value = _negativeCache.value - media.id
            _archivedItems.value = _archivedItems.value.filterNot { it.id == media.id }
            saveArchive(_archivedItems.value)
        }

        withContext(ioDispatcher) {
            fileStore.deleteQuietly(media.fileName)
            fileStore.deleteQuietly(media.thumbnailFileName)
            media.negativeVideoFileName?.let(fileStore::deleteQuietly)
        }
    }

    private suspend fun appendAndPersist(entry: ArchivedMedia) {
        mutationMutex.withLock {
            _archivedItems.value = _archivedItems.value + entry
            saveArchive(_archivedItems.value)
        }
    }

    private suspend fun updateAndPersist(
        mediaId: UUID,
        transform: (ArchivedMedia) -> ArchivedMedia,
    ) {
        mutationMutex.withLock {
            val current = _archivedItems.value
            val index = current.indexOfFirst { it.id == mediaId }
            if (index < 0) return@withLock
            _archivedItems.value = current.toMutableList().apply {
                this[index] = transform(this[index])
            }
            saveArchive(_archivedItems.value)
        }
    }

    private fun saveArchive(items: List<ArchivedMedia>) {
        ledger.write(ArchiveJson.gson.toJson(items, ArchiveJson.listType))
    }

    private fun loadArchive(): List<ArchivedMedia> {
        val saved = ledger.read() ?: return emptyList()
        return try {
            ArchiveJson.gson.fromJson<List<ArchivedMedia>>(saved, ArchiveJson.listType)
                ?.toList()
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun videoExtension(file: File): String =
        if (file.extension.equals("mov", ignoreCase = true)) "mov" else "mp4"

    companion object {
        @Volatile
        private var sharedInstance: OfflineMediaManager? = null

        fun shared(context: Context): OfflineMediaManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: OfflineMediaManager(context).also { sharedInstance = it }
            }
    }
}

internal interface ArchiveLedger {
    fun read(): String?
    fun write(json: String)
}

private class SharedPreferencesArchiveLedger(context: Context) : ArchiveLedger {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(LEDGER_KEY, null)

    override fun write(json: String) {
        preferences.edit().putString(LEDGER_KEY, json).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "looksee_offline_media"
        const val LEDGER_KEY = "LookSeeArchiveLedger"
    }
}

internal interface ArchiveFileStore {
    val rootDirectory: File

    fun file(fileName: String): File
    fun archiveVideo(source: File, fileName: String, thumbnailFileName: String)
    fun archivePhoto(jpegData: ByteArray, fileName: String, thumbnailFileName: String)
    fun copy(source: File, fileName: String)
    fun deleteQuietly(fileName: String)
}

private class AndroidArchiveFileStore(context: Context) : ArchiveFileStore {
    override val rootDirectory: File = File(context.filesDir, ARCHIVE_DIRECTORY).apply {
        if (!exists() && !mkdirs()) {
            throw IOException("Could not create archive directory: $absolutePath")
        }
    }

    override fun file(fileName: String): File = File(rootDirectory, fileName)

    override fun archiveVideo(source: File, fileName: String, thumbnailFileName: String) {
        require(source.isFile) { "Video source does not exist: ${source.name}" }
        source.copyTo(file(fileName), overwrite = false)

        // Swift uses try? for video thumbnail extraction/writing. Keep the archived video even
        // when a device codec cannot produce frame zero.
        try {
            val retriever = MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(file(fileName).absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
            if (frame != null) {
                try {
                    FileOutputStream(file(thumbnailFileName)).use { output ->
                        frame.compress(Bitmap.CompressFormat.JPEG, VIDEO_THUMBNAIL_QUALITY, output)
                    }
                } finally {
                    frame.recycle()
                }
            }
        } catch (_: Exception) {
            deleteQuietly(thumbnailFileName)
        }
    }

    override fun archivePhoto(
        jpegData: ByteArray,
        fileName: String,
        thumbnailFileName: String,
    ) {
        val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            ?: throw IOException("The supplied photo is not valid image data.")
        try {
            FileOutputStream(file(fileName)).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, output)) {
                    throw IOException("Could not encode archive photo.")
                }
            }
            FileOutputStream(file(thumbnailFileName)).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_THUMBNAIL_QUALITY, output)) {
                    throw IOException("Could not encode archive thumbnail.")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun copy(source: File, fileName: String) {
        require(source.isFile) { "Media source does not exist: ${source.name}" }
        source.copyTo(file(fileName), overwrite = false)
    }

    override fun deleteQuietly(fileName: String) {
        try {
            file(fileName).delete()
        } catch (_: SecurityException) {
            // Swift also treats archive-file deletion as best effort.
        }
    }

    private companion object {
        const val ARCHIVE_DIRECTORY = "offline_media"
        const val PHOTO_QUALITY = 80
        const val PHOTO_THUMBNAIL_QUALITY = 30
        const val VIDEO_THUMBNAIL_QUALITY = 80
    }
}

private object ArchiveJson {
    val listType = object : TypeToken<List<ArchivedMedia>>() {}.type
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, SwiftReferenceDateAdapter())
        .create()
}

/** JSONEncoder's default Date format: seconds since 2001-01-01T00:00:00Z. */
private class SwiftReferenceDateAdapter : TypeAdapter<Date>() {
    override fun write(output: JsonWriter, value: Date?) {
        if (value == null) {
            output.nullValue()
            return
        }
        output.value((value.time - APPLE_REFERENCE_DATE_MILLIS) / 1_000.0)
    }

    override fun read(input: JsonReader): Date? {
        if (input.peek() == JsonToken.NULL) {
            input.nextNull()
            return null
        }
        val seconds = input.nextDouble()
        return Date(APPLE_REFERENCE_DATE_MILLIS + Math.round(seconds * 1_000.0))
    }

    private companion object {
        const val APPLE_REFERENCE_DATE_MILLIS = 978_307_200_000L
    }
}
