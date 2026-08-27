package looksee.angelll.com.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterLandmarkManifestTest {
    @Test
    fun validatesSchemaTwoAndResolvesClassIndex() {
        val manifest = validManifest()

        manifest.validate()

        assertEquals(ClusterReleaseKey(7, "run-42"), manifest.releaseKey)
        assertEquals("landmark-0", manifest.landmark(0)?.landmarkId)
        assertNull(manifest.landmark(1))
    }

    @Test(expected = LandmarkManifestValidationException.InvalidCoordinateSystem::class)
    fun schemaTwoRequiresWgs84() {
        validManifest(coordinateSystem = null).validate()
    }

    @Test(expected = LandmarkManifestValidationException.InvalidCoordinate::class)
    fun rejectsOutOfRangeCoordinates() {
        validManifest(
            entry = validEntry(latitude = 91.0),
        ).validate()
    }

    @Test(expected = LandmarkManifestValidationException.ClassIndexMismatch::class)
    fun rejectsDictionaryAndEntryIndexMismatch() {
        validManifest(
            entry = validEntry(classIndex = 1),
        ).validate()
    }

    private fun validManifest(
        coordinateSystem: String? = "WGS84",
        entry: LandmarkManifestEntry = validEntry(),
    ) = ClusterLandmarkManifest(
        schemaVersion = 2,
        clusterId = 7,
        trainingRunId = "run-42",
        generatedAt = "2026-08-04T12:00:00Z",
        classCount = 1,
        coordinateSystem = coordinateSystem,
        landmarks = mapOf("0" to entry),
    )

    private fun validEntry(
        classIndex: Int = 0,
        latitude: Double = 40.7128,
    ) = LandmarkManifestEntry(
        classIndex = classIndex,
        landmarkId = "landmark-0",
        datasetClassName = "landmark_zero",
        label = "Landmark Zero",
        shortDescription = "A test landmark.",
        latitude = latitude,
        longitude = -74.0060,
        positiveImageCount = 25,
    )
}
