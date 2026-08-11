package looksee.angelll.com.services

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Composable
fun NegativeGalleryPicker(
    onPicked: (Uri) -> Unit,
    onInvalidDuration: (String) -> Unit,
    // Note: Android needs a manual callback for when the user hits the "Back" button to dismiss the picker
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 1. Setup the Android Photo Picker (Equivalent to PHPickerViewController)
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) {
            // User swiped down or hit back without picking a video
            onDismiss()
            return@rememberLauncherForActivityResult
        }

        // 2. Equivalent of Task { ... } -> Launching a coroutine off the main thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 3. Check Duration using MediaMetadataRetriever (AVAsset equivalent)
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val timeMs = timeStr?.toLongOrNull() ?: 0L
                val seconds = timeMs / 1000.0
                retriever.release()

                // 4. Equivalent of await MainActor.run { ... }
                withContext(Dispatchers.Main) {
                    if (seconds < 10.0) {
                        onInvalidDuration("Negative videos must be at least 10 seconds long.")
                        onDismiss()
                    } else if (seconds > 15.0) {
                        onInvalidDuration("Negative videos cannot be longer than 15 seconds.")
                        onDismiss()
                    } else {
                        // 5. Copy to temporary directory (FileManager equivalent)
                        // We do this on the Main dispatcher right before returning, or you can keep it in IO
                        val tempFile = File(context.cacheDir, "${UUID.randomUUID()}.mp4")

                        // Safely copy the file byte-by-byte
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        onPicked(Uri.fromFile(tempFile))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onInvalidDuration("Could not verify video length.")
                    onDismiss()
                }
            }
        }
    }

    // This automatically pops up the picker the second this view loads (just like iOS)
    LaunchedEffect(Unit) {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }
}