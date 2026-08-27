package looksee.angelll.com.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledModelReleaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validatesExactAndroidTensorAndLandmarkContract() {
        val model = validTflite()
        val validated = BundledModelReleaseValidator.validate(
            release = release(model),
            modelFile = model,
            manifest = manifest(),
        )

        assertEquals("0", validated.clusterId)
        assertEquals("run-1", validated.modelVersion)
        assertEquals("yolo26", validated.modelFamily)
        assertEquals(2, validated.classCount)
        assertEquals(listOf("Clock", "Library"), validated.classLabels)
    }

    @Test
    fun rejectsModelWhoseShaDoesNotMatchRelease() {
        val model = validTflite()
        val release = release(model).copy(modelSha256 = "0".repeat(64))

        val error = expectValidationFailure {
            BundledModelReleaseValidator.validate(release, model, manifest())
        }

        assertTrue(error.message.orEmpty().contains("SHA-256 does not match"))
    }

    @Test
    fun rejectsUnsupportedOutputFieldOrdering() {
        val model = validTflite()
        val release = release(model).copy(
            outputContract = release(model).outputContract?.copy(
                detectionFields = listOf(
                    "confidence",
                    "x1",
                    "y1",
                    "x2",
                    "y2",
                    "classIndex",
                ),
            ),
        )

        val error = expectValidationFailure {
            BundledModelReleaseValidator.validate(release, model, manifest())
        }

        assertTrue(error.message.orEmpty().contains("field order"))
    }

    @Test
    fun bundledModelIsNotInstalledWhenItsRequiredManifestIsMissing() {
        val model = validTflite()
        val missingManifest = File(temporaryFolder.root, "missing.json")
        val bundled = BundledTestModel(
            modelFile = model,
            manifestFile = missingManifest,
            classCount = 2,
        )

        assertEquals(false, bundled.isInstalled)
    }

    private fun validTflite(): File = temporaryFolder.newFile("model.tflite").apply {
        writeBytes(
            byteArrayOf(0x1c, 0, 0, 0) +
                "TFL3".toByteArray() +
                byteArrayOf(1, 2, 3, 4),
        )
    }

    private fun release(model: File) = BundledModelReleaseMetadata(
        schemaVersion = 1,
        status = "ready",
        platform = "android",
        format = "litert",
        fileExtension = ".tflite",
        clusterId = "0",
        modelVersion = "run-1",
        modelFamily = "yolo26",
        task = "detect",
        modelFile = model.name,
        modelSha256 = BundledModelReleaseValidator.sha256(model),
        modelSizeBytes = model.length(),
        landmarkManifest = "landmark-manifest.json",
        manifestSchemaVersion = 2,
        classCount = 2,
        imageSize = 640,
        batchSize = 1,
        precision = "fp32",
        inputLayout = "NCHW",
        preprocessing = BundledModelPreprocessing(
            colorOrder = "RGB",
            resize = "letterbox",
            targetWidth = 640,
            targetHeight = 640,
            pixelScale = "0_to_1",
        ),
        outputContract = BundledModelOutputContract(
            endToEnd = true,
            shape = listOf(1, 300, 6),
            detectionFields = listOf(
                "x1",
                "y1",
                "x2",
                "y2",
                "confidence",
                "classIndex",
            ),
            boxFormat = "xyxy",
            nmsRequired = false,
            confidenceFilterRequired = true,
        ),
        tensors = BundledModelTensors(
            inputLayout = "NCHW",
            inputs = listOf(
                BundledModelTensor(
                    name = "serving_default_args_0",
                    index = 0,
                    shape = listOf(1, 3, 640, 640),
                    shapeSignature = listOf(1, 3, 640, 640),
                    dataType = "float32",
                ),
            ),
            outputs = listOf(
                BundledModelTensor(
                    name = "serving_default_output_0_output",
                    index = 733,
                    shape = listOf(1, 300, 6),
                    shapeSignature = listOf(1, 300, 6),
                    dataType = "float32",
                ),
            ),
            smokeTest = BundledModelSmokeTest("passed"),
        ),
    )

    private fun manifest() = ClusterLandmarkManifest(
        schemaVersion = 2,
        clusterId = 0,
        trainingRunId = "run-1",
        generatedAt = "2026-08-18T00:00:00Z",
        classCount = 2,
        coordinateSystem = "WGS84",
        landmarks = mapOf(
            "0" to LandmarkManifestEntry(
                classIndex = 0,
                landmarkId = "clock",
                datasetClassName = "Clock",
                label = "Clock",
                shortDescription = "A clock.",
                latitude = 41.0,
                longitude = -72.0,
                positiveImageCount = 10,
            ),
            "1" to LandmarkManifestEntry(
                classIndex = 1,
                landmarkId = "library",
                datasetClassName = "Library",
                label = "Library",
                shortDescription = "A library.",
                latitude = 41.1,
                longitude = -72.1,
                positiveImageCount = 10,
            ),
        ),
    ).also { it.validate() }

    private fun expectValidationFailure(block: () -> Unit): IllegalArgumentException {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            return error
        }
        error("Unreachable")
    }
}
