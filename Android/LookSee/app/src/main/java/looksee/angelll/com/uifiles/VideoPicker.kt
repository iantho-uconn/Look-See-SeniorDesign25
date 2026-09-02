package looksee.angelll.com.uifiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

// Placeholder data class to map to CLLocationCoordinate2D
data class LocationCoordinate2D(val latitude: Double, val longitude: Double)

@Composable
fun VideoPicker(
    useCamera: Boolean = true,
    onPicked: (Uri, LocationCoordinate2D?) -> Unit,
    onInvalidDuration: (String) -> Unit
) {
    val context = LocalContext.current
    val maxDuration = 90.0

    // Temporary URI to store the camera's captured video
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            processPickedVideo(context, uri, maxDuration, onPicked, onInvalidDuration)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && cameraUri != null) {
            processPickedVideo(context, cameraUri!!, maxDuration, onPicked, onInvalidDuration)
        }
    }

    LaunchedEffect(useCamera) {
        if (useCamera) {
            // Set up a temporary file for the Android Camera Intent to write to
            val tmpFile = File(context.cacheDir, "LookSee_${UUID.randomUUID()}.mp4")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", tmpFile)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            galleryLauncher.launch("video/*")
        }
    }

    // In Compose, if useCamera is true and you wanted a custom overlay (like GuidedCaptureOverlay),
    // you would typically build a custom CameraX implementation here instead of an Intent launcher.
    Box(modifier = Modifier.fillMaxSize()) {
        // If useCamera is true, you would render your custom overlay here:
        // if (useCamera) { GuidedCaptureOverlay(isNegative = false, isRecording = true) }
    }
}

private fun processPickedVideo(
    context: Context,
    uri: Uri,
    maxDuration: Double,
    onPicked: (Uri, LocationCoordinate2D?) -> Unit,
    onInvalidDuration: (String) -> Unit
) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L
        val durationSeconds = durationMs / 1000.0

        if (durationSeconds <= 0.0) {
            onInvalidDuration("Could not read video duration.")
            return
        }

        // No per-clip minimum anymore — clips are combined and the
        // combined total is validated against the 15s minimum elsewhere.
        if (durationSeconds > maxDuration) {
            onInvalidDuration("Each clip must be 90 seconds or less.")
            return
        }

        // Extract original GPS location directly from the asset metadata
        var extractedLocation: LocationCoordinate2D? = null
        val locationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        if (locationStr != null) {
            // ISO-6709 format usually looks like: "+37.7749-122.4194/"
            val match = Regex("([+-][0-9.]+)([+-][0-9.]+)").find(locationStr)
            if (match != null) {
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) {
                    extractedLocation = LocationCoordinate2D(lat, lon)
                }
            }
        }

        onPicked(uri, extractedLocation)
    } catch (e: Exception) {
        onInvalidDuration("Could not read video data: ${e.localizedMessage}")
    } finally {
        retriever.release()
    }
}