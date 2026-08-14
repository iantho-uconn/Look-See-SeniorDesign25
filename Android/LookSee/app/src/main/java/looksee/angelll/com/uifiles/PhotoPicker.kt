package looksee.angelll.com.uifiles

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun PhotoPicker(
    onPicked: (Bitmap) -> Unit,
    onDismiss: () -> Unit // Called when the user cancels or finishes taking the photo
) {
    // This launcher asks the Android OS to open the camera and return a Bitmap image
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            onPicked(bitmap)
        }
        onDismiss()
    }

    // Launch the camera exactly once as soon as this view appears (mirrors SwiftUI init)
    LaunchedEffect(Unit) {
        cameraLauncher.launch(null)
    }
}