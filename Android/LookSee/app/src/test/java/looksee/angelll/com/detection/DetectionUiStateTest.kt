package looksee.angelll.com.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionUiStateTest {
    @Test
    fun waitingForReleaseIsSafeAndExplicit() {
        val state = detectorHudState(
            loadState = DetectorLoadState.WaitingForRelease,
            detectionCount = 3,
            lastInferenceMilliseconds = 12.5,
            isPaused = false,
            isSyntheticPreviewEnabled = false,
        )

        assertEquals("Model unavailable", state.title)
        assertEquals(0, state.detectionCount)
        assertNull(state.inferenceMilliseconds)
        assertFalse(state.isModelReady)
    }

    @Test
    fun loadingStateNamesThePendingRelease() {
        val state = detectorHudState(
            loadState = DetectorLoadState.Loading("12|run-7"),
            detectionCount = 0,
            lastInferenceMilliseconds = 0.0,
            isPaused = false,
            isSyntheticPreviewEnabled = false,
        )

        assertEquals("Loading model", state.title)
        assertTrue(state.detail.contains("12|run-7"))
    }

    @Test
    fun readyStatePublishesCountAndTiming() {
        val state = detectorHudState(
            loadState = DetectorLoadState.Ready("12|run-7"),
            detectionCount = 2,
            lastInferenceMilliseconds = 18.25,
            isPaused = false,
            isSyntheticPreviewEnabled = false,
        )

        assertEquals("Model ready", state.title)
        assertEquals(2, state.detectionCount)
        assertEquals(18.25, state.inferenceMilliseconds!!, 0.001)
        assertTrue(state.isModelReady)
    }

    @Test
    fun pausedReadyStateHidesStaleResults() {
        val state = detectorHudState(
            loadState = DetectorLoadState.Ready("12|run-7"),
            detectionCount = 2,
            lastInferenceMilliseconds = 18.25,
            isPaused = true,
            isSyntheticPreviewEnabled = false,
        )

        assertEquals("Detection paused", state.title)
        assertEquals(0, state.detectionCount)
        assertNull(state.inferenceMilliseconds)
    }

    @Test
    fun failedStateShowsModelErrorWithoutResults() {
        val state = detectorHudState(
            loadState = DetectorLoadState.Failed("12|run-7", "Bad model file"),
            detectionCount = 4,
            lastInferenceMilliseconds = 20.0,
            isPaused = false,
            isSyntheticPreviewEnabled = false,
        )

        assertEquals("Model could not load", state.title)
        assertEquals("Bad model file", state.detail)
        assertEquals(0, state.detectionCount)
    }

    @Test
    fun syntheticPreviewIsClearlySeparatedFromRealDetection() {
        val state = detectorHudState(
            loadState = DetectorLoadState.WaitingForRelease,
            detectionCount = 1,
            lastInferenceMilliseconds = 0.0,
            isPaused = false,
            isSyntheticPreviewEnabled = true,
        )

        assertEquals("Overlay test active", state.title)
        assertEquals(1, state.detectionCount)
        assertNull(state.inferenceMilliseconds)
        assertFalse(state.isModelReady)
        assertTrue(state.isSyntheticPreview)
    }
}
