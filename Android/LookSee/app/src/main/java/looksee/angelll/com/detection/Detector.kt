@file:Suppress("unused", "SpellCheckingInspection", "BlockingMethodInNonBlockingContext")

package looksee.angelll.com.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

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
    // THIS is where the "1" came from! It is fully dynamic based on AWS.
    val releaseIdentifier: String get() = "$clusterId|$modelVersion"
    val label: String get() = classIndex.toString()

    fun landmarkEntry(
        store: LandmarkManifestStore = LandmarkManifestStore.shared,
    ): LandmarkManifestEntry? = store.resolve(clusterId, modelVersion, classIndex)

    fun displayLabel(
        store: LandmarkManifestStore = LandmarkManifestStore.shared,
    ): String = displayLabelOverride ?: landmarkEntry(store)?.label ?: "Class $classIndex"
}

class DetectionTracker(
    private val maxCoastFrames: Int = Detector.MAX_COAST_FRAMES,
    private val coastConfidenceDecay: Float = Detector.COAST_CONFIDENCE_DECAY,
) {
    var lastDetection: Detection? = null
        private set
    private var framesSinceLastSeen = 0

    init {
        require(maxCoastFrames >= 0)
        require(coastConfidenceDecay in 0f..1f)
    }

    @Synchronized
    fun update(newDetection: Detection?, alpha: Float = Detector.TRACKING_SMOOTHING_ALPHA): Detection? {
        if (newDetection != null) {
            framesSinceLastSeen = 0
            val previous = lastDetection
            val smoothed = if (previous == null || !sameTrack(previous, newDetection)) {
                newDetection
            } else {
                newDetection.copy(
                    confidence = ema(previous.confidence, newDetection.confidence, alpha),
                    bbox = DetectionBox(
                        left = ema(previous.bbox.left, newDetection.bbox.left, alpha),
                        top = ema(previous.bbox.top, newDetection.bbox.top, alpha),
                        right = ema(previous.bbox.right, newDetection.bbox.right, alpha),
                        bottom = ema(previous.bbox.bottom, newDetection.bbox.bottom, alpha),
                    ),
                )
            }
            lastDetection = smoothed
            return smoothed
        }

        framesSinceLastSeen += 1
        val previous = lastDetection
        return if (previous != null && framesSinceLastSeen < maxCoastFrames) {
            previous.copy(confidence = previous.confidence * coastConfidenceDecay).also {
                lastDetection = it
            }
        } else {
            lastDetection = null
            null
        }
    }

    @Synchronized
    fun reset() {
        lastDetection = null
        framesSinceLastSeen = 0
    }

    private fun ema(previous: Float, current: Float, alpha: Float): Float = previous + alpha * (current - previous)
    private fun sameTrack(first: Detection, second: Detection): Boolean = first.releaseIdentifier == second.releaseIdentifier && first.classIndex == second.classIndex
}

data class DetectorFrame(val width: Int, val height: Int, val bitmap: Bitmap) {
    init { require(width > 0 && height > 0) }
}

internal data class LetterboxMetadata(val sourceWidth: Int, val sourceHeight: Int, val inputWidth: Int, val inputHeight: Int, val scale: Float, val padX: Float, val padY: Float)

internal data class PreparedDetectorFrame(val normalizedRgb: FloatArray, val letterbox: LetterboxMetadata)

sealed interface DetectorModelOutput {
    class EndToEnd(val values: FloatArray, val shape: IntArray) : DetectorModelOutput
    class Split(val confidence: FloatArray, val confidenceShape: IntArray, val coordinates: FloatArray, val coordinatesShape: IntArray) : DetectorModelOutput
    class RawYolo(val values: FloatArray, val shape: IntArray) : DetectorModelOutput
}

interface DetectorModel : Closeable {
    val inputWidth: Int
    val inputHeight: Int
    val inferredClassCount: Int? get() = null
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

private data class LoadedDetectorRelease(val release: ActiveModelRelease, val manifest: ClusterLandmarkManifest?, val model: DetectorModel)

class Detector internal constructor(
    activeReleases: StateFlow<ActiveModelRelease?>,
    private val manifestStore: LandmarkManifestStore = LandmarkManifestStore.shared,
    private val modelFactory: DetectorModelFactory = LiteRtDetectorModelFactory(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    observeActiveReleases: Boolean = true,
    private val allowSyntheticPreview: Boolean = false,
    context: Context? = null
) : AutoCloseable {
    constructor(modelSelector: ModelSelector, allowSyntheticPreview: Boolean = false, context: Context? = null) : this(
        activeReleases = modelSelector.activeRelease,
        allowSyntheticPreview = allowSyntheticPreview,
        context = context
    )

    private val detectorScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inferenceDispatcher = dispatcher
    private val inferenceMutex = Mutex()
    private val loadedRelease = AtomicReference<LoadedDetectorRelease?>(null)
    private val engineLock = Any()

    private val locationManager = context?.let { LocationManager(it.applicationContext) }

    private val trackers = mutableMapOf<String, DetectionTracker>()
    private val notificationCooldowns = mutableMapOf<String, Long>()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    private val _newlyDetectedLandmark = MutableStateFlow<Detection?>(null)
    val newlyDetectedLandmark: StateFlow<Detection?> = _newlyDetectedLandmark.asStateFlow()

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

    private val _loadState = MutableStateFlow<DetectorLoadState>(DetectorLoadState.WaitingForRelease)
    val loadState: StateFlow<DetectorLoadState> = _loadState.asStateFlow()

    private val _isSyntheticPreviewEnabled = MutableStateFlow(false)
    val isSyntheticPreviewEnabled: StateFlow<Boolean> = _isSyntheticPreviewEnabled.asStateFlow()

    @Volatile var dynamicSafeZone: DetectionBox? = null

    @Volatile var proximityThresholdMeters: Double = DEFAULT_PROXIMITY_THRESHOLD_METERS
        set(value) { require(value.isFinite() && value >= 0.0); field = value }

    @Volatile var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
        set(value) { require(value in 0f..1f); field = value }

    @Volatile var trackingThresholdMultiplier: Float = DEFAULT_TRACKING_THRESHOLD_MULTIPLIER
        set(value) { require(value in 0f..1f); field = value }

    @Volatile var trackingAlpha: Float = TRACKING_SMOOTHING_ALPHA
        set(value) { require(value in 0f..1f); field = value }

    @Volatile private var userLocation: DetectorLocation? = null

    init {
        Log.e("LOOKSEE_DEBUG", "🚀 Detector initialized. observeActiveReleases = $observeActiveReleases")
        locationManager?.start()

        detectorScope.launch {
            delay(4000L)
            if (userLocation == null) {
                userLocation = DetectorLocation(41.1809, -73.1568)
            }
        }

        detectorScope.launch {
            locationManager?.state?.collect { state ->
                if (state is LookSeeLocationState.Ready) {
                    userLocation = DetectorLocation(state.fix.latitude, state.fix.longitude)
                }
            }
        }

        if (observeActiveReleases) {
            detectorScope.launch {
                var observedReleaseIdentifier: String? = null
                activeReleases.collect { release ->
                    if (release == null) {
                        if (observedReleaseIdentifier != null) unloadModel()
                        observedReleaseIdentifier = null
                    } else if (release.releaseIdentifier != observedReleaseIdentifier) {
                        observedReleaseIdentifier = release.releaseIdentifier
                        activateRelease(release)
                    }
                }
            }
        }
    }

    fun setPaused(paused: Boolean) { _isPaused.value = paused }
    fun setHideBoundingBoxes(hidden: Boolean) { _hideBoundingBoxes.value = hidden }

    fun setSyntheticPreviewEnabled(enabled: Boolean) {
        val shouldEnable = allowSyntheticPreview && enabled
        if (_isSyntheticPreviewEnabled.value == shouldEnable) return
        _isSyntheticPreviewEnabled.value = shouldEnable
        resetEngine()
    }

    fun updateUserLocation(latitude: Double, longitude: Double, accuracyMeters: Double) {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0 || !accuracyMeters.isFinite() || accuracyMeters <= 0.0 || accuracyMeters > MAX_LOCATION_ACCURACY_METERS) return
        userLocation = DetectorLocation(latitude, longitude)
    }

    fun clearUserLocation() { userLocation = null }
    fun consumeNewlyDetectedLandmark() { _newlyDetectedLandmark.value = null }

    fun resetEngine() {
        synchronized(engineLock) {
            trackers.values.forEach(DetectionTracker::reset)
            trackers.clear()
        }
        _detections.value = emptyList()
        _currentLabel.value = null
        _newlyDetectedLandmark.value = null
    }

    suspend fun process(frame: DetectorFrame) {
        if (_isPaused.value || !inferenceMutex.tryLock()) return

        try {
            withContext(inferenceDispatcher) {
                _bufferSize.value = DetectionSize(frame.width, frame.height)

                val loaded = loadedRelease.get()
                if (loaded == null) {
                    return@withContext
                }

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

                _lastInferenceMs.value = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            }
        } catch (error: Exception) {
            // 🚀 FIXED: Ignore coroutine cancellations when CameraX drops a frame, so it stops spamming errors!
            if (error is kotlinx.coroutines.CancellationException) throw error

            val releaseId = loadedRelease.get()?.release?.releaseIdentifier ?: "none"
            Log.e("LOOKSEE_DEBUG", "❌ Detector inference failed for $releaseId: ${error.message}")
        } finally {
            inferenceMutex.unlock()
        }
    }

    internal suspend fun activateRelease(release: ActiveModelRelease) {
        Log.e("LOOKSEE_DEBUG", "⚙️ Attempting to activate release: ${release.releaseIdentifier}")
        _loadState.value = DetectorLoadState.Loading(release.releaseIdentifier)
        var candidateModel: DetectorModel? = null

        try {
            val newModel = modelFactory.load(release).also { candidateModel = it }
            val effectiveClassCount = release.classCount.takeIf { it > 0 } ?: newModel.inferredClassCount?.takeIf { it > 0 } ?: MAX_INFERRED_CLASS_COUNT
            val effectiveRelease = release.copy(classCount = effectiveClassCount)
            val manifest = release.manifestFile?.let { manifestFile ->
                val numericClusterId = release.clusterId.toIntOrNull() ?: error("Numeric clusterId required.")
                manifestStore.load(manifestFile).also { loadedManifest ->
                    require(loadedManifest.clusterId == numericClusterId)
                    require(loadedManifest.trainingRunId == release.modelVersion)
                    require(loadedManifest.classCount == effectiveClassCount)
                }
            }

            val newLoadedRelease = LoadedDetectorRelease(effectiveRelease, manifest, newModel)

            withContext(inferenceDispatcher) {
                inferenceMutex.lock()
                try {
                    val previous = loadedRelease.getAndSet(newLoadedRelease)
                    candidateModel = null
                    previous?.model?.close()

                    _classLabels.value = release.classLabels
                    resetEngine()
                } finally {
                    inferenceMutex.unlock()
                }
            }

            Log.e("LOOKSEE_DEBUG", "✅ Model successfully activated and handed to camera feed!")
            _loadState.value = DetectorLoadState.Ready(release.releaseIdentifier)
        } catch (error: Exception) {
            candidateModel?.close()
            Log.e("LOOKSEE_DEBUG", "❌ Failed to activate model: ${error.message}")
            _loadState.value = DetectorLoadState.Failed(release.releaseIdentifier, error.message ?: "Unknown error")
        }
    }

    private fun unloadModel() {
        Log.e("LOOKSEE_DEBUG", "🗑️ Unloading current model")
        loadedRelease.getAndSet(null)?.model?.close()
        _classLabels.value = emptyList()
        _loadState.value = DetectorLoadState.WaitingForRelease
        resetEngine()
    }

    private fun publishOutput(
        output: DetectorModelOutput,
        metadata: LetterboxMetadata,
        loaded: LoadedDetectorRelease,
        eventTimeMillis: Long,
    ) {
        val parsed = when (output) {
            is DetectorModelOutput.EndToEnd -> parseEndToEndDetections(output, metadata, loaded.release)
            is DetectorModelOutput.Split -> parseSplitDetections(output, metadata, loaded.release)
            is DetectorModelOutput.RawYolo -> parseRawYoloDetections(output, metadata, loaded.release)
        }
        val nearby = proximityFilter(parsed, loaded.manifest)
        val tracked = finalizeTracking(nearby)
        val strongest = tracked.maxByOrNull(Detection::confidence)

        _detections.value = if (_hideBoundingBoxes.value) emptyList() else tracked
        _currentLabel.value = strongest?.displayLabel(manifestStore)

        if (strongest != null) {
            val cooldownKey = strongest.displayLabel(manifestStore)
            val lastNotifiedAt = notificationCooldowns[cooldownKey] ?: Long.MIN_VALUE
            if (lastNotifiedAt == Long.MIN_VALUE || eventTimeMillis - lastNotifiedAt > NOTIFICATION_COOLDOWN_MILLIS) {
                notificationCooldowns[cooldownKey] = eventTimeMillis
                _newlyDetectedLandmark.value = strongest
            }
        }
    }

    private fun parseRawYoloDetections(
        output: DetectorModelOutput.RawYolo,
        metadata: LetterboxMetadata,
        release: ActiveModelRelease
    ): List<Detection> {
        val shape = output.shape
        if (shape.size != 3 || shape[0] != 1) return emptyList()

        val isTransposed = shape[1] == 8400
        val numBoxes = if (isTransposed) shape[1] else shape[2]
        val numChannels = if (isTransposed) shape[2] else shape[1]
        val numClasses = numChannels - 4

        if (numClasses <= 0) return emptyList()

        val rawDetections = mutableListOf<Detection>()
        val flatValues = output.values

        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var bestClass = -1

            for (c in 0 until numClasses) {
                val score = if (isTransposed) {
                    flatValues[i * numChannels + 4 + c]
                } else {
                    flatValues[(4 + c) * numBoxes + i]
                }

                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            val requiredScore = thresholdFor(release, bestClass)
            if (maxScore < requiredScore) continue

            var cx = if (isTransposed) flatValues[i * numChannels] else flatValues[i]
            var cy = if (isTransposed) flatValues[i * numChannels + 1] else flatValues[numBoxes + i]
            var w = if (isTransposed) flatValues[i * numChannels + 2] else flatValues[2 * numBoxes + i]
            var h = if (isTransposed) flatValues[i * numChannels + 3] else flatValues[3 * numBoxes + i]

            if (cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f) {
                cx *= metadata.inputWidth
                cy *= metadata.inputHeight
                w *= metadata.inputWidth
                h *= metadata.inputHeight
            }

            makeDetection(cx, cy, w, h, maxScore, bestClass, metadata, release)?.let {
                rawDetections.add(it)
            }
        }

        rawDetections.sortByDescending { it.confidence }
        val finalDetections = mutableListOf<Detection>()
        val active = BooleanArray(rawDetections.size) { true }

        for (i in rawDetections.indices) {
            if (!active[i]) continue
            val boxA = rawDetections[i]
            finalDetections.add(boxA)

            for (j in i + 1 until rawDetections.size) {
                if (!active[j]) continue
                val boxB = rawDetections[j]

                if (boxA.classIndex == boxB.classIndex && calculateIoU(boxA.bbox, boxB.bbox) > IOU_THRESHOLD) {
                    active[j] = false
                }
            }
        }

        return finalDetections
    }

    private fun calculateIoU(a: DetectionBox, b: DetectionBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        return interArea / (areaA + areaB - interArea)
    }

    private fun parseEndToEndDetections(output: DetectorModelOutput.EndToEnd, metadata: LetterboxMetadata, release: ActiveModelRelease): List<Detection> {
        val boxSize = output.shape.lastOrNull() ?: return emptyList()
        if (boxSize != END_TO_END_BOX_SIZE || output.values.size % boxSize != 0) return emptyList()

        val detections = mutableListOf<Detection>()
        val boxCount = output.values.size / boxSize
        repeat(boxCount) { boxIndex ->
            val offset = boxIndex * boxSize
            val score = output.values[offset + 4]
            val classIndex = output.values[offset + 5].toInt()
            val requiredScore = thresholdFor(release, classIndex)
            if (score < requiredScore || classIndex !in 0 until release.classCount) return@repeat

            var x1 = output.values[offset]
            var y1 = output.values[offset + 1]
            var x2 = output.values[offset + 2]
            var y2 = output.values[offset + 3]
            if (x1 <= 1f) x1 *= metadata.inputWidth
            if (x2 <= 1f) x2 *= metadata.inputWidth
            if (y1 <= 1f) y1 *= metadata.inputHeight
            if (y2 <= 1f) y2 *= metadata.inputHeight

            makeDetection((x1 + x2) / 2f, (y1 + y2) / 2f, x2 - x1, y2 - y1, score, classIndex, metadata, release)?.let(detections::add)
        }
        return detections
    }

    private fun parseSplitDetections(output: DetectorModelOutput.Split, metadata: LetterboxMetadata, release: ActiveModelRelease): List<Detection> {
        val numClasses = output.confidenceShape.lastOrNull() ?: return emptyList()
        val numDetections = output.coordinates.size / COORDINATE_VALUE_COUNT
        if (numClasses <= 0 || output.confidence.size < numDetections * numClasses) return emptyList()

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
            if (bestScore < thresholdFor(release, bestClass)) return@repeat

            val offset = detectionIndex * COORDINATE_VALUE_COUNT
            var centerX = output.coordinates[offset]
            var centerY = output.coordinates[offset + 1]
            var width = output.coordinates[offset + 2]
            var height = output.coordinates[offset + 3]
            if (centerX <= 1f) centerX *= metadata.inputWidth
            if (width <= 1f) width *= metadata.inputWidth
            if (centerY <= 1f) centerY *= metadata.inputHeight
            if (height <= 1f) height *= metadata.inputHeight

            makeDetection(centerX, centerY, width, height, bestScore, bestClass, metadata, release)?.let(detections::add)
        }
        return detections
    }

    private fun makeDetection(centerX: Float, centerY: Float, width: Float, height: Float, score: Float, classIndex: Int, metadata: LetterboxMetadata, release: ActiveModelRelease): Detection? {
        if (width <= 0f || height <= 0f || metadata.scale <= 0f) return null

        val sourceCenterX = (centerX - metadata.padX) / metadata.scale
        val sourceCenterY = (centerY - metadata.padY) / metadata.scale
        val sourceWidth = width / metadata.scale
        val sourceHeight = height / metadata.scale
        val box = DetectionBox(sourceCenterX - sourceWidth / 2f, sourceCenterY - sourceHeight / 2f, sourceCenterX + sourceWidth / 2f, sourceCenterY + sourceHeight / 2f)

        val safeZone = dynamicSafeZone ?: DetectionBox(0f, 0f, metadata.sourceWidth.toFloat(), metadata.sourceHeight.toFloat())
        if (!box.intersects(safeZone)) return null

        return Detection(release.clusterId, release.modelVersion, release.modelKey ?: "ota-model", classIndex, release.classCount, score, box, displayLabelOverride = release.classLabels.getOrNull(classIndex))
    }

    private fun thresholdFor(release: ActiveModelRelease, classIndex: Int): Float = synchronized(engineLock) {
        if (trackers[trackingKey(release, classIndex)]?.lastDetection != null) confidenceThreshold * trackingThresholdMultiplier else confidenceThreshold
    }

    private fun proximityFilter(detections: List<Detection>, manifest: ClusterLandmarkManifest?): List<Detection> {
        return detections
    }

    private fun finalizeTracking(detections: List<Detection>): List<Detection> = synchronized(engineLock) {
        val strongestByTrack = mutableMapOf<String, Detection>()
        detections.forEach { detection ->
            val key = trackingKey(detection)
            val current = strongestByTrack[key]
            if (current == null || detection.confidence > current.confidence) strongestByTrack[key] = detection
        }

        val activeKeys = strongestByTrack.keys
        val results = strongestByTrack.mapNotNullTo(mutableListOf()) { (key, detection) ->
            trackers.getOrPut(key) { DetectionTracker() }.update(detection, trackingAlpha)
        }

        val lostKeys = trackers.keys.filterNot(activeKeys::contains)
        lostKeys.forEach { key ->
            val coasted = trackers[key]?.update(null, trackingAlpha)
            if (coasted != null) results += coasted else trackers.remove(key)
        }
        results
    }

    private fun trackingKey(detection: Detection): String = "${detection.releaseIdentifier}|${detection.classIndex}"
    private fun trackingKey(release: ActiveModelRelease, classIndex: Int): String = "${release.releaseIdentifier}|$classIndex"

    override fun close() {
        detectorScope.cancel()
        locationManager?.close()
        loadedRelease.getAndSet(null)?.model?.close()
    }

    companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 640
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.65f
        const val DEFAULT_TRACKING_THRESHOLD_MULTIPLIER = 0.35f

        const val TRACKING_SMOOTHING_ALPHA = 0.65f
        const val MAX_COAST_FRAMES = 5
        const val COAST_CONFIDENCE_DECAY = 0.85f

        const val MODEL_OUTPUT_FLOOR = 0.05f
        const val IOU_THRESHOLD = 0.45f
        const val DEFAULT_PROXIMITY_THRESHOLD_METERS = 50.0
        const val MAX_LOCATION_ACCURACY_METERS = 100.0
        const val NOTIFICATION_COOLDOWN_MILLIS = 12_000L

        private const val END_TO_END_BOX_SIZE = 6
        private const val COORDINATE_VALUE_COUNT = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val MAX_INFERRED_CLASS_COUNT = 10_000

        @Volatile
        private var sharedInstance: Detector? = null

        fun shared(context: Context): Detector =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: Detector(
                    modelSelector = ModelSelector.shared(context.applicationContext),
                    allowSyntheticPreview = false,
                    context = context.applicationContext
                ).also { sharedInstance = it }
            }

        private var cachedFloatArray: FloatArray? = null
        private var cachedBitmap: Bitmap? = null
        private var cachedCanvas: Canvas? = null
        private var cachedIntArray: IntArray? = null

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

            val requiredSize = inputWidth * inputHeight

            if (cachedBitmap == null || cachedBitmap!!.width != inputWidth) {
                cachedBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                cachedCanvas = Canvas(cachedBitmap!!)
                cachedIntArray = IntArray(requiredSize)
                cachedFloatArray = FloatArray(requiredSize * 3)
            }

            val canvas = cachedCanvas!!
            val letterboxBitmap = cachedBitmap!!
            val intValues = cachedIntArray!!
            val normalizedRgb = cachedFloatArray!!

            canvas.drawColor(Color.BLACK)
            val matrix = Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(padX, padY)
            canvas.drawBitmap(frame.bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

            letterboxBitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            var floatIdx = 0
            for (pixel in intValues) {
                normalizedRgb[floatIdx++] = ((pixel shr 16) and 0xFF) / 255f
                normalizedRgb[floatIdx++] = ((pixel shr 8) and 0xFF) / 255f
                normalizedRgb[floatIdx++] = (pixel and 0xFF) / 255f
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
    }
}

private data class DetectorLocation(val latitude: Double, val longitude: Double)

class LiteRtDetectorModelFactory : DetectorModelFactory {
    override fun load(release: ActiveModelRelease): DetectorModel {
        var interpreter: Interpreter? = null

        // 1. Try Hardware GPU
        try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val gpuOptions = Interpreter.Options().addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
                interpreter = Interpreter(release.modelFile, gpuOptions)
                Log.e("LOOKSEE_DEBUG", "🚀 ✅ TFLite is running on HARDWARE GPU!")
            }
        } catch (e: Exception) {
            Log.e("LOOKSEE_DEBUG", "⚠️ GPU rejected YOLO11: ${e.message}")
        }

        // 2. Try Hardware NPU / Tensor Chip (NNAPI)
        if (interpreter == null) {
            try {
                val nnapiOptions = Interpreter.Options().apply { setUseNNAPI(true) }
                interpreter = Interpreter(release.modelFile, nnapiOptions)
                Log.e("LOOKSEE_DEBUG", "🧠 ✅ TFLite is running on NNAPI (Hardware NPU)!")
            } catch (e: Exception) {
                Log.e("LOOKSEE_DEBUG", "⚠️ NNAPI rejected YOLO11: ${e.message}")
            }
        }

        // 3. Max-Power CPU Fallback (All available cores)
        val finalInterpreter = interpreter ?: Interpreter(release.modelFile, Interpreter.Options().apply {
            val maxCores = Runtime.getRuntime().availableProcessors()
            setNumThreads(maxCores)
            setUseXNNPACK(true)
        }).also {
            Log.e("LOOKSEE_DEBUG", "🐢 TFLite running on CPU (XNNPACK) using ${Runtime.getRuntime().availableProcessors()} cores.")
        }

        return LiteRtDetectorModel(finalInterpreter)
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
    override val inferredClassCount: Int? by lazy {
        val outputShapes = (0 until interpreter.outputTensorCount)
            .map { interpreter.getOutputTensor(it).shape() }
        if (outputShapes.any { it.lastOrNull() == END_TO_END_OUTPUT_VALUES }) {
            return@lazy null
        }
        val confidenceShape = (0 until interpreter.outputTensorCount)
            .firstOrNull { index ->
                interpreter.getOutputTensor(index).name().contains("confidence", ignoreCase = true)
            }
            ?.let { outputShapes[it] }
        confidenceShape?.lastOrNull()?.takeIf { it > 0 }
            ?: outputShapes.firstNotNullOfOrNull { shape ->
                shape.drop(1)
                    .firstOrNull { it in MIN_CLASS_CHANNELS..MAX_CLASS_CHANNELS }
                    ?.takeUnless { it == END_TO_END_OUTPUT_VALUES }
                    ?.minus(COORDINATE_OUTPUT_VALUES)
            }
    }

    init {
        require((isNhwc && imageShape[3] == 3) || (!isNhwc && imageShape[1] == 3))
        require(inputWidth == Detector.INPUT_WIDTH && inputHeight == Detector.INPUT_HEIGHT)
    }

    private val imageByteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val preallocatedOutputBuffers = (0 until interpreter.outputTensorCount).map { i ->
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
    }
    private val preallocatedOutputMap = mutableMapOf<Int, Any>().apply {
        preallocatedOutputBuffers.forEachIndexed { index, buffer -> put(index, buffer) }
    }
    private val preallocatedFloatArrays = preallocatedOutputBuffers.map { buffer ->
        FloatArray(buffer.capacity() / Float.SIZE_BYTES)
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

        preallocatedOutputBuffers.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(inputs, preallocatedOutputMap)

        val values = preallocatedOutputBuffers.mapIndexed { index, buffer ->
            buffer.rewind()
            val array = preallocatedFloatArrays[index]
            buffer.asFloatBuffer().get(array)
            array
        }

        val rawIndex = outputTensors.indexOfFirst {
            it.shape().size == 3 && (it.shape()[1] == 8400 || it.shape()[2] == 8400)
        }

        if (rawIndex >= 0) {
            return DetectorModelOutput.RawYolo(
                values = values[rawIndex],
                shape = outputTensors[rawIndex].shape()
            )
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
            require(combinedIndex >= 0)
            DetectorModelOutput.EndToEnd(
                values = values[combinedIndex],
                shape = outputTensors[combinedIndex].shape(),
            )
        }
    }

    private fun imageBuffer(normalizedRgb: FloatArray): ByteBuffer {
        imageByteBuffer.rewind()
        val floats = imageByteBuffer.asFloatBuffer()
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
        imageByteBuffer.rewind()
        return imageByteBuffer
    }

    private fun thresholdBuffer(inputName: String): ByteBuffer {
        val value = when {
            inputName.contains("iou", ignoreCase = true) -> Detector.IOU_THRESHOLD
            inputName.contains("confidence", ignoreCase = true) -> Detector.MODEL_OUTPUT_FLOOR
            else -> error("Unsupported LiteRT detector input: $inputName.")
        }
        return ByteBuffer.allocateDirect(Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putFloat(value)
            .apply { rewind() }
    }

    override fun close() = interpreter.close()

    private companion object {
        const val END_TO_END_OUTPUT_VALUES = 6
        const val COORDINATE_OUTPUT_VALUES = 4
        const val MIN_CLASS_CHANNELS = 5
        const val MAX_CLASS_CHANNELS = 512
    }
}