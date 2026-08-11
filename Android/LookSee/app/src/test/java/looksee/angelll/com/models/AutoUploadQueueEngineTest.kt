package looksee.angelll.com.models

import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUploadQueueEngineTest {
    @Test
    fun emptyQueueCompletesWithoutFetchingAuthentication(): Unit = runBlocking {
        val fixture = Fixture(items = emptyList())

        val result = fixture.engine.process()

        assertEquals(AutoUploadRunResult.Completed(0), result)
        assertEquals(0, fixture.sessions.fetchCount)
    }

    @Test
    fun missingAuthenticationLeavesTheQueueUntouched(): Unit = runBlocking {
        val fixture = Fixture(session = null)

        val result = fixture.engine.process()

        assertSame(AutoUploadRunResult.NotAuthenticated, result)
        assertEquals(1, fixture.archive.pendingItems().size)
        assertTrue(fixture.archive.deleted.isEmpty())
    }

    @Test
    fun subscriptionLimitStopsBeforeUploadingAndNotifies(): Unit = runBlocking {
        val fixture = Fixture(
            session = signedInSession.copy(hasActiveSubscription = false),
        )

        val result = fixture.engine.process()

        assertSame(AutoUploadRunResult.SubscriptionRequired, result)
        assertTrue(fixture.positive.calls.isEmpty())
        assertEquals("Subscription Required", fixture.events.limits.single().first)
    }

    @Test
    fun tokenLimitStopsBeforeUploadingAndNotifies(): Unit = runBlocking {
        val fixture = Fixture(session = signedInSession.copy(tokenBalance = 0))

        val result = fixture.engine.process()

        assertSame(AutoUploadRunResult.TokensUnavailable, result)
        assertTrue(fixture.positive.calls.isEmpty())
        assertEquals("Out of Tokens", fixture.events.limits.single().first)
    }

    @Test
    fun positiveThenNegativeUploadCompletesBeforeArchiveDeletion(): Unit = runBlocking {
        val media = archivedMedia(
            title = "City Hall",
            savedLabel = "",
            isVideo = true,
            hasNegative = true,
        )
        val fixture = Fixture(items = listOf(media))
        fixture.positive.resultLandmarkId = "server_landmark"

        val result = fixture.engine.process()

        assertEquals(AutoUploadRunResult.Completed(1), result)
        assertEquals(listOf("positive", "negative", "delete"), fixture.order)
        assertEquals("City Hall", fixture.positive.calls.single().label)
        assertEquals("landmark_test", fixture.positive.calls.single().landmarkId)
        assertEquals("server_landmark", fixture.negative.landmarkIds.single())
        assertEquals(listOf(media.id), fixture.archive.deleted)
        assertEquals(listOf("City Hall"), fixture.events.successes)
        assertEquals(1.0, fixture.events.progress.last().overallProgress, 0.0001)
    }

    @Test
    fun pauseDuringAnUploadFinishesCurrentItemButPreservesTheNext(): Unit = runBlocking {
        val first = archivedMedia(title = "First")
        val second = archivedMedia(title = "Second")
        val fixture = Fixture(items = listOf(first, second))
        fixture.events.afterSuccess = { fixture.pause.paused = true }

        val result = fixture.engine.process()

        assertEquals(AutoUploadRunResult.Paused(1), result)
        assertEquals(listOf(first.id), fixture.archive.deleted)
        assertEquals(listOf(second.id), fixture.archive.pendingItems().map { it.id })
        assertEquals(1, fixture.positive.calls.size)
    }

    @Test
    fun transientFailureRequestsRetryAndKeepsTheCurrentArchive(): Unit = runBlocking {
        val fixture = Fixture()
        val failure = UnknownHostException("offline")
        fixture.positive.failure = failure

        val result = fixture.engine.process()

        assertTrue(result is AutoUploadRunResult.Retry)
        assertSame(failure, (result as AutoUploadRunResult.Retry).cause)
        assertTrue(fixture.archive.deleted.isEmpty())
        assertEquals(1, fixture.archive.pendingItems().size)
        assertTrue(fixture.events.successes.isEmpty())
    }

    private class Fixture(
        items: List<ArchivedMedia> = listOf(archivedMedia()),
        session: AutoUploadSession? = signedInSession,
    ) {
        val order = mutableListOf<String>()
        val archive = FakeArchive(items.toMutableList(), order)
        val sessions = FakeSessionProvider(session)
        val positive = FakePositiveUploader(order)
        val negative = FakeNegativeUploader(order)
        val pause = FakePauseGate()
        val events = FakeEvents()
        val engine = AutoUploadQueueEngine(
            archive = archive,
            sessionProvider = sessions,
            positiveUploader = positive,
            negativeUploader = negative,
            pauseGate = pause,
            events = events,
            landmarkIdFactory = { "landmark_test" },
        )
    }

    private companion object {
        val signedInSession = AutoUploadSession(
            userEmail = "person@example.com",
            idToken = "id-token",
            hasActiveSubscription = true,
            tokenBalance = 3,
        )

        fun archivedMedia(
            title: String = "Library",
            savedLabel: String? = "Library",
            isVideo: Boolean = false,
            hasNegative: Boolean = false,
        ): ArchivedMedia {
            val id = UUID.randomUUID()
            return ArchivedMedia(
                id = id,
                title = title,
                fileName = if (isVideo) "$id.mp4" else "$id.jpg",
                thumbnailFileName = "${id}_thumb.jpg",
                isVideo = isVideo,
                latitude = 40.7,
                longitude = -74.0,
                dateSaved = Date(1_786_000_000_000L),
                landmarkId = null,
                savedLabel = savedLabel,
                savedDescription = "Description",
                savedUserDescription = "User detail",
                negativeVideoFileName = if (hasNegative) "${id}_negative.mp4" else null,
            )
        }
    }
}

private class FakeArchive(
    private val items: MutableList<ArchivedMedia>,
    private val order: MutableList<String>,
) : AutoUploadArchive {
    val deleted = mutableListOf<UUID>()
    private val root = Files.createTempDirectory("auto-upload-test").toFile()

    init {
        items.forEach { media ->
            File(root, media.fileName).writeBytes(byteArrayOf(1, 2, 3))
            media.negativeVideoFileName?.let { name ->
                File(root, name).writeBytes(byteArrayOf(4, 5, 6))
            }
        }
    }

    override fun pendingItems(): List<ArchivedMedia> = items.toList()

    override fun positiveFile(media: ArchivedMedia): File = File(root, media.fileName)

    override fun negativeFile(media: ArchivedMedia): File? =
        media.negativeVideoFileName?.let { File(root, it) }

    override suspend fun delete(media: ArchivedMedia) {
        order += "delete"
        deleted += media.id
        items.removeAll { it.id == media.id }
        positiveFile(media).delete()
        negativeFile(media)?.delete()
    }
}

private class FakeSessionProvider(
    private val session: AutoUploadSession?,
) : AutoUploadSessionProvider {
    var fetchCount = 0

    override suspend fun fetchSession(): AutoUploadSession? {
        fetchCount += 1
        return session
    }
}

private data class PositiveCall(
    val mediaId: UUID,
    val label: String,
    val landmarkId: String,
)

private class FakePositiveUploader(
    private val order: MutableList<String>,
) : AutoUploadPositiveUploader {
    val calls = mutableListOf<PositiveCall>()
    var resultLandmarkId: String? = null
    var failure: Throwable? = null

    override suspend fun upload(
        media: ArchivedMedia,
        file: File,
        session: AutoUploadSession,
        label: String,
        landmarkId: String,
        onProgress: suspend (Double) -> Unit,
    ): PositiveSubmissionResult {
        failure?.let { throw it }
        assertTrue(file.isFile)
        order += "positive"
        calls += PositiveCall(media.id, label, landmarkId)
        onProgress(0.5)
        onProgress(1.0)
        return PositiveSubmissionResult(
            submissionId = "submission",
            landmarkId = resultLandmarkId,
            mediaKind = if (media.isVideo) MediaKind.VIDEO else MediaKind.PHOTO,
            s3Key = "key",
        )
    }
}

private class FakeNegativeUploader(
    private val order: MutableList<String>,
) : AutoUploadNegativeUploader {
    val landmarkIds = mutableListOf<String>()

    override suspend fun upload(
        file: File,
        landmarkId: String,
        idToken: String,
        onProgress: suspend (Double) -> Unit,
    ) {
        assertTrue(file.isFile)
        assertFalse(idToken.isBlank())
        order += "negative"
        landmarkIds += landmarkId
        onProgress(1.0)
    }
}

private class FakePauseGate : AutoUploadPauseGate {
    var paused = false
    override fun isPaused(): Boolean = paused
}

private class FakeEvents : AutoUploadEvents {
    val progress = mutableListOf<AutoUploadItemProgress>()
    val successes = mutableListOf<String>()
    val limits = mutableListOf<Pair<String, String>>()
    var afterSuccess: (() -> Unit)? = null

    override suspend fun onProgress(progress: AutoUploadItemProgress) {
        this.progress += progress
    }

    override fun onUploadSucceeded(label: String) {
        successes += label
        afterSuccess?.invoke()
    }

    override fun onLimit(title: String, body: String) {
        limits += title to body
    }
}
