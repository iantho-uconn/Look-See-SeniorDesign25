package looksee.angelll.com.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NegativeVideoCameraService(private val context: Context) {

    // Published State Equivalents
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _authorizationStatus = MutableStateFlow(false)
    val authorizationStatus: StateFlow<Boolean> = _authorizationStatus.asStateFlow()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Dedicated queue for hardware operations prevents Main Thread freezing
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var onVideoRecorded: ((Uri) -> Unit)? = null

    fun start(lifecycleOwner: LifecycleOwner, provider: ProcessCameraProvider) {
        checkPermissionsAndStart(lifecycleOwner, provider)
    }

    fun stop() {
        cameraExecutor.shutdown()
        stopRecording()
    }

    private fun checkPermissionsAndStart(lifecycleOwner: LifecycleOwner, provider: ProcessCameraProvider) {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        _authorizationStatus.value = hasPermission

        if (hasPermission) {
            setupSession(lifecycleOwner, provider)
        } else {
            _errorMessage.value = "Camera access is denied. Please enable it in Settings."
        }
    }

    private fun setupSession(lifecycleOwner: LifecycleOwner, provider: ProcessCameraProvider) {
        try {
            // Hardware configuration pushed off the UI thread via cameraExecutor
            val qualitySelector = QualitySelector.from(Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.FHD))
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(cameraExecutor)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)
        } catch (e: Exception) {
            _errorMessage.value = "Unable to access the back camera: ${e.localizedMessage}"
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        val capture = videoCapture ?: return

        val tempDir = context.cacheDir
        val filename = UUID.randomUUID().toString() + ".mp4"
        val file = File(tempDir, filename)

        val outputOptions = FileOutputOptions.Builder(file).build()

        // Missing audio permission gracefully handled by CameraX if omitted,
        // but we explicitly suppress the check here since we handled it in checkPermissions
        @Suppress("MissingPermission")
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        _isRecording.value = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        if (!recordEvent.hasError()) {
                            onVideoRecorded?.invoke(recordEvent.outputResults.outputUri)
                        } else {
                            _errorMessage.value = "Failed to record video: ${recordEvent.cause?.message}"
                        }
                    }
                }
            }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        activeRecording?.stop()
        activeRecording = null
    }
}