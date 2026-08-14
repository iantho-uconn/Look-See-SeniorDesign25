package looksee.angelll.com.services

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

enum class AVAuthorizationStatus { NOT_DETERMINED, AUTHORIZED, DENIED }

class NegativeVideoCameraService {
    val isRecording = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)
    val authorizationStatus = MutableStateFlow(AVAuthorizationStatus.NOT_DETERMINED)

    var onVideoRecorded: ((File) -> Unit)? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // Android needs a Context and LifecycleOwner to bind the camera securely
    @SuppressLint("MissingPermission")
    fun start(context: Context, lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // 🚀 THE FIX: Force maximum explicit resolution (UHD/4K falling back to HD)
                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD)
                )

                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                // Smart Camera Selector for virtual multi-lens arrays handled natively by DEFAULT_BACK_CAMERA
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    videoCapture
                )

                authorizationStatus.value = AVAuthorizationStatus.AUTHORIZED

            } catch (e: Exception) {
                errorMessage.value = "Unable to access the back camera."
                authorizationStatus.value = AVAuthorizationStatus.DENIED
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        // Unbinding is handled automatically by the lifecycle, but we stop active recordings
        stopRecording()
    }

    @SuppressLint("MissingPermission")
    fun startRecording(context: Context) {
        if (isRecording.value) return

        val videoCapture = this.videoCapture ?: return
        val tempDir = context.cacheDir
        val filename = "${UUID.randomUUID()}.mp4"
        val file = File(tempDir, filename)

        val outputOptions = FileOutputOptions.Builder(file).build()

        recording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        isRecording.value = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording.value = false
                        if (!recordEvent.hasError()) {
                            onVideoRecorded?.invoke(file)
                        } else {
                            errorMessage.value = "Failed to record video: ${recordEvent.error}"
                        }
                    }
                }
            }
    }

    fun stopRecording() {
        if (!isRecording.value) return
        recording?.stop()
        recording = null
        isRecording.value = false
    }
}