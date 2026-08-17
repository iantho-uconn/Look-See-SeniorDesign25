package looksee.angelll.com.detection

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import looksee.angelll.com.models.ActiveModelRelease
import looksee.angelll.com.models.ClusterLandmarkManifest
import looksee.angelll.com.models.LandmarkManifestEntry
import looksee.angelll.com.models.LandmarkManifestStore
import looksee.angelll.com.models.ModelSelector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectionSize(val width: Int, val height: Int)

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersects(other: DetectionBox): Boolean =
        left < other.right && other.left < right &&
                top < other.bottom && other.top < bottom
}

data class Detection(
    val clusterId: String,
    val modelVersion: String,
    val modelIdentifier: String,
    val classIndex: Int,
    val classCount: Int,
    val confidence: Float,
    val bbox: DetectionBox,
    val id: String = UUID.randomUUID().toString(),
    val displayLabelOverride: String? = null,
) {
    val releaseIdentifier: String get() = "$clusterId|$modelVersion"
    val label: String get() = classIndex.toString()

    fun landmarkEntry(
        store: LandmarkManifestStore = LandmarkManifestStore.shared,
    ): LandmarkManifestEntry? = store.resolve(clusterId, modelVersion, classIndex)

    fun displayLabel(
        store: LandmarkManifestStore = LandmarkManifestStore.shared,
    ): String = displayLabelOverride ?: landmarkEntry(store)?.label ?: "Class $classIndex"
}

class BoundingBoxSmoother(private val maxFrames: Int = 4) {
    private val history = ArrayDeque<DetectionBox>()

    init {
        require(maxFrames > 0) { "maxFrames must be positive." }
    }

    @Synchronized
    fun smooth(newBox: DetectionBox): DetectionBox {
        history.addLast(newBox)
        if (history.size > maxFrames) history.removeFirst()

        val count = history.size.toFloat()
        return DetectionBox(
            left = history.sumOf { it.left.toDouble() }.toFloat() / count,
            top = history.sumOf { it.top.toDouble() }.toFloat() / count,
            right = history.sumOf { it.right.toDouble() }.toFloat() / count,
            bottom = history.sumOf { it.bottom.toDouble() }.toFloat() / count,
        )
    }

    @Synchronized
    fun reset() = history.clear()
}

data class DetectorFrame(
    val width: Int,
    val height: Int,
    /** One Android ARGB color per source pixel, in row-major order. */
    val argbPixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        require(argbPixels.size == width * height) {
            "Expected ${width * height} ARGB pixels, received ${argbPixels.size}."
        }
    }
}

internal data class LetterboxMetadata(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
)

internal data class PreparedDetectorFrame(
    val normalizedRgb: FloatArray,
    val letterbox: LetterboxMetadata,
)

sealed interface DetectorModelOutput {
    data class EndToEnd(
        val values: FloatArray,
        val shape: IntArray,
    ) : DetectorModelOutput

    data class Split(
        val confidence: FloatArray,
        val confidenceShape: IntArray,
        val coordinates: FloatArray,
        val coordinatesShape: IntArray,
    ) : DetectorModelOutput
}

interface DetectorModel : Closeable {
    val inputWidth: Int
    val inputHeight: Int
    fun infer(normalizedRgb: FloatArray): DetectorModelOutput
}

fun interface DetectorModelFactory {
    fun load(release: ActiveModelRelease): DetectorModel
}

sealed interface DetectorLoadState {
    data object WaitingForRelease : DetectorLoadState
    data class Loading(val releaseIdentifier: String) : DetectorLoadState
    data class Ready(val releaseIdentifier: String) : DetectorLoadState
    data class Failed(val releaseIdentifier: String, val message: String) : DetectorLoadState
}

private data class LoadedDetectorRelease(
    val release: ActiveModelRelease,
    val manifest: ClusterLandmarkManifest,
    val model: DetectorModel,
)

/**
 * Release-aware LookSee detector.
 *
 * CameraPreview supplies source ARGB frames. Detector performs the same
 * letterboxing, thresholding, three-frame confirmation, four-frame smoothing,
 * proximity filtering, and six-second notification cooldown as the Swift
 * implementation. Bounding boxes remain in source-image coordinates; Checkpoint
 * 7 maps them into CameraX PreviewView coordinates.
 */
class Detector internal constructor(
    activeReleases: StateFlow<ActiveModelRelease?>,
    private val manifestStore: LandmarkManifestStore = LandmarkManifestStore.shared,
    private val modelFactory: DetectorModelFactory = LiteRtDetectorModelFactory(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    observeActiveReleases: Boolean = true,
    private val allowSyntheticPreview: Boolean = false,
) : AutoCloseable {
    constructor(
        modelSelector: ModelSelector,
        allowSyntheticPreview: Boolean = false,
    ) : this(
        activeReleases = modelSelector.activeRelease,
        allowSyntheticPreview = allowSyntheticPreview,
    )

    private val detectorScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inferenceDispatcher = dispatcher
    private val inferenceMutex = Mutex()
    private val loadedRelease = AtomicReference<LoadedDetectorRelease?>(null)
    private val engineLock = Any()

    private val frameCounters = mutableMapOf<String, Int>()
    private val smoothers = mutableMapOf<String, BoundingBoxSmoother>()
    private val notificationCooldowns = mutableMapOf<String, Long>()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _newlyDetectedLandmark = MutableStateFlow<Detection?>(null)
    val newlyDetectedLandmark: StateFlow<Detection?> =
        _newlyDetectedLandmark.asStateFlow()

    private val _currentLabel = MutableStateFlow<String?>(null)
    val currentLabel: StateFlow<String?> = _currentLabel.asStateFlow()

    private val _lastInferenceMs = MutableStateFlow(0.0)
    val lastInferenceMs: StateFlow<Double> = _lastInferenceMs.asStateFlow()

    private val _bufferSize = MutableStateFlow(DetectionSize(0, 0))
    val bufferSize: StateFlow<DetectionSize> = _bufferSize.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _classLabels = MutableStateFlow<List<String>>(emptyList())
    val classLabels: StateFlow<List<String>> = _classLabels.asStateFlow()

    private val _hideBoundingBoxes = MutableStateFlow(false)
    val hideBoundingBoxes: StateFlow<Boolean> = _hideBoundingBoxes.asStateFlow()

    private val _loadState = MutableStateFlow<DetectorLoadState>(
        DetectorLoadState.WaitingForRelease,
    )
    val loadState: StateFlow<DetectorLoadState> = _loadState.asStateFlow()

    private val _isSyntheticPreviewEnabled = MutableStateFlow(false)
    val isSyntheticPreviewEnabled: StateFlow<Boolean> =
        _isSyntheticPreviewEnabled.asStateFlow()

    @Volatile
    var dynamicSafeZone: DetectionBox? = null

    @Volatile
    var proximityThresholdMeters: Double = DEFAULT_PROXIMITY_THRESHOLD_METERS
        set(value) {
            require(value.isFinite() && value >= 0.0) {
                "proximityThresholdMeters must be finite and non-negative."
            }
            field = value
        }

    @Volatile
    private var userLocation: DetectorLocation? = null

    init {
        if (observeActiveReleases) {
            detectorScope.launch {
                var observedReleaseIdentifier: String? = null
                activeReleases.collect { release ->
                    if (release != null &&
                        release.releaseIdentifier != observedReleaseIdentifier
                    ) {
                        observedReleaseIdentifier = release.releaseIdentifier
                        activateRelease(release)
                    }
                }
            }
        }
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun setHideBoundingBoxes(hidden: Boolean) {
        _hideBoundingBoxes.value = hidden
    }

    /**
     * Enables a debug-only overlay fixture without loading or executing a model.
     *
     * Production Detector instances ignore attempts to enable it. This gives the
     * emulator a way to verify CameraX-to-preview coordinate mapping and overlay
     * rendering while the real landmark model is unavailable.
     */
    fun setSyntheticPreviewEnabled(enabled: Boolean) {
        val shouldEnable = allowSyntheticPreview && enabled
        if (_isSyntheticPreviewEnabled.value == shouldEnable) return
        _isSyntheticPreviewEnabled.value = shouldEnable
        resetEngine()
    }

    fun updateUserLocation(latitude: Double, longitude: Double, accuracyMeters: Double) {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters <= 0.0 ||
            accuracyMeters > MAX_LOCATION_ACCURACY_METERS
        ) {
            return
        }
        userLocation = DetectorLocation(latitude, longitude)
    }

    fun clearUserLocation() {
        userLocation = null
    }

    fun consumeNewlyDetectedLandmark() {
        _newlyDetectedLandmark.value = null
    }

    fun resetEngine() {
        synchronized(engineLock) {
            frameCounters.clear()
            smoothers.values.forEach(BoundingBoxSmoother::reset)
            smoothers.clear()
        }
        _detections.value = emptyList()
        _currentLabel.value = null
        _newlyDetectedLandmark.value = null
    }

    /** Processes at most one camera frame at a time. Extra CameraX frames are dropped. */
    suspend fun process(frame: DetectorFrame) {
        if (_isPaused.value || !inferenceMutex.tryLock()) return

        try {
            withContext(inferenceDispatcher) {
                _bufferSize.value = DetectionSize(frame.width, frame.height)

                if (_isSyntheticPreviewEnabled.value) {
                    _detections.value = syntheticPreviewDetections(frame.width, frame.height)
                    _currentLabel.value = "Overlay test"
                    _lastInferenceMs.value = 0.0
                    return@withContext
                }

                val loaded = loadedRelease.get() ?: return@withContext
                val startedAt = System.nanoTime()

                val prepared = letterbox(
                    frame = frame,
                    inputWidth = loaded.model.inputWidth,
                    inputHeight = loaded.model.inputHeight,
                )
                val output = loaded.model.infer(prepared.normalizedRgb)
                publishOutput(
                    output = output,
                    metadata = prepared.letterbox,
                    loaded = loaded,
                    eventTimeMillis = nowMillis(),
                )

                _lastInferenceMs.value =
                    (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            }
        } catch (error: Exception) {
            val releaseId = loadedRelease.get()?.release?.releaseIdentifier ?: "none"
            logger.severe("Detector inference failed for $releaseId: ${error.message}")
        } finally {
            // Matches the Swift detector's short post-inference throttle window.
            delay(POST_INFERENCE_THROTTLE_MILLIS)
            inferenceMutex.unlock()
        }
    }

    private fun syntheticPreviewDetections(width: Int, height: Int): List<Detection> = listOf(
        Detection(
            clusterId = "debug",
            modelVersion = "synthetic",
            modelIdentifier = "overlay-test",
            classIndex = 0,
            classCount = 1,
            confidence = 1f,
            displayLabelOverride = "Overlay test",
            bbox = DetectionBox(
                left = width * 0.22f,
                top = height * 0.24f,
                right = width * 0.78f,
                bottom = height * 0.72f,
            ),
        ),
    )

    internal suspend fun activateRelease(release: ActiveModelRelease) {
        _loadState.value = DetectorLoadState.Loading(release.releaseIdentifier)

        try {
            val numericClusterId = release.clusterId.toIntOrNull()
                ?: error("Detector requires a numeric clusterId: ${release.clusterId}.")
            val manifest = manifestStore.load(release.manifestFile)

            require(manifest.clusterId == numericClusterId) {
                "Manifest cluster ${manifest.clusterId} does not match $numericClusterId."
            }
            require(manifest.trainingRunId == release.modelVersion) {
                "Manifest version ${manifest.trainingRunId} does not match " +
                        "${release.modelVersion}."
            }
            require(manifest.classCount == release.classCount) {
                "Manifest classCount ${manifest.classCount} does not match " +
                        "${release.classCount}."
            }

            val newModel = modelFactory.load(release)
            val newLoadedRelease = LoadedDetectorRelease(release, manifest, newModel)
            val previous = loadedRelease.getAndSet(newLoadedRelease)
            previous?.model?.close()

            // Swift currently exposes classLabels without populating it. Keep
            // that behavior for the first parity port; see README follow-ups.
            _loadState.value = DetectorLoadState.Ready(release.releaseIdentifier)
            logger.info("Detector hot-swapped to ${release.releaseIdentifier}.")
        } catch (error: Exception) {
            _loadState.value = DetectorLoadState.Failed(
                releaseIdentifier = release.releaseIdentifier,
                message = error.message ?: "Unknown detector model-load error.",
            )
            logger.severe(
                "Detector model load failed for ${release.releaseIdentifier}: " +
                        error.message,
            )
        }
    }

    private fun publishOutput(
        output: DetectorModelOutput,
        metadata: LetterboxMetadata,
        loaded: LoadedDetectorRelease,
        eventTimeMillis: Long,
    ) {
        val parsed = when (output) {
            is DetectorModelOutput.EndToEnd -> parseEndToEndDetections(
                output = output,
                metadata = metadata,
                release = loaded.release,
            )

            is DetectorModelOutput.Split -> parseSplitDetections(
                output = output,
                metadata = metadata,
                release = loaded.release,
            )
        }
        val nearby = proximityFilter(parsed, loaded.manifest)
        val confirmed = confirmAndSmooth(nearby)
        val strongest = confirmed.maxByOrNull(Detection::confidence)

        _detections.value = if (_hideBoundingBoxes.value) emptyList() else confirmed
        _currentLabel.value = strongest?.displayLabel(manifestStore)

        if (strongest != null) {
            val cooldownKey = strongest.displayLabel(manifestStore)
            val lastNotifiedAt = notificationCooldowns[cooldownKey] ?: Long.MIN_VALUE
            val elapsed = if (lastNotifiedAt == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                eventTimeMillis - lastNotifiedAt
            }
            if (elapsed > NOTIFICATION_COOLDOWN_MILLIS) {
                notificationCooldowns[cooldownKey] = eventTimeMillis
                _newlyDetectedLandmark.value = strongest
            }
        }
    }

    private fun parseEndToEndDetections(
        output: DetectorModelOutput.EndToEnd,
        metadata: LetterboxMetadata,
        release: ActiveModelRelease,
    ): List<Detection> {
        val boxSize = output.shape.lastOrNull() ?: return emptyList()
        if (boxSize != END_TO_END_BOX_SIZE || output.values.size % boxSize != 0) {
            logger.warning("Unsupported end-to-end output shape ${output.shape.contentToString()}.")
            return emptyList()
        }

        val detections = mutableListOf<Detection>()
        val boxCount = output.values.size / boxSize
        repeat(boxCount) { boxIndex ->
            val offset = boxIndex * boxSize
            val score = output.values[offset + 4]
            val classIndex = output.values[offset + 5].toInt()
            if (score < CONFIDENCE_THRESHOLD || classIndex !in 0 until release.classCount) {
                return@repeat
            }

            var x1 = output.values[offset]
            var y1 = output.values[offset + 1]
            var x2 = output.values[offset + 2]
            var y2 = output.values[offset + 3]
            if (x1 <= 1f) x1 *= metadata.inputWidth
            if (x2 <= 1f) x2 *= metadata.inputWidth
            if (y1 <= 1f) y1 *= metadata.inputHeight
            if (y2 <= 1f) y2 *= metadata.inputHeight

            makeDetection(
                centerX = (x1 + x2) / 2f,
                centerY = (y1 + y2) / 2f,
                width = x2 - x1,
                height = y2 - y1,
                score = score,
                classIndex = classIndex,
                metadata = metadata,
                release = release,
            )?.let(detections::add)
        }
        return detections
    }

    private fun parseSplitDetections(
        output: DetectorModelOutput.Split,
        metadata: LetterboxMetadata,
        release: ActiveModelRelease,
    ): List<Detection> {
        val numClasses = output.confidenceShape.lastOrNull() ?: return emptyList()
        val numDetections = output.coordinates.size / COORDINATE_VALUE_COUNT
        if (numClasses <= 0 || output.confidence.size < numDetections * numClasses) {
            logger.warning("Invalid split detector output shapes.")
            return emptyList()
        }

        val classesToInspect = min(numClasses, release.classCount)
        val detections = mutableListOf<Detection>()
        repeat(numDetections) { detectionIndex ->
            var bestScore = 0f
            var bestClass = 0
            repeat(classesToInspect) { classIndex ->
                val score = output.confidence[detectionIndex * numClasses + classIndex]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            if (bestScore < CONFIDENCE_THRESHOLD) return@repeat

            val offset = detectionIndex * COORDINATE_VALUE_COUNT
            var centerX = output.coordinates[offset]
            var centerY = output.coordinates[offset + 1]
            var width = output.coordinates[offset + 2]
            var height = output.coordinates[offset + 3]
            if (centerX <= 1f) centerX *= metadata.inputWidth
            if (width <= 1f) width *= metadata.inputWidth
            if (centerY <= 1f) centerY *= metadata.inputHeight
            if (height <= 1f) height *= metadata.inputHeight

            makeDetection(
                centerX = centerX,
                centerY = centerY,
                width = width,
                height = height,
                score = bestScore,
                classIndex = bestClass,
                metadata = metadata,
                release = release,
            )?.let(detections::add)
        }
        return detections
    }

    private fun makeDetection(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        score: Float,
        classIndex: Int,
        metadata: LetterboxMetadata,
        release: ActiveModelRelease,
    ): Detection? {
        if (width <= 0f || height <= 0f || metadata.scale <= 0f) return null

        val sourceCenterX = (centerX - metadata.padX) / metadata.scale
        val sourceCenterY = (centerY - metadata.padY) / metadata.scale
        val sourceWidth = width / metadata.scale
        val sourceHeight = height / metadata.scale
        val box = DetectionBox(
            left = sourceCenterX - sourceWidth / 2f,
            top = sourceCenterY - sourceHeight / 2f,
            right = sourceCenterX + sourceWidth / 2f,
            bottom = sourceCenterY + sourceHeight / 2f,
        )

        val safeZone = dynamicSafeZone ?: DetectionBox(
            left = 0f,
            top = 0f,
            right = metadata.sourceWidth.toFloat(),
            bottom = metadata.sourceHeight.toFloat(),
        )
        if (!box.intersects(safeZone)) return null

        return Detection(
            clusterId = release.clusterId,
            modelVersion = release.modelVersion,
            modelIdentifier = release.modelKey ?: "ota-model",
            classIndex = classIndex,
            classCount = release.classCount,
            confidence = score,
            bbox = box,
        )
    }

    private fun proximityFilter(
        detections: List<Detection>,
        manifest: ClusterLandmarkManifest,
    ): List<Detection> {
        val location = userLocation ?: return detections
        return detections.filter { detection ->
            val landmark = manifest.landmark(detection.classIndex) ?: return@filter true
            distanceMeters(
                location,
                DetectorLocation(landmark.latitude, landmark.longitude),
            ) <= proximityThresholdMeters
        }
    }

    private fun confirmAndSmooth(detections: List<Detection>): List<Detection> =
        synchronized(engineLock) {
            val currentLabels = detections.mapTo(mutableSetOf(), Detection::label)
            val lostLabels = smoothers.keys.filterNot(currentLabels::contains)
            lostLabels.forEach { label ->
                smoothers.remove(label)
                frameCounters.remove(label)
            }

            detections.mapNotNull { detection ->
                val label = detection.label
                val frameCount = (frameCounters[label] ?: 0) + 1
                frameCounters[label] = frameCount
                val smoother = smoothers.getOrPut(label) { BoundingBoxSmoother() }
                val smoothedBox = smoother.smooth(detection.bbox)

                if (frameCount >= REQUIRED_FRAMES_FOR_DETECTION) {
                    detection.copy(bbox = smoothedBox)
                } else {
                    null
                }
            }
        }

    internal fun processOutputForTesting(
        output: DetectorModelOutput,
        metadata: LetterboxMetadata,
        release: ActiveModelRelease,
        manifest: ClusterLandmarkManifest,
        eventTimeMillis: Long,
    ) {
        publishOutput(
            output = output,
            metadata = metadata,
            loaded = LoadedDetectorRelease(release, manifest, NoOpDetectorModel),
            eventTimeMillis = eventTimeMillis,
        )
    }

    override fun close() {
        detectorScope.cancel()
        loadedRelease.getAndSet(null)?.model?.close()
    }

    companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 640
        const val CONFIDENCE_THRESHOLD = 0.80f
        const val IOU_THRESHOLD = 0.45f
        const val REQUIRED_FRAMES_FOR_DETECTION = 3
        const val DEFAULT_PROXIMITY_THRESHOLD_METERS = 150.0
        const val MAX_LOCATION_ACCURACY_METERS = 100.0
        const val NOTIFICATION_COOLDOWN_MILLIS = 6_000L

        private const val END_TO_END_BOX_SIZE = 6
        private const val COORDINATE_VALUE_COUNT = 4
        private const val POST_INFERENCE_THROTTLE_MILLIS = 30L
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private val logger = Logger.getLogger(Detector::class.java.name)

        @Volatile
        private var sharedInstance: Detector? = null

        fun shared(context: Context): Detector =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: Detector(ModelSelector.shared(context.applicationContext))
                    .also { sharedInstance = it }
            }

        internal fun letterbox(
            frame: DetectorFrame,
            inputWidth: Int,
            inputHeight: Int,
        ): PreparedDetectorFrame {
            require(inputWidth > 0 && inputHeight > 0)
            val scale = min(
                inputWidth.toFloat() / frame.width,
                inputHeight.toFloat() / frame.height,
            )
            val scaledWidth = frame.width * scale
            val scaledHeight = frame.height * scale
            val padX = (inputWidth - scaledWidth) / 2f
            val padY = (inputHeight - scaledHeight) / 2f
            val normalizedRgb = FloatArray(inputWidth * inputHeight * 3)

            for (outputY in 0 until inputHeight) {
                val sourceY = floor(((outputY - padY) / scale).toDouble()).toInt()
                if (sourceY !in 0 until frame.height) continue
                for (outputX in 0 until inputWidth) {
                    val sourceX = floor(((outputX - padX) / scale).toDouble()).toInt()
                    if (sourceX !in 0 until frame.width) continue

                    val argb = frame.argbPixels[sourceY * frame.width + sourceX]
                    val outputOffset = (outputY * inputWidth + outputX) * 3
                    normalizedRgb[outputOffset] = ((argb ushr 16) and 0xFF) / 255f
                    normalizedRgb[outputOffset + 1] = ((argb ushr 8) and 0xFF) / 255f
                    normalizedRgb[outputOffset + 2] = (argb and 0xFF) / 255f
                }
            }

            return PreparedDetectorFrame(
                normalizedRgb = normalizedRgb,
                letterbox = LetterboxMetadata(
                    sourceWidth = frame.width,
                    sourceHeight = frame.height,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    scale = scale,
                    padX = padX,
                    padY = padY,
                ),
            )
        }

        private fun distanceMeters(from: DetectorLocation, to: DetectorLocation): Double {
            val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)
            val haversine =
                sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                        cos(fromLatitude) * cos(toLatitude) *
                        sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            val bounded = haversine.coerceIn(0.0, 1.0)
            return EARTH_RADIUS_METERS *
                    2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
        }
    }
}

private data class DetectorLocation(val latitude: Double, val longitude: Double)

private object NoOpDetectorModel : DetectorModel {
    override val inputWidth = Detector.INPUT_WIDTH
    override val inputHeight = Detector.INPUT_HEIGHT
    override fun infer(normalizedRgb: FloatArray): DetectorModelOutput =
        error("NoOpDetectorModel cannot run inference.")
    override fun close() = Unit
}

/** LiteRT Interpreter adapter for float32 YOLO exports. */
class LiteRtDetectorModelFactory(
    private val numberOfThreads: Int = max(2, Runtime.getRuntime().availableProcessors() / 2),
) : DetectorModelFactory {
    override fun load(release: ActiveModelRelease): DetectorModel {
        val options = Interpreter.Options()
            .setNumThreads(numberOfThreads)
            .setUseXNNPACK(true)
        return LiteRtDetectorModel(Interpreter(release.modelFile, options))
    }
}

private class LiteRtDetectorModel(
    private val interpreter: Interpreter,
) : DetectorModel {
    private val inputTensors = (0 until interpreter.inputTensorCount).map(
        interpreter::getInputTensor,
    )
    private val imageInputIndex = inputTensors.indexOfFirst { tensor ->
        tensor.dataType() == DataType.FLOAT32 && tensor.shape().size == 4
    }.also { require(it >= 0) { "LiteRT model has no float32 rank-4 image input." } }
    private val imageShape = inputTensors[imageInputIndex].shape()
    private val isNhwc = imageShape.last() == 3

    override val inputHeight: Int = if (isNhwc) imageShape[1] else imageShape[2]
    override val inputWidth: Int = if (isNhwc) imageShape[2] else imageShape[3]

    init {
        require((isNhwc && imageShape[3] == 3) || (!isNhwc && imageShape[1] == 3)) {
            "LiteRT detector image input must be NHWC or NCHW RGB; " +
                    "received ${imageShape.contentToString()}."
        }
        require(inputWidth == Detector.INPUT_WIDTH && inputHeight == Detector.INPUT_HEIGHT) {
            "LookSee detector expects 640x640 input; received ${inputWidth}x$inputHeight."
        }
    }

    @Synchronized
    override fun infer(normalizedRgb: FloatArray): DetectorModelOutput {
        require(normalizedRgb.size == inputWidth * inputHeight * 3)
        val inputs = Array<Any>(inputTensors.size) { inputIndex ->
            val tensor = inputTensors[inputIndex]
            when (inputIndex) {
                imageInputIndex -> imageBuffer(normalizedRgb)
                else -> thresholdBuffer(tensor.name())
            }
        }

        val outputTensors = (0 until interpreter.outputTensorCount).map(
            interpreter::getOutputTensor,
        )
        require(outputTensors.all { it.dataType() == DataType.FLOAT32 }) {
            "Checkpoint 6 supports float32 detector outputs only."
        }
        val outputBuffers = outputTensors.map { tensor ->
            ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }
        val outputMap = mutableMapOf<Int, Any>()
        outputBuffers.forEachIndexed { index, buffer -> outputMap[index] = buffer }
        interpreter.runForMultipleInputsOutputs(inputs, outputMap)

        val values = outputBuffers.map { buffer ->
            buffer.rewind()
            FloatArray(buffer.capacity() / Float.SIZE_BYTES).also {
                buffer.asFloatBuffer().get(it)
            }
        }
        val confidenceIndex = outputTensors.indexOfFirst {
            it.name().contains("confidence", ignoreCase = true)
        }
        val coordinatesIndex = outputTensors.indexOfFirst {
            it.name().contains("coordinate", ignoreCase = true)
        }

        return if (confidenceIndex >= 0 && coordinatesIndex >= 0) {
            DetectorModelOutput.Split(
                confidence = values[confidenceIndex],
                confidenceShape = outputTensors[confidenceIndex].shape(),
                coordinates = values[coordinatesIndex],
                coordinatesShape = outputTensors[coordinatesIndex].shape(),
            )
        } else {
            val combinedIndex = outputTensors.indexOfFirst { it.shape().lastOrNull() == 6 }
            require(combinedIndex >= 0) {
                "Unknown LiteRT detector outputs: " +
                        outputTensors.joinToString { "${it.name()}=${it.shape().contentToString()}" }
            }
            DetectorModelOutput.EndToEnd(
                values = values[combinedIndex],
                shape = outputTensors[combinedIndex].shape(),
            )
        }
    }

    private fun imageBuffer(normalizedRgb: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(normalizedRgb.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        if (isNhwc) {
            floats.put(normalizedRgb)
        } else {
            repeat(3) { channel ->
                var pixelOffset = channel
                repeat(inputWidth * inputHeight) {
                    floats.put(normalizedRgb[pixelOffset])
                    pixelOffset += 3
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun thresholdBuffer(inputName: String): ByteBuffer {
        val value = when {
            inputName.contains("iou", ignoreCase = true) -> Detector.IOU_THRESHOLD
            inputName.contains("confidence", ignoreCase = true) ->
                Detector.CONFIDENCE_THRESHOLD
            else -> error("Unsupported LiteRT detector input: $inputName.")
        }
        return ByteBuffer.allocateDirect(Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putFloat(value)
            .apply { rewind() }
    }

    override fun close() = interpreter.close()
}
