package looksee.angelll.com.detection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Range
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.gestures.calculateZoom
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

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

    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var imageAnalysis: ImageAnalysis? = null
    @Volatile private var camera: Camera? = null
    @Volatile private var closed = false
    @Volatile private var requestedZoom = 1f

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onImageDimensions: (IntSize?) -> Unit,
        onError: (Throwable) -> Unit,
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
                        val displayRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

                        // 🚀 FIXED: Allow the Preview to use the full screen 16:9/4:3 resolution so it looks crystal clear.
                        val previewResolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()

                        val preview = Preview.Builder()
                            .setResolutionSelector(previewResolutionSelector)
                            .setTargetRotation(displayRotation)
                            .build().also { useCase ->
                                useCase.surfaceProvider = previewView.surfaceProvider
                            }

                        // 🚀 FIXED: Restrict ONLY the ImageAnalyzer to 640x480 for the TFLite Model.
                        val analysisResolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    AndroidSize(TARGET_WIDTH, TARGET_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build()

                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(analysisResolutionSelector)
                            .setTargetRotation(displayRotation)
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
                                val bitmap = image.toBitmap()
                                val cropWidth = bitmap.width
                                val cropHeight = bitmap.height

                                val frame = DetectorFrame(
                                    width = cropWidth,
                                    height = cropHeight,
                                    bitmap = bitmap
                                )

                                mainExecutor.execute {
                                    if (generation != bindingGeneration.get()) {
                                        frameInFlight.set(false)
                                        bitmap.recycle()
                                        return@execute
                                    }

                                    onImageDimensions(IntSize(cropWidth, cropHeight))

                                    detectorScope.launch {
                                        try {
                                            detector.process(frame)
                                        } finally {
                                            frameInFlight.set(false)
                                            bitmap.recycle()
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

                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                        cameraProvider = provider
                        imageAnalysis = analysis
                        setZoom(requestedZoom)
                    } catch (error: Throwable) {
                        onImageDimensions(null)
                        onError(error)
                    }
                },
                mainExecutor,
            )
        }
    }

    fun stop(onImageDimensions: (IntSize?) -> Unit = {}) {
        bindingGeneration.incrementAndGet()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        camera = null
        frameInFlight.set(false)
        onImageDimensions(null)
    }

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
        const val TARGET_WIDTH = 640
        const val TARGET_HEIGHT = 480
        const val MAX_ANALYSIS_FPS = 30
    }
}

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
    hideBoundingBoxes: Boolean = false,
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
    var imageDimensions by remember { mutableStateOf<IntSize?>(null) }
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

    val hitExpansion = with(density) { 40.dp.toPx() }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(cameraPermissionGranted, previewView) {
        val view = previewView
        if (cameraPermissionGranted && view != null) {
            cameraError = null
            coordinator.start(
                lifecycleOwner = lifecycleOwner,
                previewView = view,
                onImageDimensions = { imageDimensions = it },
                onError = { error ->
                    cameraError = error
                    currentOnCameraError(error)
                },
            )
        } else {
            coordinator.stop { imageDimensions = it }
        }
    }

    LaunchedEffect(isAIPaused) {
        detector.setPaused(isAIPaused)
    }

    LaunchedEffect(zoomLevel, cameraPermissionGranted) {
        if (cameraPermissionGranted) coordinator.setZoom(zoomLevel)
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
            .pointerInput(imageDimensions, overlaySize, showSafeZone, safeZoneRect, hideBoundingBoxes) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val changes = event.changes
                        if (changes.size == 1 && changes.first().changedToUp()) {
                            val location = changes.first().position
                            currentOnTap()

                            if (!hideBoundingBoxes) {
                                val mapped = mapDisplayDetections(
                                    detections = detector.detections.value,
                                    imageDimensions = imageDimensions,
                                    overlayWidth = overlaySize.width.toFloat(),
                                    overlayHeight = overlaySize.height.toFloat(),
                                    showSafeZone = showSafeZone,
                                    safeZoneRect = safeZoneRect,
                                    horizontalMargin = with(density) { 16.dp.toPx() },
                                    verticalMargin = with(density) { 80.dp.toPx() },
                                    minimumBoxSize = with(density) { 10.dp.toPx() },
                                )

                                mapped.firstOrNull { target ->
                                    target.box.expandedBy(hitExpansion)
                                        .contains(location.x, location.y)
                                }?.let { target ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnBoxTap(target.detection)
                                    changes.first().consume()
                                }
                            }
                        } else if (changes.size >= 2) {
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) {
                                val adjustedZoom = coordinator.adjustZoom(zoomChange)
                                currentOnZoomLevelChange(adjustedZoom)
                                currentOnPinch()
                            }
                            changes.forEach { it.consume() } // 🚀 FIXED: Consume touch events so HorizontalPager doesn't slide
                        }
                    }
                }
            },
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

        DetectionOverlay(
            detector = detector,
            imageDimensions = imageDimensions,
            overlaySize = overlaySize,
            showSafeZone = showSafeZone,
            safeZoneRect = safeZoneRect,
            hideBoundingBoxes = hideBoundingBoxes,
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
    detector: Detector,
    imageDimensions: IntSize?,
    overlaySize: IntSize,
    showSafeZone: Boolean,
    safeZoneRect: DetectionBox?,
    hideBoundingBoxes: Boolean,
    modifier: Modifier,
) {
    if (hideBoundingBoxes || imageDimensions == null || overlaySize == IntSize.Zero) return

    val visibleDetections by detector.detections.collectAsState()

    val density = LocalDensity.current
    val horizontalMargin = remember { with(density) { 16.dp.toPx() } }
    val verticalMargin = remember { with(density) { 80.dp.toPx() } }
    val minimumBoxSize = remember { with(density) { 10.dp.toPx() } }

    val displayDetections = remember(
        visibleDetections,
        imageDimensions,
        overlaySize,
        showSafeZone,
        safeZoneRect,
        horizontalMargin,
        verticalMargin,
        minimumBoxSize,
    ) {
        mapDisplayDetections(
            detections = visibleDetections,
            imageDimensions = imageDimensions,
            overlayWidth = overlaySize.width.toFloat(),
            overlayHeight = overlaySize.height.toFloat(),
            showSafeZone = showSafeZone,
            safeZoneRect = safeZoneRect,
            horizontalMargin = horizontalMargin,
            verticalMargin = verticalMargin,
            minimumBoxSize = minimumBoxSize,
        )
    }

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

        displayDetections.forEach { target ->
            val box = target.box
            drawRoundRect(
                color = green,
                topLeft = Offset(box.left, box.top),
                size = Size(box.width, box.height),
                cornerRadius = CornerRadius(cornerRadius),
                style = Stroke(width = boxStroke),
            )

            val labelText = target.label

            val metrics = labelPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val textWidth = labelPaint.measureText(labelText)
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
                    labelText,
                    badgeLeft + labelHorizontalPadding,
                    badgeTop + labelVerticalPadding - metrics.ascent,
                    labelPaint,
                )
            }
        }

        if (showSafeZone && displayDetections.isEmpty()) {
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

private fun mapDisplayDetections(
    detections: List<Detection>,
    imageDimensions: IntSize?,
    overlayWidth: Float,
    overlayHeight: Float,
    showSafeZone: Boolean,
    safeZoneRect: DetectionBox?,
    horizontalMargin: Float,
    verticalMargin: Float,
    minimumBoxSize: Float,
): List<DisplayDetection> {
    if (imageDimensions == null || overlayWidth <= 0f || overlayHeight <= 0f) return emptyList()

    val imageWidth = imageDimensions.width.toFloat()
    val imageHeight = imageDimensions.height.toFloat()

    if (imageWidth <= 0f || imageHeight <= 0f) return emptyList()

    val scaleX = overlayWidth / imageWidth
    val scaleY = overlayHeight / imageHeight

    if (scaleX.isNaN() || scaleY.isNaN() || scaleX.isInfinite() || scaleY.isInfinite()) return emptyList()

    val scale = max(scaleX, scaleY)

    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale

    val offsetX = (scaledWidth - overlayWidth) / 2f
    val offsetY = (scaledHeight - overlayHeight) / 2f

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
        val mappedBox = DetectionBox(
            left = (detection.bbox.left * scale) - offsetX,
            top = (detection.bbox.top * scale) - offsetY,
            right = (detection.bbox.right * scale) - offsetX,
            bottom = (detection.bbox.bottom * scale) - offsetY
        )

        val safeClipped = if (showSafeZone) {
            mappedBox.intersectionOrNull(activeSafeZone)
        } else {
            mappedBox
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

internal fun detectionOverlayLabel(detection: Detection): String {
    val cleanLabel = detection.displayLabel().substringBefore("%").trim()
    return "$cleanLabel ${(detection.confidence * 100).toInt()}%"
}

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