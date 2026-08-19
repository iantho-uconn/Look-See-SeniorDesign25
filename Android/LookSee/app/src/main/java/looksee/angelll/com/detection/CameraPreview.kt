package looksee.angelll.com.detection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Range
import android.util.Size as AndroidSize
import androidx.annotation.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * CameraX equivalent of the shared AVCaptureSession coordinator used by iOS.
 *
 * The coordinator owns the back-camera preview and one KEEP_ONLY_LATEST RGBA
 * analysis stream. The caller owns [Detector]; stopping the camera never closes
 * or resets the detector/model release.
 */
internal class CameraSessionCoordinator(
    context: Context,
    private val detector: Detector,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindingGeneration = AtomicInteger(0)
    private val frameInFlight = AtomicBoolean(false)
    private val analysisFrameRateGate = FrameRateGate(MAX_ANALYSIS_FPS)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile
    private var camera: Camera? = null

    @Volatile
    private var closed = false

    @Volatile
    private var requestedZoom = 1f

    /** Starts or rebinds preview and analysis after [previewView] has a viewport. */
    @OptIn(TransformExperimental::class)
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onPreviewTransform: (PreviewTransform?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed) return
        val generation = bindingGeneration.incrementAndGet()

        previewView.post {
            if (closed || generation != bindingGeneration.get()) return@post

            val viewPort = previewView.viewPort
            if (viewPort == null) {
                onError(IllegalStateException("Camera preview has no viewport yet."))
                return@post
            }

            val providerFuture = ProcessCameraProvider.getInstance(appContext)
            providerFuture.addListener(
                {
                    if (closed || generation != bindingGeneration.get()) return@addListener

                    try {
                        val provider = providerFuture.get()
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                            )
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    AndroidSize(TARGET_WIDTH, TARGET_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build()
                        val preview = Preview.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setTargetFrameRate(Range(MAX_ANALYSIS_FPS, MAX_ANALYSIS_FPS))
                            .build().also { useCase ->
                                useCase.surfaceProvider = previewView.surfaceProvider
                            }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setOutputImageRotationEnabled(true)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { image ->
                            if (!analysisFrameRateGate.shouldProcess(image.imageInfo.timestamp) ||
                                detector.isPaused.value ||
                                !frameInFlight.compareAndSet(false, true)
                            ) {
                                image.close()
                                return@setAnalyzer
                            }

                            try {
                                val sourceTransform = ImageProxyTransformFactory().apply {
                                    setUsingCropRect(true)
                                    setUsingRotationDegrees(true)
                                }.getOutputTransform(image)
                                val frame = image.toDetectorFrame()

                                mainExecutor.execute {
                                    if (generation != bindingGeneration.get()) {
                                        frameInFlight.set(false)
                                        return@execute
                                    }
                                    val targetTransform = previewView.outputTransform
                                    if (targetTransform == null) {
                                        frameInFlight.set(false)
                                        return@execute
                                    }
                                    val matrix = Matrix()
                                    CoordinateTransform(sourceTransform, targetTransform)
                                        .transform(matrix)
                                    onPreviewTransform(PreviewTransform.from(matrix))
                                    detectorScope.launch {
                                        try {
                                            detector.process(frame)
                                        } finally {
                                            frameInFlight.set(false)
                                        }
                                    }
                                }
                            } catch (error: Throwable) {
                                frameInFlight.set(false)
                                mainExecutor.execute { onError(error) }
                            } finally {
                                image.close()
                            }
                        }

                        val useCaseGroup = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .build()

                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            useCaseGroup,
                        )
                        cameraProvider = provider
                        imageAnalysis = analysis
                        setZoom(requestedZoom)
                    } catch (error: Throwable) {
                        onPreviewTransform(null)
                        onError(error)
                    }
                },
                mainExecutor,
            )
        }
    }

    /** Stops camera hardware while leaving this coordinator reusable. */
    fun stop(onPreviewTransform: (PreviewTransform?) -> Unit = {}) {
        bindingGeneration.incrementAndGet()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        camera = null
        frameInFlight.set(false)
        onPreviewTransform(null)
    }

    /** Applies the iOS-compatible 1x through 5x zoom range. */
    fun setZoom(factor: Float): Float {
        val activeCamera = camera
        if (activeCamera == null) {
            requestedZoom = factor.coerceIn(MIN_ZOOM, MAX_ZOOM)
            return requestedZoom
        }
        val deviceMaximum = activeCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: MAX_ZOOM
        val clamped = factor.coerceIn(MIN_ZOOM, min(deviceMaximum, MAX_ZOOM))
        requestedZoom = clamped
        activeCamera.cameraControl.setZoomRatio(clamped)
        return clamped
    }

    fun adjustZoom(scaleChange: Float): Float = setZoom(requestedZoom * scaleChange)

    override fun close() {
        if (closed) return
        stop()
        closed = true
        detectorScope.cancel()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080
        const val MAX_ANALYSIS_FPS = 30
    }
}

/** Monotonic frame gate used to cap detector work even if a camera emits above 30 fps. */
internal class FrameRateGate(maxFramesPerSecond: Int) {
    private val minimumIntervalNanos = 1_000_000_000L / maxFramesPerSecond.also {
        require(it > 0) { "maxFramesPerSecond must be positive." }
    }
    private val lastAcceptedTimestamp = AtomicLong(Long.MIN_VALUE)

    fun shouldProcess(timestampNanos: Long): Boolean {
        while (true) {
            val previous = lastAcceptedTimestamp.get()
            if (previous != Long.MIN_VALUE && timestampNanos > previous &&
                timestampNanos - previous < minimumIntervalNanos
            ) {
                return false
            }
            if (lastAcceptedTimestamp.compareAndSet(previous, timestampNanos)) return true
        }
    }
}

/**
 * Live LookSee camera preview, detector feed, safe-zone overlay, and gestures.
 *
 * [safeZoneRect] uses PreviewView pixel coordinates. Passing null preserves the
 * Swift `.zero` behavior and treats the whole preview as the safe zone.
 */
@Composable
fun CameraPreview(
    detector: Detector,
    zoomLevel: Float,
    onZoomLevelChange: (Float) -> Unit,
    showSafeZone: Boolean,
    safeZoneRect: DetectionBox?,
    onTap: () -> Unit,
    onPinch: () -> Unit,
    isAIPaused: Boolean,
    onBoxTap: (Detection) -> Unit,
    modifier: Modifier = Modifier,
    onCameraPermissionResult: (Boolean) -> Unit = {},
    onCameraError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
        ?: error("CameraPreview must be hosted under a LifecycleOwner.")
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewTransform by remember { mutableStateOf<PreviewTransform?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var cameraError by remember { mutableStateOf<Throwable?>(null) }

    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnZoomLevelChange by rememberUpdatedState(onZoomLevelChange)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnPinch by rememberUpdatedState(onPinch)
    val currentOnBoxTap by rememberUpdatedState(onBoxTap)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        onCameraPermissionResult(granted)
    }
    val coordinator = remember(context, detector) {
        CameraSessionCoordinator(context, detector)
    }

    val detections by detector.detections.collectAsComposeState()
    val visibleDetections = if (isAIPaused) emptyList() else detections
    val horizontalMargin = with(density) { 16.dp.toPx() }
    val verticalMargin = with(density) { 80.dp.toPx() }
    val minimumBoxSize = with(density) { 10.dp.toPx() }
    val hitExpansion = with(density) { 40.dp.toPx() }

    val displayDetections = remember(
        visibleDetections,
        previewTransform,
        overlaySize,
        showSafeZone,
        safeZoneRect,
        horizontalMargin,
        verticalMargin,
        minimumBoxSize,
    ) {
        mapDisplayDetections(
            detections = visibleDetections,
            transform = previewTransform,
            overlayWidth = overlaySize.width.toFloat(),
            overlayHeight = overlaySize.height.toFloat(),
            showSafeZone = showSafeZone,
            safeZoneRect = safeZoneRect,
            horizontalMargin = horizontalMargin,
            verticalMargin = verticalMargin,
            minimumBoxSize = minimumBoxSize,
        )
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(isAIPaused) {
        detector.setPaused(isAIPaused)
    }

    LaunchedEffect(zoomLevel, cameraPermissionGranted) {
        if (cameraPermissionGranted) coordinator.setZoom(zoomLevel)
    }

    LaunchedEffect(cameraPermissionGranted, isAIPaused, previewView) {
        val view = previewView
        if (cameraPermissionGranted && !isAIPaused && view != null) {
            cameraError = null
            coordinator.start(
                lifecycleOwner = lifecycleOwner,
                previewView = view,
                onPreviewTransform = { previewTransform = it },
                onError = { error ->
                    cameraError = error
                    currentOnCameraError(error)
                },
            )
        } else {
            coordinator.stop { previewTransform = it }
        }
    }

    DisposableEffect(coordinator) {
        onDispose {
            detector.setPaused(true)
            coordinator.close()
        }
    }

    if (!cameraPermissionGranted) {
        CameraPermissionMessage(
            modifier = modifier,
            requestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
        )
        return
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { overlaySize = it }
            .pointerInput(displayDetections, hitExpansion) {
                detectTapGestures { location ->
                    currentOnTap()
                    displayDetections.firstOrNull { target ->
                        target.box.expandedBy(hitExpansion)
                            .contains(location.x, location.y)
                    }?.let { target ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnBoxTap(target.detection)
                    }
                }
            }
            .pointerInput(coordinator) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        val adjustedZoom = coordinator.adjustZoom(zoomChange)
                        currentOnZoomLevelChange(adjustedZoom)
                        currentOnPinch()
                    }
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView mode guarantees the Compose overlay stays above preview.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        DetectionOverlay(
            detections = displayDetections,
            showSafeZone = showSafeZone,
            safeZoneRect = safeZoneRect,
            modifier = Modifier.fillMaxSize(),
        )

        cameraError?.let { error ->
            Text(
                text = error.message ?: "Unable to start camera.",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun CameraPermissionMessage(
    modifier: Modifier,
    requestPermission: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = requestPermission) {
            Text("Allow camera access")
        }
    }
}

@Composable
private fun DetectionOverlay(
    detections: List<DisplayDetection>,
    showSafeZone: Boolean,
    safeZoneRect: DetectionBox?,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val green = Color(0xFF34C759)
    val cyan = Color(0xCC00CCFF)
    val boxStroke = with(density) { 4.dp.toPx() }
    val safeZoneStroke = with(density) { 2.dp.toPx() }
    val cornerRadius = with(density) { 8.dp.toPx() }
    val labelCornerRadius = with(density) { 6.dp.toPx() }
    val labelTextSize = with(density) { 16.dp.toPx() }
    val labelHorizontalPadding = with(density) { 6.dp.toPx() }
    val labelVerticalPadding = with(density) { 3.dp.toPx() }
    val labelGap = with(density) { 8.dp.toPx() }
    val labelMinimumTop = with(density) { 44.dp.toPx() }
    val labelMinimumLeft = with(density) { 16.dp.toPx() }
    val dashEffect = PathEffect.dashPathEffect(
        floatArrayOf(with(density) { 8.dp.toPx() }, with(density) { 6.dp.toPx() }),
    )
    val labelPaint = remember(labelTextSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = labelTextSize
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = modifier) {
        val bounds = DetectionBox(0f, 0f, size.width, size.height)
        val activeSafeZone = safeZoneRect
            ?.intersectionOrNull(bounds)
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?: bounds

        if (showSafeZone) {
            val shade = Color.Black.copy(alpha = 0.40f)
            drawRect(shade, size = Size(size.width, activeSafeZone.top))
            drawRect(
                shade,
                topLeft = Offset(0f, activeSafeZone.bottom),
                size = Size(size.width, size.height - activeSafeZone.bottom),
            )
            drawRect(
                shade,
                topLeft = Offset(0f, activeSafeZone.top),
                size = Size(activeSafeZone.left, activeSafeZone.height),
            )
            drawRect(
                shade,
                topLeft = Offset(activeSafeZone.right, activeSafeZone.top),
                size = Size(size.width - activeSafeZone.right, activeSafeZone.height),
            )
        }

        detections.forEach { target ->
            val box = target.box
            drawRoundRect(
                color = green,
                topLeft = Offset(box.left, box.top),
                size = Size(box.width, box.height),
                cornerRadius = CornerRadius(cornerRadius),
                style = Stroke(width = boxStroke),
            )

            val metrics = labelPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val textWidth = labelPaint.measureText(target.label)
            val badgeLeft = max(box.left, labelMinimumLeft)
            val badgeTop = max(
                box.top - textHeight - labelVerticalPadding * 2f - labelGap,
                labelMinimumTop,
            )
            val badgeWidth = textWidth + labelHorizontalPadding * 2f
            val badgeHeight = textHeight + labelVerticalPadding * 2f

            drawRoundRect(
                color = green,
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(labelCornerRadius),
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    target.label,
                    badgeLeft + labelHorizontalPadding,
                    badgeTop + labelVerticalPadding - metrics.ascent,
                    labelPaint,
                )
            }
        }

        if (showSafeZone && detections.isEmpty()) {
            drawRect(
                color = cyan,
                topLeft = Offset(activeSafeZone.left, activeSafeZone.top),
                size = Size(activeSafeZone.width, activeSafeZone.height),
                style = Stroke(width = safeZoneStroke, pathEffect = dashEffect),
            )
        }
    }
}

private data class DisplayDetection(
    val detection: Detection,
    val box: DetectionBox,
    val label: String,
)

/** Immutable CameraX transform snapshot that is safe to retain after ImageProxy.close(). */
internal class PreviewTransform internal constructor(
    internal val values: FloatArray,
) {
    init {
        require(values.size == MATRIX_VALUE_COUNT) { "A 3x3 matrix needs nine values." }
    }

    fun map(box: DetectionBox): DetectionBox {
        val corners = arrayOf(
            mapPoint(box.left, box.top),
            mapPoint(box.right, box.top),
            mapPoint(box.right, box.bottom),
            mapPoint(box.left, box.bottom),
        )
        return DetectionBox(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second },
        )
    }

    private fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        val denominator = values[6] * x + values[7] * y + values[8]
        val safeDenominator = if (denominator == 0f) 1f else denominator
        return Pair(
            (values[0] * x + values[1] * y + values[2]) / safeDenominator,
            (values[3] * x + values[4] * y + values[5]) / safeDenominator,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is PreviewTransform && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        private const val MATRIX_VALUE_COUNT = 9

        fun from(matrix: Matrix): PreviewTransform {
            val values = FloatArray(MATRIX_VALUE_COUNT)
            matrix.getValues(values)
            return PreviewTransform(values)
        }
    }
}

private fun mapDisplayDetections(
    detections: List<Detection>,
    transform: PreviewTransform?,
    overlayWidth: Float,
    overlayHeight: Float,
    showSafeZone: Boolean,
    safeZoneRect: DetectionBox?,
    horizontalMargin: Float,
    verticalMargin: Float,
    minimumBoxSize: Float,
): List<DisplayDetection> {
    if (transform == null || overlayWidth <= 0f || overlayHeight <= 0f) return emptyList()

    val viewport = DetectionBox(0f, 0f, overlayWidth, overlayHeight)
    val activeSafeZone = safeZoneRect
        ?.intersectionOrNull(viewport)
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?: viewport
    val insetBounds = DetectionBox(
        left = horizontalMargin,
        top = verticalMargin,
        right = overlayWidth - horizontalMargin,
        bottom = overlayHeight - verticalMargin,
    ).takeIf { it.width > 0f && it.height > 0f } ?: viewport

    return detections.mapNotNull { detection ->
        val mapped = transform.map(detection.bbox)
        val safeClipped = if (showSafeZone) {
            mapped.intersectionOrNull(activeSafeZone)
        } else {
            mapped
        }
        val clamped = safeClipped?.intersectionOrNull(insetBounds)
            ?.takeIf { it.width > minimumBoxSize && it.height > minimumBoxSize }
            ?: return@mapNotNull null

        DisplayDetection(
            detection = detection,
            box = clamped,
            label = detectionOverlayLabel(detection),
        )
    }
}

internal fun detectionOverlayLabel(detection: Detection): String =
    "${detection.displayLabel()} ${(detection.confidence * 100).toInt()}%"

internal fun DetectionBox.intersectionOrNull(other: DetectionBox): DetectionBox? {
    val result = DetectionBox(
        left = max(left, other.left),
        top = max(top, other.top),
        right = min(right, other.right),
        bottom = min(bottom, other.bottom),
    )
    return result.takeIf { it.width > 0f && it.height > 0f }
}

internal fun DetectionBox.expandedBy(padding: Float): DetectionBox = DetectionBox(
    left = left - padding,
    top = top - padding,
    right = right + padding,
    bottom = bottom + padding,
)

internal fun DetectionBox.contains(x: Float, y: Float): Boolean =
    x in left..right && y in top..bottom

private fun ImageProxy.toDetectorFrame(): DetectorFrame {
    val crop = cropRect
    val plane = planes.firstOrNull()
        ?: error("CameraX RGBA frame did not contain a pixel plane.")
    val pixels = rgbaPlaneToArgb(
        buffer = plane.buffer,
        bufferWidth = width,
        bufferHeight = height,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropWidth = crop.width(),
        cropHeight = crop.height(),
    )
    return DetectorFrame(
        width = crop.width(),
        height = crop.height(),
        argbPixels = pixels,
    )
}

/**
 * Converts CameraX's documented A,R,G,B byte order into Android ARGB ints.
 * Row padding, pixel stride, and the ImageProxy crop rectangle are respected.
 */
internal fun rgbaPlaneToArgb(
    buffer: ByteBuffer,
    bufferWidth: Int,
    bufferHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = bufferWidth,
    cropHeight: Int = bufferHeight,
): IntArray {
    require(bufferWidth > 0 && bufferHeight > 0)
    require(pixelStride >= 4) { "RGBA pixel stride must be at least four bytes." }
    require(rowStride >= bufferWidth * pixelStride)
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    require(cropLeft + cropWidth <= bufferWidth && cropTop + cropHeight <= bufferHeight)

    val lastByteOffset =
        (cropTop + cropHeight - 1) * rowStride +
                (cropLeft + cropWidth - 1) * pixelStride + 3
    require(lastByteOffset < buffer.capacity()) {
        "RGBA plane is smaller than its declared dimensions and strides."
    }

    val source = buffer.duplicate()
    val output = IntArray(cropWidth * cropHeight)
    var outputIndex = 0
    repeat(cropHeight) { row ->
        val rowOffset = (cropTop + row) * rowStride + cropLeft * pixelStride
        repeat(cropWidth) { column ->
            val offset = rowOffset + column * pixelStride
            val alpha = source.get(offset).toInt() and 0xFF
            val red = source.get(offset + 1).toInt() and 0xFF
            val green = source.get(offset + 2).toInt() and 0xFF
            val blue = source.get(offset + 3).toInt() and 0xFF
            output[outputIndex++] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return output
}

/** Small StateFlow adapter that avoids adding lifecycle-runtime-compose. */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsComposeState():
        androidx.compose.runtime.State<T> {
    val state = remember(this) { mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}
