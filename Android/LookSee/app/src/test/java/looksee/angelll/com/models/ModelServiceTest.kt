package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsCompleteReleaseAndRegistersMatchingManifest() = runBlocking {
        val fixture = fixture()

        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        val loaded = fixture.service.state.value as ModelState.Loaded
        assertEquals(1, loaded.models.size)
        assertEquals("7|run-a", loaded.models.single().releaseIdentifier)
        assertEquals(1, loaded.models.single().classCount)
        assertTrue(loaded.models.single().modelFile.isFile)
        assertTrue(loaded.models.single().manifestFile.isFile)
        assertEquals(1.0, fixture.service.downloadProgress.value, 0.0)
        assertEquals(ModelPullReason.Single("nearby"), fixture.service.pullReason.value)
        assertNotNull(fixture.manifestStore.manifest(clusterId = 7, trainingRunId = "run-a"))
    }

    @Test
    fun acceptsZipContainingTfliteAndInstallsOnlyReleaseFiles() = runBlocking {
        val fixture = fixture(modelBytes = zipWithTflite())

        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        val model = (fixture.service.state.value as ModelState.Loaded).models.single()
        val releaseDirectory = requireNotNull(model.modelFile.parentFile)
        assertEquals(
            setOf("Model.tflite", "landmark-manifest.json"),
            releaseDirectory.list()?.toSet(),
        )
        assertTrue(model.modelFile.readBytes().copyOfRange(4, 8).contentEquals("TFL3".toByteArray()))
    }

    @Test
    fun reusesValidatedCompleteCacheWithoutDownloadingAgain() = runBlocking {
        val fixture = fixture()
        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        val secondService = ModelService(
            modelsDirectory = fixture.modelsDirectory,
            manifestStore = LandmarkManifestStore(),
            transport = fixture.transport,
            apiUrl = DISCOVERY_URL,
        )
        secondService.loadModels(latitude = 40.7128, longitude = -74.0060)

        assertEquals(1, fixture.transport.modelDownloadCount)
        assertEquals(1, fixture.transport.manifestDownloadCount)
        assertTrue(secondService.state.value is ModelState.Loaded)
    }

    @Test
    fun removesAndRepairsPartialCachedRelease() = runBlocking {
        val fixture = fixture()
        val partialDirectory = File(fixture.modelsDirectory, "cluster-7/run-a")
        assertTrue(partialDirectory.mkdirs())
        File(partialDirectory, "Model.tflite").writeBytes(validTflite())

        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        assertEquals(1, fixture.transport.modelDownloadCount)
        assertTrue(File(partialDirectory, "landmark-manifest.json").isFile)
        assertTrue(fixture.modelsDirectory.listFiles().orEmpty().none { it.name.startsWith(".staging-") })
    }

    @Test
    fun failsLoadWhenManifestIdentityDoesNotMatchModelVersion() = runBlocking {
        val fixture = fixture(manifestBytes = manifestJson(trainingRunId = "wrong-run").toByteArray())

        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        val failed = fixture.service.state.value as ModelState.Failed
        assertTrue(failed.message.contains("wrong-run"))
        assertTrue(failed.message.contains("run-a"))
        assertEquals(0, fixture.manifestStore.registeredReleaseCount)
        assertFalse(File(fixture.modelsDirectory, "cluster-7/run-a").exists())
        assertEquals(0, fixture.transport.modelDownloadCount)
    }

    @Test
    fun silentRefreshDetectsModelVersionChangeAndKeepsBothVersionedCaches() = runBlocking {
        val fixture = fixture()
        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        fixture.transport.discoveryBytes = discoveryJson(
            modelVersion = "run-b",
            modelUrl = MODEL_B_URL,
            manifestUrl = MANIFEST_B_URL,
        ).toByteArray()
        fixture.transport.getResponses[MANIFEST_B_URL] =
            ModelHttpResponse(200, manifestJson(trainingRunId = "run-b").toByteArray())
        fixture.transport.downloadResponses[MODEL_B_URL] = 200 to validTflite()

        val changed = fixture.service.refreshModelsSilentlyIfNeeded(
            latitude = 40.7128,
            longitude = -74.0060,
        )

        assertTrue(changed)
        val loaded = fixture.service.state.value as ModelState.Loaded
        assertEquals("run-b", loaded.models.single().modelVersion)
        assertTrue(File(fixture.modelsDirectory, "cluster-7/run-a").isDirectory)
        assertTrue(File(fixture.modelsDirectory, "cluster-7/run-b").isDirectory)
    }

    @Test
    fun deleteModelsClearsFilesManifestStateAndProgress() = runBlocking {
        val fixture = fixture()
        fixture.service.loadModels(latitude = 40.7128, longitude = -74.0060)

        fixture.service.deleteModels()

        assertTrue(fixture.service.state.value is ModelState.NotLoaded)
        assertEquals(ModelPullReason.None, fixture.service.pullReason.value)
        assertEquals(0.0, fixture.service.downloadProgress.value, 0.0)
        assertEquals(0, fixture.manifestStore.registeredReleaseCount)
        assertTrue(fixture.modelsDirectory.listFiles().orEmpty().isEmpty())
    }

    private fun fixture(
        modelBytes: ByteArray = validTflite(),
        manifestBytes: ByteArray = manifestJson().toByteArray(),
    ): Fixture {
        val modelsDirectory = temporaryFolder.newFolder("models-${System.nanoTime()}")
        val transport = FakeModelTransport(
            discoveryBytes = discoveryJson().toByteArray(),
        ).apply {
            getResponses[MANIFEST_URL] = ModelHttpResponse(200, manifestBytes)
            downloadResponses[MODEL_URL] = 200 to modelBytes
        }
        val manifestStore = LandmarkManifestStore()
        val service = ModelService(
            modelsDirectory = modelsDirectory,
            manifestStore = manifestStore,
            transport = transport,
            apiUrl = DISCOVERY_URL,
        )
        return Fixture(service, modelsDirectory, manifestStore, transport)
    }

    private fun discoveryJson(
        modelVersion: String = "run-a",
        modelUrl: String = MODEL_URL,
        manifestUrl: String = MANIFEST_URL,
    ) =
        """
        {
          "models": [{
            "clusterId": "cluster-7",
            "modelVersion": "$modelVersion",
            "downloadUrl": "$modelUrl",
            "manifestUrl": "$manifestUrl",
            "modelKey": "models/7/$modelVersion/model.tflite",
            "manifestKey": "models/7/$modelVersion/landmark-manifest.json",
            "manifestSchemaVersion": 2,
            "classCount": 1
          }],
          "reason": "nearby",
          "objects": [{
            "clusterId": 7.0,
            "lat": 40.7128,
            "lon": -74.0060
          }]
        }
        """.trimIndent()

    private fun manifestJson(trainingRunId: String = "run-a") =
        """
        {
          "schemaVersion": 2,
          "clusterId": 7,
          "trainingRunId": "$trainingRunId",
          "generatedAt": "2026-08-04T12:00:00Z",
          "classCount": 1,
          "coordinateSystem": "WGS84",
          "landmarks": {
            "0": {
              "classIndex": 0,
              "landmarkId": "landmark-0",
              "datasetClassName": "landmark_zero",
              "label": "Landmark Zero",
              "shortDescription": "A test landmark.",
              "latitude": 40.7128,
              "longitude": -74.0060,
              "positiveImageCount": 25
            }
          }
        }
        """.trimIndent()

    private fun validTflite(): ByteArray =
        byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte(), 0)

    private fun zipWithTflite(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("android/model/looksee.tflite"))
            zip.write(validTflite())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private data class Fixture(
        val service: ModelService,
        val modelsDirectory: File,
        val manifestStore: LandmarkManifestStore,
        val transport: FakeModelTransport,
    )

    private class FakeModelTransport(
        var discoveryBytes: ByteArray,
    ) : ModelTransport {
        val getResponses = mutableMapOf<String, ModelHttpResponse>()
        val downloadResponses = mutableMapOf<String, Pair<Int, ByteArray>>()
        var manifestDownloadCount = 0
        var modelDownloadCount = 0

        override suspend fun postJson(url: String, jsonBody: String): ModelHttpResponse =
            ModelHttpResponse(200, discoveryBytes)

        override suspend fun get(url: String): ModelHttpResponse {
            manifestDownloadCount += 1
            return getResponses.getValue(url)
        }

        override suspend fun download(url: String, destination: File): Int {
            modelDownloadCount += 1
            val (status, bytes) = downloadResponses.getValue(url)
            if (status in 200..299) destination.writeBytes(bytes)
            return status
        }
    }

    private companion object {
        const val DISCOVERY_URL = "https://example.test/discover"
        const val MODEL_URL = "https://example.test/run-a-model"
        const val MANIFEST_URL = "https://example.test/run-a-manifest"
        const val MODEL_B_URL = "https://example.test/run-b-model"
        const val MANIFEST_B_URL = "https://example.test/run-b-manifest"
    }
}
