package looksee.angelll.com.services

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import looksee.angelll.com.uifiles.VideoMerger

enum class AuthorizationStatus {
    NOT_DETERMINED, AUTHORIZED, DENIED
}

class NegativeVideoCameraService(private val context: Context) {

    // MARK: - State Properties
    var isRecording by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
    var authorizationStatus by mutableStateOf(AuthorizationStatus.NOT_DETERMINED)
    var isInterrupted by mutableStateOf(false)

    var videoCapture: VideoCapture<Recorder>? = null
        private set

    var camera: androidx.camera.core.Camera? = null
        private set

    private var activeRecording: Recording? = null
    private var segmentUris = mutableListOf<Uri>()
    private var isIntentionalStop = false
    private var wasRecordingBeforeInterruption = false

    var onVideoRecorded: ((Uri) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // MARK: - Setup & Permissions

    fun start(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        checkPermissionsAndStart(lifecycleOwner, surfaceProvider)
    }

    fun stop() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun checkPermissionsAndStart(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val cameraPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
        if (cameraPermission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            authorizationStatus = AuthorizationStatus.AUTHORIZED
            setupSession(lifecycleOwner, surfaceProvider)
        } else {
            authorizationStatus = AuthorizationStatus.DENIED
            errorMessage = "Camera access is denied. Please enable it in Settings."
        }
    }

    private fun setupSession(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview setup
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(surfaceProvider)
            }

            // Recorder setup (HD 1080p equivalent)
            val qualitySelector = QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Select best back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, videoCapture
                )
            } catch (exc: Exception) {
                errorMessage = "Unable to access the back camera."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // MARK: - Recording Controls

    fun startRecording() {
        if (isRecording) return
        segmentUris.clear()
        beginNewSegment()
    }

    private fun beginNewSegment() {
        val videoCapture = this.videoCapture ?: return

        val filename = "${UUID.randomUUID()}.mp4"
        val file = File(context.cacheDir, filename)

        val outputOptions = FileOutputOptions.Builder(file).build()

        // 🚀 Video only, no audio track, matching iOS implementation
        activeRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                handleRecordEvent(recordEvent)
            }

        isRecording = true
    }

    fun stopRecording() {
        if (!isRecording) return
        isIntentionalStop = true
        activeRecording?.stop()
        activeRecording = null
    }

    // MARK: - Event Handling

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isRecording = true
            }
            is VideoRecordEvent.Finalize -> {
                isRecording = false
                val wasIntentional = isIntentionalStop
                isIntentionalStop = false

                if (event.hasError()) {
                    errorMessage = "Failed to record video: ${event.cause?.localizedMessage}"
                    return
                }

                segmentUris.add(event.outputResults.outputUri)

                if (wasIntentional) {
                    coroutineScope.launch {
                        finishAndDeliverSegments()
                    }
                }
            }
        }
    }

    // MARK: - Interruption Handling (Call from UI Lifecycle)

    fun handleInterruptionBegan() {
        wasRecordingBeforeInterruption = isRecording
        isInterrupted = true
        activeRecording?.stop()
        activeRecording = null
    }

    fun handleInterruptionEnded() {
        isInterrupted = false
        if (wasRecordingBeforeInterruption) {
            wasRecordingBeforeInterruption = false
            // Small 0.3s delay matching iOS logic
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                resumeRecordingAfterInterruption()
            }, 300)
        }
    }

    private fun resumeRecordingAfterInterruption() {
        if (isRecording) return
        beginNewSegment()
    }

    // MARK: - Segment Merging

    private suspend fun finishAndDeliverSegments() {
        val uris = segmentUris.toList()
        segmentUris.clear()

        val first = uris.firstOrNull() ?: return

        if (uris.size == 1) {
            onVideoRecorded?.invoke(first)
            return
        }

        try {
            // 🚀 Routes safely through the Android VideoMerger
            val mergedUri = VideoMerger.mergeAndValidate(context, uris, 1.0)

            // Cleanup segments
            for (uri in uris) {
                try {
                    uri.path?.let { File(it).delete() }
                } catch (e: Exception) { e.printStackTrace() }
            }

            onVideoRecorded?.invoke(mergedUri)
        } catch (e: Exception) {
            onVideoRecorded?.invoke(first)
        }
    }
}