package looksee.angelll.com.uifiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun VideoPicker(
    useCamera: Boolean = true,
    onPicked: (Uri, LocationData?) -> Unit,
    onInvalidDuration: (String) -> Unit
) {
    val context = LocalContext.current
    val maxDuration = 60.0

    if (useCamera) {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraXVideoRecordingView(
                maxDurationSeconds = maxDuration,
                onVideoRecorded = { uri: Uri ->
                    val durationSeconds = getVideoDurationSeconds(context, uri)
                    if (durationSeconds > maxDuration) {
                        onInvalidDuration("Each clip must be 60 seconds or less.")
                    } else {
                        onPicked(uri, extractLocation(context, uri))
                    }
                }
            )

            GuidedCaptureOverlay(isNegative = false, isRecording = true)
        }
    } else {
        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                val durationSeconds = getVideoDurationSeconds(context, uri)

                if (durationSeconds <= 0) {
                    onInvalidDuration("Could not read video duration.")
                } else if (durationSeconds > maxDuration) {
                    onInvalidDuration("Each clip must be 60 seconds or less.")
                } else {
                    onPicked(uri, extractLocation(context, uri))
                }
            }
        }

        LaunchedEffect(Unit) {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }
}

// MARK: - Helper Metadata Functions

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

private fun extractLocation(context: Context, uri: Uri): LocationData? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val locationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)

        if (locationString != null) {
            parseIso6709Location(locationString)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private fun parseIso6709Location(locationStr: String): LocationData? {
    val regex = Regex("""([+-][0-9.]+)([+-][0-9.]+)""")
    val match = regex.find(locationStr)

    return if (match != null && match.groupValues.size >= 3) {
        val lat = match.groupValues[1].toDoubleOrNull()
        val lon = match.groupValues[2].toDoubleOrNull()
        if (lat != null && lon != null) {
            LocationData(latitude = lat, longitude = lon)
        } else null
    } else {
        null
    }
}