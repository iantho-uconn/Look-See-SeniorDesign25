package looksee.angelll.com.models

import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-checks the Checkpoint 8 archive contract against the Checkpoint 11 queue engine. */
class CheckpointIntegrationTest {
    @Test
    fun archivedMetadataFlowsIntoPositiveUploadBeforeDeletion(): Unit = runBlocking {
        val media = archivedMedia(savedLabel = "  Bryant Park  ")
        val fixture = IntegrationFixture(media)

        val result = fixture.engine.process()

        assertEquals(AutoUploadRunResult.Completed(1), result)
        assertEquals(listOf("positive", "delete"), fixture.order)
        assertSame(media, fixture.positiveMedia)
        assertEquals("  Bryant Park  ", fixture.positiveLabel)
        assertEquals("existing_landmark", fixture.positiveLandmarkId)
        assertTrue(fixture.archive.pendingItems().isEmpty())
    }

    @Test
    fun serverLandmarkIdFeedsNegativeUploadBeforeDeletion(): Unit = runBlocking {
        val media = archivedMedia(withNegative = true, landmarkId = null)
        val fixture = IntegrationFixture(media)
        fixture.serverLandmarkId = "server_landmark"

        val result = fixture.engine.process()

        assertEquals(AutoUploadRunResult.Completed(1), result)
        assertEquals(listOf("positive", "negative", "delete"), fixture.order)
        assertEquals("generated_landmark", fixture.positiveLandmarkId)
        assertEquals("server_landmark", fixture.negativeLandmarkId)
        assertEquals("id-token", fixture.negativeIdToken)
    }

    @Test
    fun transientNegativeFailureKeepsTheEntireArchiveForRetry(): Unit = runBlocking {
        val media = archivedMedia(withNegative = true)
        val fixture = IntegrationFixture(media)
        val failure = UnknownHostException("offline")
        fixture.negativeFailure = failure

        val result = fixture.engine.process()

        assertTrue(result is AutoUploadRunResult.Retry)
        assertSame(failure, (result as AutoUploadRunResult.Retry).cause)
        assertEquals(listOf("positive", "negative"), fixture.order)
        assertEquals(listOf(media), fixture.archive.pendingItems())
    }

    private companion object {
        fun archivedMedia(
            savedLabel: String? = "Library",
            withNegative: Boolean = false,
            landmarkId: String? = "existing_landmark",
        ): ArchivedMedia {
            val id = UUID.randomUUID()
            return ArchivedMedia(
                id = id,
                title = "Library",
                fileName = "$id.jpg",
                thumbnailFileName = "${id}_thumb.jpg",
                isVideo = false,
                latitude = 40.7536,
                longitude = -73.9832,
                dateSaved = Date(1_786_000_000_000L),
                landmarkId = landmarkId,
                savedLabel = savedLabel,
                savedDescription = "Stone facade",
                savedUserDescription = "North entrance",
                negativeVideoFileName = if (withNegative) "${id}_negative.mp4" else null,
                isTier2 = false,
            )
        }
    }
}

private class IntegrationFixture(media: ArchivedMedia) {
    val order = mutableListOf<String>()
    val archive = IntegrationArchive(media, order)
    var positiveMedia: ArchivedMedia? = null
    var positiveLabel: String? = null
    var positiveLandmarkId: String? = null
    var negativeLandmarkId: String? = null
    var negativeIdToken: String? = null
    var serverLandmarkId: String? = null
    var negativeFailure: Throwable? = null

    val engine = AutoUploadQueueEngine(
        archive = archive,
        sessionProvider = object : AutoUploadSessionProvider {
            override suspend fun fetchSession() = AutoUploadSession(
                userEmail = "person@example.com",
                idToken = "id-token",
                hasActiveSubscription = true,
                tokenBalance = 1,
            )
        },
        positiveUploader = object : AutoUploadPositiveUploader {
            override suspend fun upload(
                media: ArchivedMedia,
                file: File,
                session: AutoUploadSession,
                label: String,
                landmarkId: String,
                onProgress: suspend (Double) -> Unit,
            ): PositiveSubmissionResult {
                order += "positive"
                positiveMedia = media
                positiveLabel = label
                positiveLandmarkId = landmarkId
                onProgress(1.0)
                return PositiveSubmissionResult(
                    submissionId = "submission",
                    landmarkId = serverLandmarkId,
                    mediaKind = MediaKind.PHOTO,
                    s3Key = "positive/${file.name}",
                )
            }
        },
        negativeUploader = object : AutoUploadNegativeUploader {
            override suspend fun upload(
                file: File,
                landmarkId: String,
                idToken: String,
                onProgress: suspend (Double) -> Unit,
            ) {
                order += "negative"
                negativeLandmarkId = landmarkId
                negativeIdToken = idToken
                negativeFailure?.let { throw it }
                onProgress(1.0)
            }
        },
        pauseGate = object : AutoUploadPauseGate {
            override fun isPaused() = false
        },
        events = object : AutoUploadEvents {
            override suspend fun onProgress(progress: AutoUploadItemProgress) = Unit
            override fun onUploadSucceeded(label: String) = Unit
            override fun onLimit(title: String, body: String) = Unit
        },
        landmarkIdFactory = { "generated_landmark" },
    )
}

private class IntegrationArchive(
    media: ArchivedMedia,
    private val order: MutableList<String>,
) : AutoUploadArchive {
    private val root = Files.createTempDirectory("checkpoint-integration").toFile()
    private val items = mutableListOf(media)

    init {
        File(root, media.fileName).writeBytes(byteArrayOf(1, 2, 3))
        media.negativeVideoFileName?.let { name ->
            File(root, name).writeBytes(byteArrayOf(4, 5, 6))
        }
    }

    override fun pendingItems(): List<ArchivedMedia> = items.toList()

    override fun positiveFile(media: ArchivedMedia): File = File(root, media.fileName)

    override fun negativeFile(media: ArchivedMedia): File? =
        media.negativeVideoFileName?.let { File(root, it) }

    override suspend fun delete(media: ArchivedMedia) {
        order += "delete"
        items.remove(media)
    }
}
