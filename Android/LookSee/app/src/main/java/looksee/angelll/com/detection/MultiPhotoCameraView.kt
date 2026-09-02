package looksee.angelll.com.detection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import looksee.angelll.com.models.CapturedNegativePhoto
import looksee.angelll.com.models.MultiPhotoCameraService

/** Compose equivalent of the iOS negative-reference multi-photo camera screen. */
@Composable
fun MultiPhotoCameraView(
    existingPhotos: List<CapturedNegativePhoto>,
    onDone: (List<CapturedNegativePhoto>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    minimumPhotoCount: Int = 5,
    maximumPhotoCount: Int = 10,
) {
    require(minimumPhotoCount in 0..maximumPhotoCount)

    val context = LocalContext.current
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
        ?: error("MultiPhotoCameraView must be hosted under a LifecycleOwner.")
    val cameraService = remember(context, existingPhotos, maximumPhotoCount) {
        MultiPhotoCameraService(
            context = context,
            initialPhotos = existingPhotos,
            maximumPhotoCount = maximumPhotoCount,
        )
    }

    val photos by cameraService.capturedPhotos.collectAsState()
    val isConfigured by cameraService.isConfigured.collectAsState()
    val isCapturing by cameraService.isCapturing.collectAsState()
    val errorMessage by cameraService.errorMessage.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) cameraService.reportPermissionDenied()
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner, cameraService) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                permissionGranted = nowGranted
                if (nowGranted) cameraService.clearError()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionGranted, previewView) {
        val view = previewView
        if (permissionGranted && view != null) {
            cameraService.start(lifecycleOwner, view)
        } else {
            cameraService.stop()
        }
    }

    DisposableEffect(cameraService) {
        onDispose { cameraService.close() }
    }

    val remainingRequired = (minimumPhotoCount - photos.size).coerceAtLeast(0)
    val hasMinimumPhotos = photos.size >= minimumPhotoCount
    val canCapture = isConfigured && !isCapturing && photos.size < maximumPhotoCount

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TopControls(
                photoCount = photos.size,
                maximumPhotoCount = maximumPhotoCount,
                onCancel = {
                    cameraService.discardNewPhotos()
                    onCancel()
                },
            )

            Spacer(modifier = Modifier.weight(1f))

            Instructions(remainingRequiredPhotos = remainingRequired)

            if (photos.isNotEmpty()) {
                PhotoStrip(
                    photos = photos,
                    onRemove = cameraService::removePhoto,
                )
            }

            BottomControls(
                canCapture = canCapture,
                isCapturing = isCapturing,
                canFinish = hasMinimumPhotos,
                onCapture = cameraService::capturePhoto,
                onDone = { onDone(photos) },
            )
        }

        errorMessage?.let { message ->
            CameraErrorOverlay(
                message = message,
                permissionDenied = !permissionGranted,
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                onClose = {
                    cameraService.discardNewPhotos()
                    onCancel()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TopControls(
    photoCount: Int,
    maximumPhotoCount: Int,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayButton(text = "Cancel", onClick = onCancel)
        Text(
            text = "$photoCount / $maximumPhotoCount",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Instructions(remainingRequiredPhotos: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("Capture Negative References", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            "Photograph the surrounding area, not the landmark itself.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (remainingRequiredPhotos > 0) {
                "$remainingRequiredPhotos more required"
            } else {
                "Minimum complete"
            },
            color = if (remainingRequiredPhotos > 0) Color.Yellow else Color(0xFF34C759),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PhotoStrip(
    photos: List<CapturedNegativePhoto>,
    onRemove: (CapturedNegativePhoto) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(photos, key = { it.id }) { photo ->
            Box {
                val bitmap = remember(photo.file, photo.file.lastModified()) {
                    decodeThumbnail(photo.file.absolutePath)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured negative reference",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color.DarkGray, RoundedCornerShape(10.dp)),
                    )
                }
                Button(
                    onClick = { onRemove(photo) },
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) {
                    Text("×", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun BottomControls(
    canCapture: Boolean,
    isCapturing: Boolean,
    canFinish: Boolean,
    onCapture: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = Modifier.width(90.dp))
        Button(
            onClick = onCapture,
            enabled = canCapture,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.45f),
            ),
            modifier = Modifier.size(76.dp),
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    color = Color.Black,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Button(
            onClick = onDone,
            enabled = canFinish,
            modifier = Modifier
                .width(90.dp)
                .height(52.dp),
        ) {
            Text("Done", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraErrorOverlay(
    message: String,
    permissionDenied: Boolean,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.90f), RoundedCornerShape(22.dp))
            .padding(28.dp),
    ) {
        Text("Camera Unavailable", color = Color.White, fontWeight = FontWeight.Bold)
        Text(message, color = Color.White, textAlign = TextAlign.Center)
        if (permissionDenied) {
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        }
        Button(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun OverlayButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.55f)),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

private fun decodeThumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > THUMBNAIL_PIXELS * 2 ||
        bounds.outHeight / sampleSize > THUMBNAIL_PIXELS * 2
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

private const val THUMBNAIL_PIXELS = 180
