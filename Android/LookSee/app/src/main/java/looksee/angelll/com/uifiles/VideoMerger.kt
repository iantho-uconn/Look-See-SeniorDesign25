package looksee.angelll.com.uifiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class VideoMergeException(message: String) : Exception(message) {
    class NoClips : VideoMergeException("No video clips were provided to merge.")
    class TrackCreationFailed : VideoMergeException("Could not prepare the video clips for merging.")
    class ExportSessionCreationFailed : VideoMergeException("Could not start merging the video clips.")
    class ExportFailed(reason: String) : VideoMergeException("Merging the video clips failed: $reason")
    class TooShort(actual: Double, minimum: Double) : VideoMergeException(
        "The combined video is only ${String.format(Locale.US, "%.1f", actual)}s long. It must be at least ${String.format(Locale.US, "%.0f", minimum)}s."
    )
}

object VideoMerger {

    suspend fun mergeAndValidate(
        context: Context,
        clipUris: List<Uri>,
        minimumDuration: Double = 15.0
    ): Uri {

        if (clipUris.isEmpty()) {
            throw VideoMergeException.NoClips()
        }

        if (clipUris.size == 1) {
            val durationSeconds = getVideoDurationSeconds(context, clipUris.first())
            if (durationSeconds < minimumDuration) {
                throw VideoMergeException.TooShort(actual = durationSeconds, minimum = minimumDuration)
            }
            return clipUris.first()
        }

        var totalDurationSeconds = 0.0
        for (uri in clipUris) {
            totalDurationSeconds += getVideoDurationSeconds(context, uri)
        }

        if (totalDurationSeconds < minimumDuration) {
            throw VideoMergeException.TooShort(actual = totalDurationSeconds, minimum = minimumDuration)
        }

        val outputDirectory = File(context.cacheDir, "merged_videos").apply { mkdirs() }
        val outputFile = File(outputDirectory, "${UUID.randomUUID()}.mp4")
        val outputUri = Uri.fromFile(outputFile)

        val editedMediaItems = clipUris.map { uri ->
            val mediaItem = MediaItem.fromUri(uri)
            EditedMediaItem.Builder(mediaItem).build()
        }
        val sequence = EditedMediaItemSequence(editedMediaItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        return suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_MP4)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuation.resume(outputUri)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        val reason = exportException.message ?: "Unknown export error."
                        continuation.resumeWithException(VideoMergeException.ExportFailed(reason))
                    }
                })
                .build()

            transformer.start(composition, outputFile.absolutePath)

            continuation.invokeOnCancellation {
                transformer.cancel()
            }
        }
    }

    private fun getVideoDurationSeconds(context: Context, uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            durationMs / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }
}