package looksee.angelll.com.models

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The prepared positive video and whether UploadService owns its temporary file. */
data class MergedVideo(
    val file: File,
    val deleteAfterUpload: Boolean,
)

/** Injectable boundary used by UploadService and its local unit tests. */
fun interface PositiveVideoMerger {
    suspend fun mergeAndValidate(
        clipFiles: List<File>,
        minimumDurationSeconds: Double,
    ): MergedVideo
}

sealed class VideoMergeError(message: String) : Exception(message) {
    data object NoClips : VideoMergeError("Please record at least one video before uploading.")
    data class MissingClip(val filename: String) :
        VideoMergeError("The video clip could not be found: $filename")

    data class DurationUnavailable(val filename: String) :
        VideoMergeError("LookSee could not read the duration of $filename.")

    data class TooShort(val actualSeconds: Double, val minimumSeconds: Double) :
        VideoMergeError(
            "Please record at least ${minimumSeconds.toInt()} seconds of video " +
                "(${actualSeconds.toInt()} seconds selected).",
        )

    data class ExportFailed(val reason: String) :
        VideoMergeError("The selected video clips could not be combined: $reason")
}

/**
 * Android counterpart to the iOS VideoMerger helper.
 *
 * One qualifying clip is returned unchanged. Multiple clips are exported in order to a
 * temporary MP4 with Media3 Transformer; UploadService deletes that temporary file afterward.
 */
class Media3VideoMerger(context: Context) : PositiveVideoMerger {
    private val applicationContext = context.applicationContext

    override suspend fun mergeAndValidate(
        clipFiles: List<File>,
        minimumDurationSeconds: Double,
    ): MergedVideo {
        if (clipFiles.isEmpty()) throw VideoMergeError.NoClips

        clipFiles.forEach { file ->
            if (!file.isFile) throw VideoMergeError.MissingClip(file.name)
        }

        val durationSeconds = withContext(Dispatchers.IO) {
            clipFiles.sumOf(::readDurationSeconds)
        }
        if (durationSeconds < minimumDurationSeconds) {
            throw VideoMergeError.TooShort(durationSeconds, minimumDurationSeconds)
        }

        if (clipFiles.size == 1) {
            return MergedVideo(file = clipFiles.single(), deleteAfterUpload = false)
        }

        val outputFile = File(
            applicationContext.cacheDir,
            "looksee_merged_${UUID.randomUUID()}.mp4",
        )
        exportComposition(clipFiles, outputFile)
        return MergedVideo(file = outputFile, deleteAfterUpload = true)
    }

    private fun readDurationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMillis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw VideoMergeError.DurationUnavailable(file.name)
            durationMillis / 1_000.0
        } finally {
            retriever.release()
        }
    }

    private suspend fun exportComposition(clipFiles: List<File>, outputFile: File) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val editedItems = clipFiles.map { file ->
                    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build()
                }
                val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
                val composition = Composition.Builder(sequence).build()

                lateinit var transformer: Transformer
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        outputFile.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                VideoMergeError.ExportFailed(
                                    exportException.message ?: "unknown export error",
                                ),
                            )
                        }
                    }
                }

                transformer = Transformer.Builder(applicationContext)
                    .addListener(listener)
                    .build()

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        transformer.cancel()
                        outputFile.delete()
                    }
                }

                try {
                    outputFile.delete()
                    transformer.start(composition, outputFile.absolutePath)
                } catch (error: Throwable) {
                    outputFile.delete()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            VideoMergeError.ExportFailed(error.message ?: "unknown export error"),
                        )
                    }
                }
            }
        }
    }
}
