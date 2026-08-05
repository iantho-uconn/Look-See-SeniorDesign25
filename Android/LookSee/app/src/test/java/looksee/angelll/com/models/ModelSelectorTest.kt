package looksee.angelll.com.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelSelectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsToFirstCompleteReleaseAndSkipsIncompleteRecords() {
        val incomplete = model(clusterId = "1", modelVersion = "run-a", complete = false)
        val fallback = model(clusterId = "2", modelVersion = "run-b")
        val selector = selector(ModelState.Loaded(listOf(incomplete, fallback)))

        await { selector.activeRelease.value != null }

        assertEquals("2|run-b", selector.activeRelease.value?.releaseIdentifier)
        assertEquals("2", selector.activeClusterId.value)
        selector.close()
    }

    @Test
    fun selectsNearestCompleteReleaseInsideSeventyFiveMeters() {
        val farther = model(
            clusterId = "1",
            modelVersion = "run-a",
            objects = listOf(ObjectLocation("1", 40.00040, -74.0)),
        )
        val nearer = model(
            clusterId = "2",
            modelVersion = "run-b",
            objects = listOf(ObjectLocation("2", 40.00010, -74.0)),
        )
        val selector = selector(ModelState.Loaded(listOf(farther, nearer)))

        selector.updateUserLocation(latitude = 40.0, longitude = -74.0)

        assertEquals("2|run-b", selector.activeRelease.value?.releaseIdentifier)
        selector.close()
    }

    @Test
    fun ignoresObjectLocationsThatBelongToAnotherCluster() {
        val first = model(
            clusterId = "1",
            modelVersion = "run-a",
            objects = listOf(ObjectLocation("2", 40.0, -74.0)),
        )
        val second = model(
            clusterId = "2",
            modelVersion = "run-b",
            objects = listOf(ObjectLocation("2", 41.0, -74.0)),
        )
        val selector = selector(ModelState.Loaded(listOf(first, second)))

        selector.updateUserLocation(latitude = 40.0, longitude = -74.0)

        // Neither valid cluster/object pair is nearby, so the first release remains fallback.
        assertEquals("1|run-a", selector.activeRelease.value?.releaseIdentifier)
        selector.close()
    }

    @Test
    fun keepsCurrentExactReleaseWhenUserLeavesActivationRadius() {
        val first = model(
            clusterId = "1",
            modelVersion = "run-a",
            objects = listOf(ObjectLocation("1", 40.0, -74.0)),
        )
        val second = model(
            clusterId = "2",
            modelVersion = "run-b",
            objects = listOf(ObjectLocation("2", 41.0, -74.0)),
        )
        val selector = selector(ModelState.Loaded(listOf(first, second)))
        selector.updateUserLocation(latitude = 41.0, longitude = -74.0)
        assertEquals("2|run-b", selector.activeRelease.value?.releaseIdentifier)

        selector.updateUserLocation(latitude = 42.0, longitude = -74.0)

        assertEquals("2|run-b", selector.activeRelease.value?.releaseIdentifier)
        selector.close()
    }

    @Test
    fun choosesNewFallbackWhenCurrentExactReleaseIsNoLongerLoaded() {
        val state = MutableStateFlow<ModelState>(
            ModelState.Loaded(
                listOf(
                    model(
                        clusterId = "1",
                        modelVersion = "run-a",
                        objects = listOf(ObjectLocation("1", 40.0, -74.0)),
                    ),
                ),
            ),
        )
        val selector = ModelSelector(state)
        selector.updateUserLocation(latitude = 40.0, longitude = -74.0)
        assertEquals("1|run-a", selector.activeRelease.value?.releaseIdentifier)

        state.value = ModelState.Loaded(
            listOf(model(clusterId = "1", modelVersion = "run-b")),
        )
        await { selector.activeModelVersion == "run-b" }

        assertEquals("1|run-b", selector.activeRelease.value?.releaseIdentifier)
        selector.close()
    }

    @Test
    fun notLoadedClearsActiveReleaseAndCompatibilityState() {
        val state = MutableStateFlow<ModelState>(
            ModelState.Loaded(listOf(model(clusterId = "7", modelVersion = "run-a"))),
        )
        val selector = ModelSelector(state)
        await { selector.activeRelease.value != null }

        state.value = ModelState.NotLoaded
        await { selector.activeRelease.value == null }

        assertNull(selector.activeRelease.value)
        assertNull(selector.activeClusterId.value)
        assertNull(selector.activeModelVersion)
        assertNull(selector.activeClassCount)
        selector.close()
    }

    @Test
    fun loadingAndFailureKeepCurrentCompleteRelease() {
        val state = MutableStateFlow<ModelState>(
            ModelState.Loaded(listOf(model(clusterId = "7", modelVersion = "run-a"))),
        )
        val selector = ModelSelector(state)
        await { selector.activeRelease.value != null }
        val original = selector.activeRelease.value

        state.value = ModelState.Loading
        await { state.value == ModelState.Loading }
        assertSame(original, selector.activeRelease.value)

        state.value = ModelState.Failed("offline")
        await { state.value is ModelState.Failed }
        assertSame(original, selector.activeRelease.value)
        assertEquals("7", selector.activeClusterId.value)
        selector.close()
    }

    private fun selector(initialState: ModelState): ModelSelector =
        ModelSelector(MutableStateFlow(initialState))

    private fun model(
        clusterId: String,
        modelVersion: String,
        objects: List<ObjectLocation> = emptyList(),
        complete: Boolean = true,
    ): ModelInfo {
        val directory = temporaryFolder.newFolder(
            "cluster-$clusterId-$modelVersion-${System.nanoTime()}",
        )
        val modelFile = File(directory, "Model.tflite").apply { writeText("model") }
        val manifestFile = File(directory, "landmark-manifest.json").apply {
            writeText("manifest")
        }
        if (!complete) manifestFile.delete()

        return ModelInfo(
            name = clusterId,
            downloadUrl = "https://example.test/$clusterId/$modelVersion/model",
            manifestUrl = "https://example.test/$clusterId/$modelVersion/manifest",
            reason = "test",
            clusterId = clusterId,
            modelVersion = modelVersion,
            modelKey = "models/$clusterId/$modelVersion/model.tflite",
            manifestKey = "models/$clusterId/$modelVersion/landmark-manifest.json",
            manifestSchemaVersion = 2,
            classCount = 1,
            modelFile = modelFile,
            manifestFile = manifestFile,
            objects = objects,
        )
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(2_000) {
            while (!condition()) yield()
        }
    }
}
