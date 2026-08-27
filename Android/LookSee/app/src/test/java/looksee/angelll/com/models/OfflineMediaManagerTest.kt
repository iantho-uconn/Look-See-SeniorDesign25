package looksee.angelll.com.models

import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMediaManagerTest {
    @Test
    fun loadsSwiftReferenceDateLedger(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val ledger = TestArchiveLedger(
            """[{"id":"$id","title":"City Hall","fileName":"hall.jpg","thumbnailFileName":"hall_thumb.jpg","isVideo":false,"latitude":40.7,"longitude":-74.0,"dateSaved":0.0,"isFavorite":false}]""",
        )

        val manager = manager(ledger = ledger)

        assertEquals(1, manager.archivedItems.value.size)
        assertEquals(id, manager.archivedItems.value.single().id)
        assertEquals(Date(978_307_200_000L), manager.archivedItems.value.single().dateSaved)
    }

    @Test
    fun archivePhotoStoresMediaThumbnailNegativeAndLedger(): Unit = runBlocking {
        val ledger = TestArchiveLedger()
        val files = TestArchiveFileStore()
        val negative = temporaryFile("negative", ".mp4")
        val savedAt = Date(1_786_000_000_000L)
        val manager = manager(files, ledger, savedAt)

        val media = manager.archivePhoto(
            imageJpegData = byteArrayOf(1, 2, 3),
            latitude = 40.7128,
            longitude = -74.0060,
            landmarkId = "landmark_1",
            label = "City Hall",
            shortDescription = "Clock tower",
            userDescription = "Near the park",
            negativeVideoFile = negative,
            isTier2 = true,
        )

        assertNotNull(media)
        requireNotNull(media)
        assertFalse(media.isVideo)
        assertEquals(savedAt, media.dateSaved)
        assertTrue(files.names.contains(media.fileName))
        assertTrue(files.names.contains(media.thumbnailFileName))
        assertTrue(files.names.contains(requireNotNull(media.negativeVideoFileName)))
        assertTrue(media.negativeVideoFileName!!.endsWith(".mp4"))
        assertTrue(ledger.json!!.contains("\"dateSaved\":"))
        assertEquals(1, manager.archivedItems.value.size)
        negative.delete()
    }

    @Test
    fun archiveVideoUsesAndroidMp4AndMovOnlyForRealMovInput(): Unit = runBlocking {
        val files = TestArchiveFileStore()
        val manager = manager(files = files)
        val mp4 = temporaryFile("positive", ".mp4")
        val movNegative = temporaryFile("negative", ".mov")

        val media = manager.archiveVideo(
            tempFile = mp4,
            latitude = 1.0,
            longitude = 2.0,
            landmarkId = null,
            label = "Library",
            shortDescription = "Stone entrance",
            userDescription = null,
            negativeVideoFile = movNegative,
        )

        requireNotNull(media)
        assertTrue(media.fileName.endsWith(".mp4"))
        assertTrue(media.negativeVideoFileName!!.endsWith(".mov"))
        assertTrue(files.names.contains(media.thumbnailFileName))
        mp4.delete()
        movNegative.delete()
    }

    @Test
    fun draftRenameAndFavoriteChangesSurviveReload(): Unit = runBlocking {
        val ledger = TestArchiveLedger()
        val files = TestArchiveFileStore()
        val manager = manager(files, ledger)
        val media = requireNotNull(
            manager.archivePhoto(
                byteArrayOf(1),
                1.0,
                2.0,
                null,
                "Old label",
                "Old description",
                null,
                null,
            ),
        )

        manager.updateDraft(media, "New label", "New description", "User detail")
        manager.renameArchive(media, "Archive title")
        manager.toggleFavorite(media)

        val reloaded = manager(files, ledger).archivedItems.value.single()
        assertEquals("Archive title", reloaded.title)
        assertEquals("New label", reloaded.savedLabel)
        assertEquals("New description", reloaded.savedDescription)
        assertEquals("User detail", reloaded.savedUserDescription)
        assertEquals(true, reloaded.isFavorite)
    }

    @Test
    fun deleteRemovesLedgerFilesAndSessionCache(): Unit = runBlocking {
        val ledger = TestArchiveLedger()
        val files = TestArchiveFileStore()
        val manager = manager(files, ledger)
        val negative = temporaryFile("negative", ".mp4")
        val media = requireNotNull(
            manager.archivePhoto(
                byteArrayOf(1),
                1.0,
                2.0,
                null,
                "Label",
                "Description",
                null,
                negative,
            ),
        )
        val negativePhoto = CapturedNegativePhoto(temporaryFile("negative-photo", ".jpg"))
        manager.cacheNegativePhotos(media.id, listOf(negativePhoto))

        manager.deleteArchive(media)

        assertTrue(manager.archivedItems.value.isEmpty())
        assertFalse(manager.negativeCache.value.containsKey(media.id))
        assertFalse(files.names.contains(media.fileName))
        assertFalse(files.names.contains(media.thumbnailFileName))
        assertFalse(files.names.contains(requireNotNull(media.negativeVideoFileName)))
        assertEquals("[]", ledger.json)
        negativePhoto.deleteLocalFile()
        negative.delete()
    }

    @Test
    fun failedArchiveRollsBackEveryPartialFile(): Unit = runBlocking {
        val ledger = TestArchiveLedger()
        val files = TestArchiveFileStore(failCopy = true)
        val manager = manager(files, ledger)
        val negative = temporaryFile("negative", ".mp4")

        val media = manager.archivePhoto(
            byteArrayOf(1),
            1.0,
            2.0,
            null,
            "Label",
            "Description",
            null,
            negative,
        )

        assertNull(media)
        assertTrue(manager.archivedItems.value.isEmpty())
        assertTrue(files.names.isEmpty())
        assertNull(ledger.json)
        negative.delete()
    }

    @Test
    fun malformedLedgerLoadsAsAnEmptyQueue(): Unit = runBlocking {
        val manager = manager(ledger = TestArchiveLedger("not-json"))

        assertTrue(manager.archivedItems.value.isEmpty())
    }

    private fun manager(
        files: TestArchiveFileStore = TestArchiveFileStore(),
        ledger: TestArchiveLedger = TestArchiveLedger(),
        date: Date = Date(1_786_000_000_000L),
    ) = OfflineMediaManager(
        fileStore = files,
        ledger = ledger,
        ioDispatcher = Dispatchers.Unconfined,
        now = { date },
    )

    private fun temporaryFile(prefix: String, suffix: String): File =
        Files.createTempFile(prefix, suffix).toFile().apply { writeBytes(byteArrayOf(7)) }
}

private class TestArchiveLedger(var json: String? = null) : ArchiveLedger {
    override fun read(): String? = json

    override fun write(json: String) {
        this.json = json
    }
}

private class TestArchiveFileStore(
    private val failCopy: Boolean = false,
) : ArchiveFileStore {
    override val rootDirectory: File = File("test-archive")
    private val stored = linkedMapOf<String, ByteArray>()
    val names: Set<String>
        get() = stored.keys

    override fun file(fileName: String): File = File(rootDirectory, fileName)

    override fun archiveVideo(source: File, fileName: String, thumbnailFileName: String) {
        stored[fileName] = source.readBytes()
        stored[thumbnailFileName] = byteArrayOf(8)
    }

    override fun archivePhoto(
        jpegData: ByteArray,
        fileName: String,
        thumbnailFileName: String,
    ) {
        stored[fileName] = jpegData
        stored[thumbnailFileName] = byteArrayOf(3)
    }

    override fun copy(source: File, fileName: String) {
        if (failCopy) throw IllegalStateException("copy failed")
        stored[fileName] = source.readBytes()
    }

    override fun deleteQuietly(fileName: String) {
        stored.remove(fileName)
    }
}
