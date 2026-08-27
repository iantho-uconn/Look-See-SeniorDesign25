package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelServiceAndroidDiscoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun discoveryRequestIdentifiesAndroidLiteRtClient() = runBlocking {
        val transport = RecordingTransport(responseJson = EMPTY_RESPONSE)
        val service = makeService(transport)

        service.loadModels(latitude = 41.9, longitude = -73.13)

        assertTrue(transport.postBody.contains("\"platform\":\"android\""))
        assertTrue(transport.postBody.contains("\"format\":\"litert\""))
        assertEquals(ModelState.Loaded(emptyList()), service.state.value)
    }

    @Test
    fun coreMlDiscoveryRecordFailsWithActionableAndroidMessage() = runBlocking {
        val transport = RecordingTransport(responseJson = CORE_ML_RESPONSE)
        val service = makeService(transport)

        service.loadModels(latitude = 41.9, longitude = -73.13)

        val failed = service.state.value as ModelState.Failed
        assertTrue(failed.message.contains("0.mlpackage.zip"))
        assertTrue(failed.message.contains("Android .tflite release"))
        assertEquals(0, transport.getCount)
        assertEquals(0, transport.downloadCount)
    }

    private fun makeService(transport: ModelTransport): ModelService = ModelService(
        modelsDirectory = temporaryFolder.newFolder("models"),
        manifestStore = LandmarkManifestStore(),
        transport = transport,
        apiUrl = "https://example.test/discover",
    )

    private class RecordingTransport(
        private val responseJson: String,
    ) : ModelTransport {
        var postBody: String = ""
        var getCount: Int = 0
        var downloadCount: Int = 0

        override suspend fun postJson(url: String, jsonBody: String): ModelHttpResponse {
            postBody = jsonBody
            return ModelHttpResponse(200, responseJson.toByteArray())
        }

        override suspend fun get(url: String): ModelHttpResponse {
            getCount += 1
            error("GET should not be reached for an incompatible model artifact")
        }

        override suspend fun download(url: String, destination: File): Int {
            downloadCount += 1
            error("Download should not be reached for an incompatible model artifact")
        }
    }

    private companion object {
        val EMPTY_RESPONSE = """
            {
              "models": [],
              "reason": "no nearby release",
              "objects": []
            }
        """.trimIndent()

        val CORE_ML_RESPONSE = """
            {
              "models": [
                {
                  "clusterId": "0",
                  "modelVersion": "version-1",
                  "downloadUrl": "https://example.test/0.mlpackage.zip",
                  "manifestUrl": "https://example.test/landmark-manifest.json",
                  "modelKey": "ml_conversions/cluster-0/version-1/0.mlpackage.zip",
                  "manifestKey": "ml_conversions/cluster-0/version-1/landmark-manifest.json",
                  "manifestSchemaVersion": 2,
                  "classCount": 23
                }
              ],
              "reason": "test release",
              "objects": []
            }
        """.trimIndent()
    }
}
