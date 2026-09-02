package looksee.angelll.com.uifiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class VideoMergeError(message: String) : Exception(message) {
    object NoClips : VideoMergeError("No video clips were provided to merge.")
    object ExportSessionCreationFailed : VideoMergeError("Could not start merging the video clips.")
    class ExportFailed(reason: String) : VideoMergeError("Merging the video clips failed: $reason")
    class TooShort(val actual: Double, val minimum: Double) : VideoMergeError(
        "The combined video is only ${String.format("%.1f", actual)}s long. It must be at least ${String.format("%.0f", minimum)}s."
    )
}

object VideoMerger {

    suspend fun mergeAndValidate(
        context: Context,
        clipUris: List<Uri>,
        minimumDuration: Double = 1.0
    ): Uri {
        if (clipUris.isEmpty()) {
            throw VideoMergeError.NoClips
        }

        // If there's only one clip, just validate its length and return it.
        if (clipUris.size == 1) {
            val uri = clipUris[0]
            val durationSeconds = getVideoDurationInSeconds(context, uri)

            if (durationSeconds < minimumDuration) {
                throw VideoMergeError.TooShort(durationSeconds, minimumDuration)
            }
            return uri
        }

        // 🚀 Merge Multiple Clips using Media3 Transformer
        val outputFilename = "${UUID.randomUUID()}_merged.mp4"
        val outputFile = File(context.cacheDir, outputFilename)
        if (outputFile.exists()) outputFile.delete()

        val editedMediaItems = clipUris.map { uri ->
            EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()
        }

        // 🚀 THE FIX: Media3 updated the syntax to create sequences.
        // We use their new helper method to properly configure audio and video tracks!
        val sequence = androidx.media3.transformer.EditedMediaItemSequence.withAudioAndVideoFrom(editedMediaItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .build()

        // Wrap the asynchronous Transformer listener in a suspend coroutine
        suspendCancellableCoroutine<Unit> { continuation ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    continuation.resumeWithException(
                        VideoMergeError.ExportFailed(exportException.localizedMessage ?: "Unknown error")
                    )
                }
            })

            transformer.start(composition, outputFile.absolutePath)

            // Allow the coroutine to cancel the export if the parent scope dies
            continuation.invokeOnCancellation {
                transformer.cancel()
            }
        }

        // After successful export, validate the length
        val outputUri = Uri.fromFile(outputFile)
        val totalSeconds = getVideoDurationInSeconds(context, outputUri)

        if (totalSeconds < minimumDuration) {
            outputFile.delete() // Clean up the file since it failed validation
            throw VideoMergeError.TooShort(totalSeconds, minimumDuration)
        }

        return outputUri
    }

    private fun getVideoDurationInSeconds(context: Context, uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val millis = timeStr?.toLongOrNull() ?: 0L
            millis / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }
}