package looksee.angelll.com.models

import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

data class AutoUploadSession(
    val userEmail: String,
    val idToken: String,
    val hasActiveSubscription: Boolean,
    val tokenBalance: Int,
)

data class AutoUploadItemProgress(
    val mediaId: UUID,
    val label: String,
    val completedItems: Int,
    val totalItems: Int,
    val itemProgress: Double,
) {
    val overallProgress: Double
        get() = if (totalItems == 0) {
            0.0
        } else {
            ((completedItems + itemProgress.coerceIn(0.0, 1.0)) / totalItems)
                .coerceIn(0.0, 1.0)
        }
}

sealed interface AutoUploadRunResult {
    data class Completed(val uploadedCount: Int) : AutoUploadRunResult
    data class Paused(val uploadedCount: Int) : AutoUploadRunResult
    data object NotAuthenticated : AutoUploadRunResult
    data object SubscriptionRequired : AutoUploadRunResult
    data object TokensUnavailable : AutoUploadRunResult
    data class Retry(val uploadedCount: Int, val cause: Throwable) : AutoUploadRunResult
    data class Failed(val uploadedCount: Int, val cause: Throwable) : AutoUploadRunResult
}

internal interface AutoUploadArchive {
    fun pendingItems(): List<ArchivedMedia>
    fun positiveFile(media: ArchivedMedia): File
    fun negativeFile(media: ArchivedMedia): File?
    suspend fun delete(media: ArchivedMedia)
}

internal interface AutoUploadSessionProvider {
    suspend fun fetchSession(): AutoUploadSession?
}

internal interface AutoUploadPositiveUploader {
    suspend fun upload(
        media: ArchivedMedia,
        file: File,
        session: AutoUploadSession,
        label: String,
        landmarkId: String,
        onProgress: suspend (Double) -> Unit,
    ): PositiveSubmissionResult
}

internal interface AutoUploadNegativeUploader {
    suspend fun upload(
        file: File,
        landmarkId: String,
        idToken: String,
        onProgress: suspend (Double) -> Unit,
    )
}

internal interface AutoUploadPauseGate {
    fun isPaused(): Boolean
}

internal interface AutoUploadEvents {
    suspend fun onProgress(progress: AutoUploadItemProgress)
    fun onUploadSucceeded(label: String)
    fun onLimit(title: String, body: String)
}

internal class AutoUploadQueueEngine(
    private val archive: AutoUploadArchive,
    private val sessionProvider: AutoUploadSessionProvider,
    private val positiveUploader: AutoUploadPositiveUploader,
    private val negativeUploader: AutoUploadNegativeUploader,
    private val pauseGate: AutoUploadPauseGate,
    private val events: AutoUploadEvents,
    private val landmarkIdFactory: () -> String = {
        "landmark_${UUID.randomUUID().toString().take(8)}"
    },
) {
    suspend fun process(): AutoUploadRunResult {
        if (pauseGate.isPaused()) return AutoUploadRunResult.Paused(0)

        val pending = archive.pendingItems().sortedBy(ArchivedMedia::dateSaved)
        if (pending.isEmpty()) return AutoUploadRunResult.Completed(0)

        val session = try {
            sessionProvider.fetchSession()
        } catch (error: Throwable) {
            return failureResult(0, error)
        } ?: return AutoUploadRunResult.NotAuthenticated

        if (session.idToken.isBlank() || session.userEmail.isBlank()) {
            return AutoUploadRunResult.NotAuthenticated
        }

        var uploadedCount = 0
        var remainingTokens = session.tokenBalance

        for (media in pending) {
            if (pauseGate.isPaused()) return AutoUploadRunResult.Paused(uploadedCount)

            if (!session.hasActiveSubscription) {
                events.onLimit(
                    title = "Subscription Required",
                    body = "You need an active subscription or Free Trial to upload landmarks.",
                )
                return AutoUploadRunResult.SubscriptionRequired
            }

            if (remainingTokens <= 0) {
                events.onLimit(
                    title = "Out of Tokens",
                    body = "You need 1 token to upload a new landmark. " +
                        "Purchase a token pack in Settings.",
                )
                return AutoUploadRunResult.TokensUnavailable
            }

            val label = media.savedLabel?.takeIf { it.isNotBlank() } ?: media.title
            val requestedLandmarkId = media.landmarkId?.takeIf { it.isNotBlank() }
                ?: landmarkIdFactory()

            try {
                report(media, label, uploadedCount, pending.size, 0.0)
                val positive = positiveUploader.upload(
                    media = media,
                    file = archive.positiveFile(media),
                    session = session,
                    label = label,
                    landmarkId = requestedLandmarkId,
                ) { positiveProgress ->
                    report(
                        media,
                        label,
                        uploadedCount,
                        pending.size,
                        positiveProgress.coerceIn(0.0, 1.0) * POSITIVE_PROGRESS_WEIGHT,
                    )
                }

                val finalLandmarkId = positive.landmarkId ?: requestedLandmarkId
                val negative = archive.negativeFile(media)
                if (negative != null && negative.isFile) {
                    negativeUploader.upload(
                        file = negative,
                        landmarkId = finalLandmarkId,
                        idToken = session.idToken,
                    ) { negativeProgress ->
                        report(
                            media,
                            label,
                            uploadedCount,
                            pending.size,
                            POSITIVE_PROGRESS_WEIGHT +
                                negativeProgress.coerceIn(0.0, 1.0) * NEGATIVE_PROGRESS_WEIGHT,
                        )
                    }
                }

                report(media, label, uploadedCount, pending.size, 1.0)
                archive.delete(media)
                uploadedCount += 1
                remainingTokens -= 1
                events.onUploadSucceeded(label)
            } catch (error: Throwable) {
                return failureResult(uploadedCount, error)
            }
        }

        return AutoUploadRunResult.Completed(uploadedCount)
    }

    private suspend fun report(
        media: ArchivedMedia,
        label: String,
        completedItems: Int,
        totalItems: Int,
        itemProgress: Double,
    ) {
        events.onProgress(
            AutoUploadItemProgress(
                mediaId = media.id,
                label = label,
                completedItems = completedItems,
                totalItems = totalItems,
                itemProgress = itemProgress.coerceIn(0.0, 1.0),
            ),
        )
    }

    private fun failureResult(uploadedCount: Int, error: Throwable): AutoUploadRunResult =
        if (error.isRetryableAutoUploadFailure()) {
            AutoUploadRunResult.Retry(uploadedCount, error)
        } else {
            AutoUploadRunResult.Failed(uploadedCount, error)
        }

    private companion object {
        const val POSITIVE_PROGRESS_WEIGHT = 0.85
        const val NEGATIVE_PROGRESS_WEIGHT = 0.15
    }
}

internal fun Throwable.isRetryableAutoUploadFailure(): Boolean = when (this) {
    is UnknownHostException,
    is ConnectException,
    is SocketTimeoutException,
    is SocketException,
    -> true

    is PositiveUploadError.BadStatus -> code == 408 || code == 429 || code >= 500
    is HardNegativeUploadError.BadStatus -> code == 408 || code == 429 || code >= 500
    is AutoUploadStatsException -> statusCode == 408 || statusCode == 429 || statusCode >= 500
    else -> cause?.takeIf { it !== this }?.isRetryableAutoUploadFailure() ?: false
}
