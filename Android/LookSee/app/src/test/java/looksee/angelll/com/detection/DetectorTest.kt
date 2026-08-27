package looksee.angelll.com.detection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import looksee.angelll.com.models.ActiveModelRelease
import looksee.angelll.com.models.ClusterLandmarkManifest
import looksee.angelll.com.models.LandmarkManifestEntry
import looksee.angelll.com.models.LandmarkManifestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DetectorTest {
    @Test
    fun syntheticPreviewProducesAProportionalBoxWithoutARelease(): Unit = runBlocking {
        val detector = Detector(
            activeReleases = MutableStateFlow(null),
            dispatcher = Dispatchers.Unconfined,
            observeActiveReleases = false,
            allowSyntheticPreview = true,
        )

        try {
            detector.setSyntheticPreviewEnabled(true)
            detector.process(DetectorFrame(100, 200, IntArray(20_000)))

            val detection = detector.detections.value.single()
            assertEquals(DetectionBox(22f, 48f, 78f, 144f), detection.bbox)
            assertEquals("Overlay test", detection.displayLabel())
            assertTrue(detector.isSyntheticPreviewEnabled.value)
            assertEquals(DetectionSize(100, 200), detector.bufferSize.value)
        } finally {
            detector.close()
        }
    }

    @Test
    fun productionDetectorCannotEnableSyntheticPreview() {
        val detector = Detector(
            activeReleases = MutableStateFlow(null),
            dispatcher = Dispatchers.Unconfined,
            observeActiveReleases = false,
        )

        try {
            detector.setSyntheticPreviewEnabled(true)
            assertFalse(detector.isSyntheticPreviewEnabled.value)
        } finally {
            detector.close()
        }
    }

    @Test
    fun manifestlessBundledReleaseUsesModelInferredClassCount(): Unit = runBlocking {
        val release = ActiveModelRelease(
            clusterId = "bundled-test",
            modelVersion = "bundled-new-model",
            modelFile = File("unused.tflite"),
            manifestFile = null,
            classCount = 0,
            modelKey = "new-model.tflite",
            manifestKey = null,
            displayName = "New model",
            classLabels = listOf("Clock", "Library", "Museum"),
        )
        val model = object : DetectorModel {
            override val inputWidth = 2
            override val inputHeight = 2
            override val inferredClassCount = 3
            override fun infer(normalizedRgb: FloatArray): DetectorModelOutput =
                endToEnd(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 2f)
            override fun close() = Unit
        }
        val detector = Detector(
            activeReleases = MutableStateFlow(null),
            modelFactory = DetectorModelFactory { model },
            dispatcher = Dispatchers.Unconfined,
            observeActiveReleases = false,
        )

        try {
            detector.activateRelease(release)
            detector.process(DetectorFrame(2, 2, IntArray(4)))

            val detection = detector.detections.value.single()
            assertEquals(3, detection.classCount)
            assertEquals("Museum", detection.displayLabel())
            assertEquals(listOf("Clock", "Library", "Museum"), detector.classLabels.value)
            assertTrue(detector.loadState.value is DetectorLoadState.Ready)
        } finally {
            detector.close()
        }
    }

    @Test
    fun endToEndParserMapsNormalizedCoordinatesAndRejectsInvalidRows() {
        detectorFixture(classCount = 1).use { fixture ->
            val output = endToEnd(
                0.25f, 0.25f, 0.75f, 0.75f, 0.90f, 0f,
                0.10f, 0.10f, 0.20f, 0.20f, 0.79f, 0f,
                0.10f, 0.10f, 0.20f, 0.20f, 0.99f, 4f,
            )

            fixture.processThree(output)

            val detection = fixture.detector.detections.value.single()
            assertEquals(0, detection.classIndex)
            assertEquals(160f, detection.bbox.left, 0.001f)
            assertEquals(160f, detection.bbox.top, 0.001f)
            assertEquals(480f, detection.bbox.right, 0.001f)
            assertEquals(480f, detection.bbox.bottom, 0.001f)
        }
    }

    @Test
    fun splitParserChoosesStrongestClass() {
        detectorFixture(classCount = 2).use { fixture ->
            val output = DetectorModelOutput.Split(
                confidence = floatArrayOf(0.81f, 0.95f),
                confidenceShape = intArrayOf(1, 2),
                coordinates = floatArrayOf(0.5f, 0.5f, 0.2f, 0.4f),
                coordinatesShape = intArrayOf(1, 4),
            )

            fixture.processThree(output)

            val detection = fixture.detector.detections.value.single()
            assertEquals(1, detection.classIndex)
            assertEquals(0.95f, detection.confidence, 0.0001f)
            assertEquals("Landmark 1", fixture.detector.currentLabel.value)
        }
    }

    @Test
    fun firstStrongDetectionIsPublishedImmediately() {
        detectorFixture(classCount = 1).use { fixture ->
            val detection = endToEnd(10f, 10f, 30f, 30f, 0.95f, 0f)
            fixture.process(detection, 1_000L)
            assertEquals(1, fixture.detector.detections.value.size)
        }
    }

    @Test
    fun boundingBoxesUseExponentialMovingAverage() {
        detectorFixture(classCount = 1).use { fixture ->
            fixture.process(endToEnd(10f, 10f, 20f, 20f, 0.95f, 0f), 1_000L)
            fixture.process(endToEnd(20f, 10f, 30f, 20f, 0.95f, 0f), 1_100L)
            fixture.process(endToEnd(30f, 10f, 40f, 20f, 0.95f, 0f), 1_200L)
            fixture.process(endToEnd(40f, 10f, 50f, 20f, 0.95f, 0f), 1_300L)

            val box = fixture.detector.detections.value.single().bbox
            assertEquals(34.84625f, box.left, 0.001f)
            assertEquals(44.84625f, box.right, 0.001f)
            assertEquals(10f, box.top, 0.001f)
            assertEquals(20f, box.bottom, 0.001f)
        }
    }

    @Test
    fun trackedClassUsesLowerHysteresisThreshold() {
        detectorFixture(classCount = 1).use { fixture ->
            fixture.process(endToEnd(10f, 10f, 30f, 30f, 0.70f, 0f), 1_000L)
            fixture.process(endToEnd(20f, 10f, 40f, 30f, 0.30f, 0f), 1_100L)

            val detection = fixture.detector.detections.value.single()
            assertTrue(detection.bbox.left > 10f)
            assertTrue(detection.confidence > 0.30f)
        }
    }

    @Test
    fun trackerCoastsFourMissedFramesThenExpires() {
        detectorFixture(classCount = 1).use { fixture ->
            fixture.process(endToEnd(10f, 10f, 30f, 30f, 0.90f, 0f), 1_000L)

            repeat(4) { index ->
                fixture.process(endToEnd(), 1_100L + index * 100L)
                assertEquals(1, fixture.detector.detections.value.size)
            }
            fixture.process(endToEnd(), 1_500L)
            assertTrue(fixture.detector.detections.value.isEmpty())
        }
    }

    @Test
    fun strongestDuplicateWinsBeforeTracking() {
        detectorFixture(classCount = 1).use { fixture ->
            fixture.process(
                endToEnd(
                    10f, 10f, 30f, 30f, 0.70f, 0f,
                    20f, 20f, 50f, 50f, 0.95f, 0f,
                ),
                1_000L,
            )

            val detection = fixture.detector.detections.value.single()
            assertEquals(0.95f, detection.confidence, 0.0001f)
            assertEquals(20f, detection.bbox.left, 0.001f)
        }
    }

    @Test
    fun releaseClassLabelsOverrideManifestlessLabels() {
        detectorFixture(classCount = 1, classLabels = listOf("Bundled clock")).use { fixture ->
            fixture.process(endToEnd(10f, 10f, 30f, 30f, 0.90f, 0f), 1_000L)

            assertEquals("Bundled clock", fixture.detector.currentLabel.value)
        }
    }

    @Test
    fun proximityFilterSuppressesFarLandmarkButAllowsMissingLocation() {
        detectorFixture(classCount = 1).use { fixture ->
            val output = endToEnd(10f, 10f, 30f, 30f, 0.95f, 0f)
            fixture.detector.updateUserLocation(
                latitude = 1.0,
                longitude = 1.0,
                accuracyMeters = 5.0,
            )
            fixture.processThree(output)
            assertTrue(fixture.detector.detections.value.isEmpty())

            fixture.detector.clearUserLocation()
            fixture.processThree(output, firstTimeMillis = 2_000L)
            assertEquals(1, fixture.detector.detections.value.size)
        }
    }

    @Test
    fun hidingBoxesDoesNotHideTheStrongestLabel() {
        detectorFixture(classCount = 1).use { fixture ->
            fixture.detector.setHideBoundingBoxes(true)
            fixture.processThree(endToEnd(10f, 10f, 30f, 30f, 0.95f, 0f))

            assertTrue(fixture.detector.detections.value.isEmpty())
            assertEquals("Landmark 0", fixture.detector.currentLabel.value)

            fixture.detector.setHideBoundingBoxes(false)
            fixture.process(endToEnd(10f, 10f, 30f, 30f, 0.95f, 0f), 1_500L)
            assertEquals(1, fixture.detector.detections.value.size)
        }
    }

    @Test
    fun notificationUsesStrictSixSecondCooldown() {
        detectorFixture(classCount = 1).use { fixture ->
            val output = endToEnd(10f, 10f, 30f, 30f, 0.95f, 0f)
            fixture.process(output, 1_000L)
            assertNotNull(fixture.detector.newlyDetectedLandmark.value)

            fixture.detector.consumeNewlyDetectedLandmark()
            fixture.process(output, 7_000L)
            assertNull(fixture.detector.newlyDetectedLandmark.value)

            fixture.process(output, 7_001L)
            assertNotNull(fixture.detector.newlyDetectedLandmark.value)
        }
    }

    private fun detectorFixture(
        classCount: Int,
        classLabels: List<String> = emptyList(),
    ): DetectorFixture {
        val manifest = manifest(classCount)
        val store = LandmarkManifestStore().apply { register(manifest) }
        val release = ActiveModelRelease(
            clusterId = "7",
            modelVersion = "run-6",
            modelFile = File("unused-model.tflite"),
            manifestFile = File("unused-manifest.json"),
            classCount = classCount,
            modelKey = "looksee-test-model",
            manifestKey = "looksee-test-manifest",
            classLabels = classLabels,
        )
        val detector = Detector(
            activeReleases = MutableStateFlow(null),
            manifestStore = store,
            modelFactory = DetectorModelFactory { error("Model factory is unused here.") },
            dispatcher = Dispatchers.Unconfined,
            observeActiveReleases = false,
        )
        return DetectorFixture(detector, release, manifest)
    }

    private fun manifest(classCount: Int): ClusterLandmarkManifest =
        ClusterLandmarkManifest(
            schemaVersion = 2,
            clusterId = 7,
            trainingRunId = "run-6",
            generatedAt = "2026-08-04T00:00:00Z",
            classCount = classCount,
            coordinateSystem = "WGS84",
            landmarks = (0 until classCount).associate { classIndex ->
                classIndex.toString() to LandmarkManifestEntry(
                    classIndex = classIndex,
                    landmarkId = "landmark-$classIndex",
                    datasetClassName = "landmark_$classIndex",
                    label = "Landmark $classIndex",
                    shortDescription = "Test landmark $classIndex",
                    latitude = 0.0,
                    longitude = 0.0,
                    positiveImageCount = null,
                )
            },
        )

    private fun endToEnd(vararg values: Float): DetectorModelOutput.EndToEnd =
        DetectorModelOutput.EndToEnd(
            values = values,
            shape = intArrayOf(1, values.size / 6, 6),
        )

    private data class DetectorFixture(
        val detector: Detector,
        val release: ActiveModelRelease,
        val manifest: ClusterLandmarkManifest,
    ) : AutoCloseable {
        fun process(output: DetectorModelOutput, eventTimeMillis: Long) {
            detector.processOutputForTesting(
                output = output,
                metadata = TEST_METADATA,
                release = release,
                manifest = manifest,
                eventTimeMillis = eventTimeMillis,
            )
        }

        fun processThree(
            output: DetectorModelOutput,
            firstTimeMillis: Long = 1_000L,
        ) {
            repeat(3) { index -> process(output, firstTimeMillis + index * 100L) }
        }

        override fun close() = detector.close()
    }

    private companion object {
        val TEST_METADATA = LetterboxMetadata(
            sourceWidth = 640,
            sourceHeight = 640,
            inputWidth = 640,
            inputHeight = 640,
            scale = 1f,
            padX = 0f,
            padY = 0f,
        )
    }
}
