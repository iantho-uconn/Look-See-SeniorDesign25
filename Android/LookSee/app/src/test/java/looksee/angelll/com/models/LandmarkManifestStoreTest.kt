package looksee.angelll.com.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LandmarkManifestStoreTest {
    @Test
    fun loadsAndResolvesUsingExactReleasePair() {
        val store = LandmarkManifestStore()

        store.load(validJson(trainingRunId = "run-a", label = "Release A"))
        store.load(validJson(trainingRunId = "run-b", label = "Release B"))

        assertEquals(2, store.registeredReleaseCount)
        assertEquals("Release A", store.resolve(7, "run-a", 0)?.label)
        assertEquals("Release B", store.resolve("7", "run-b", 0)?.label)
        assertNull(store.resolve(7, "missing-release", 0))
        assertNull(store.resolve("not-a-number", "run-a", 0))
        assertNull(store.resolve(7, "run-a", -1))
    }

    @Test
    fun removalTargetsOnlyOneRelease() {
        val store = LandmarkManifestStore()
        store.load(validJson(trainingRunId = "run-a", label = "Release A"))
        store.load(validJson(trainingRunId = "run-b", label = "Release B"))

        store.remove(clusterId = 7, trainingRunId = "run-a")

        assertNull(store.manifest(7, "run-a"))
        assertNotNull(store.manifest(7, "run-b"))
        assertEquals(1, store.registeredReleaseCount)
    }

    @Test(expected = LandmarkManifestDecodingException::class)
    fun rejectsStringWhereIntegerIsRequired() {
        LandmarkManifestStore().load(
            validJson(trainingRunId = "run-a", label = "Release A")
                .replace("\"classCount\": 1", "\"classCount\": \"1\""),
        )
    }

    private fun validJson(trainingRunId: String, label: String) =
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
              "label": "$label",
              "shortDescription": "A test landmark.",
              "latitude": 40.7128,
              "longitude": -74.0060,
              "positiveImageCount": 25
            }
          }
        }
        """.trimIndent()
}
