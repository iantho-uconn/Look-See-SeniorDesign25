package looksee.angelll.com.models

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraX translation of the iOS multi-photo hard-negative capture service.
 *
 * The service owns only the photo capture session. It intentionally stays separate from the
 * detector camera coordinator so opening this screen cannot alter the active ML model.
 */
class MultiPhotoCameraService(
    context: Context,
    initialPhotos: List<CapturedNegativePhoto> = emptyList(),
    maximumPhotoCount: Int = 10,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val collection = MultiPhotoCaptureCollection(initialPhotos, maximumPhotoCount)
    private val bindingGeneration = AtomicInteger(0)
    private val captureGeneration = AtomicInteger(0)

    private val _capturedPhotos = MutableStateFlow(collection.photos)
    val capturedPhotos: StateFlow<List<CapturedNegativePhoto>> = _capturedPhotos.asStateFlow()

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var closed = false

    val canCaptureAnotherPhoto: Boolean
        get() = collection.canAdd(
            isConfigured = _isConfigured.value,
            isCapturing = _isCapturing.value,
        )

    /** Binds a rear-camera preview and high-quality JPEG capture to [lifecycleOwner]. */
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        if (closed) return
        val generation = bindingGeneration.incrementAndGet()

        previewView.post {
            if (closed || generation != bindingGeneration.get()) return@post

            val providerFuture = ProcessCameraProvider.getInstance(appContext)
            providerFuture.addListener(
                {
                    if (closed || generation != bindingGeneration.get()) return@addListener

                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setJpegQuality(JPEG_QUALITY)
                            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                            .build()

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        cameraProvider = provider
                        imageCapture = capture
                        _isConfigured.value = true
                        _errorMessage.value = null
                    } catch (error: Throwable) {
                        setError(
                            error.message ?: "The rear camera could not be configured.",
                        )
                    }
                },
                mainExecutor,
            )
        }
    }

    /** Captures one JPEG into the app cache and appends it only after CameraX saves it. */
    fun capturePhoto() {
        val capture = imageCapture ?: return
        if (!canCaptureAnotherPhoto) return

        val outputFile = try {
            makeOutputFile()
        } catch (error: Exception) {
            setError("Could not prepare the photo file: ${error.message}")
            return
        }

        _isCapturing.value = true
        _errorMessage.value = null
        val generation = captureGeneration.incrementAndGet()

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outputFile).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (closed || generation != captureGeneration.get()) {
                        outputFile.delete()
                        _isCapturing.value = false
                        return
                    }
                    val photo = CapturedNegativePhoto(file = outputFile)
                    if (!collection.add(photo)) {
                        photo.deleteLocalFile()
                    }
                    publishPhotos()
                    _isCapturing.value = false
                    _errorMessage.value = null
                }

                override fun onError(exception: ImageCaptureException) {
                    outputFile.delete()
                    if (closed || generation != captureGeneration.get()) {
                        _isCapturing.value = false
                        return
                    }
                    setError("Photo capture failed: ${exception.message}")
                }
            },
        )
    }

    fun removePhoto(photo: CapturedNegativePhoto) {
        collection.remove(photo.id)?.deleteLocalFile()
        publishPhotos()
    }

    /** Retains incoming photos and deletes only images captured during this screen session. */
    fun discardNewPhotos() {
        captureGeneration.incrementAndGet()
        collection.discardNew().forEach(CapturedNegativePhoto::deleteLocalFile)
        publishPhotos()
    }

    /** Unbinds the camera while keeping the service reusable. */
    fun stop() {
        bindingGeneration.incrementAndGet()
        captureGeneration.incrementAndGet()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        _isConfigured.value = false
        _isCapturing.value = false
    }

    fun reportPermissionDenied() {
        setError(
            "Camera access is disabled. Enable camera access in Settings to capture negative photos.",
        )
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
    }

    private fun makeOutputFile(): File {
        val directory = File(appContext.cacheDir, OUTPUT_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "The negative-photo cache directory could not be created."
        }
        return File(directory, "negative_${UUID.randomUUID()}.jpg")
    }

    private fun publishPhotos() {
        _capturedPhotos.value = collection.photos
    }

    private fun setError(message: String) {
        _errorMessage.value = message
        _isConfigured.value = false
        _isCapturing.value = false
    }

    private companion object {
        const val JPEG_QUALITY = 88
        const val OUTPUT_DIRECTORY = "looksee-hard-negatives"
    }
}

/** Pure collection rules shared by the CameraX callbacks and local JVM tests. */
internal class MultiPhotoCaptureCollection(
    initialPhotos: List<CapturedNegativePhoto>,
    private val maximumPhotoCount: Int,
) {
    init {
        require(maximumPhotoCount >= 0) { "maximumPhotoCount cannot be negative." }
    }

    private val originalPhotoIds = initialPhotos.mapTo(mutableSetOf()) { it.id }
    private val mutablePhotos = initialPhotos.toMutableList()

    val photos: List<CapturedNegativePhoto>
        get() = mutablePhotos.toList()

    fun canAdd(isConfigured: Boolean, isCapturing: Boolean): Boolean =
        isConfigured && !isCapturing && mutablePhotos.size < maximumPhotoCount

    fun add(photo: CapturedNegativePhoto): Boolean {
        if (mutablePhotos.size >= maximumPhotoCount) return false
        mutablePhotos += photo
        return true
    }

    fun remove(id: UUID): CapturedNegativePhoto? {
        val index = mutablePhotos.indexOfFirst { it.id == id }
        if (index < 0) return null
        return mutablePhotos.removeAt(index)
    }

    fun discardNew(): List<CapturedNegativePhoto> {
        val removed = mutablePhotos.filterNot { it.id in originalPhotoIds }
        mutablePhotos.removeAll(removed.toSet())
        return removed
    }
}
