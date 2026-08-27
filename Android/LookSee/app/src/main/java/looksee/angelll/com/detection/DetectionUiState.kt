package looksee.angelll.com.detection

/**
 * Stable UI projection of Detector's lower-level flows.
 *
 * Keeping this mapper free of Android and Compose types lets us verify every
 * model state with ordinary JVM unit tests, including the no-model path.
 */
data class DetectionHudState(
    val title: String,
    val detail: String,
    val detectionCount: Int,
    val inferenceMilliseconds: Double?,
    val isModelReady: Boolean,
    val isSyntheticPreview: Boolean,
)

fun detectorHudState(
    loadState: DetectorLoadState,
    detectionCount: Int,
    lastInferenceMilliseconds: Double,
    isPaused: Boolean,
    isSyntheticPreviewEnabled: Boolean,
): DetectionHudState {
    require(detectionCount >= 0) { "detectionCount cannot be negative." }

    if (isSyntheticPreviewEnabled) {
        return DetectionHudState(
            title = if (isPaused) "Overlay test paused" else "Overlay test active",
            detail = "Synthetic box only — no landmark model is running.",
            detectionCount = if (isPaused) 0 else detectionCount,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = true,
        )
    }

    val inferenceTime = lastInferenceMilliseconds
        .takeIf { it.isFinite() && it > 0.0 }

    return when (loadState) {
        DetectorLoadState.WaitingForRelease -> DetectionHudState(
            title = "Model unavailable",
            detail = "Camera preview is ready; waiting for a downloaded model.",
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Loading -> DetectionHudState(
            title = "Loading model",
            detail = "Preparing ${loadState.releaseIdentifier}.",
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Ready -> DetectionHudState(
            title = if (isPaused) "Detection paused" else "Model ready",
            detail = if (isPaused) {
                "Camera analysis is paused."
            } else {
                "Running ${loadState.releaseIdentifier}."
            },
            detectionCount = if (isPaused) 0 else detectionCount,
            inferenceMilliseconds = if (isPaused) null else inferenceTime,
            isModelReady = true,
            isSyntheticPreview = false,
        )

        is DetectorLoadState.Failed -> DetectionHudState(
            title = "Model could not load",
            detail = loadState.message,
            detectionCount = 0,
            inferenceMilliseconds = null,
            isModelReady = false,
            isSyntheticPreview = false,
        )
    }
}
