package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessLandmarksViewModelTest {
    @Test
    fun cachedAndFetchedLandmarksRemainSorted(): Unit = runBlocking {
        val cache = InMemoryBusinessLandmarkCache(
            listOf(BusinessLandmark(landmarkId = "z", label = "Zoo")),
        )
        val dataSource = BusinessLandmarkDataSource {
            BusinessLandmarkListResponse(
                items = listOf(
                    BusinessLandmark(landmarkId = "b", label = "bridge"),
                    BusinessLandmark(landmarkId = "a", label = "Archive"),
                ),
                count = 2,
            )
        }
        val viewModel = BusinessLandmarksViewModel(dataSource, cache)

        viewModel.loadLandmarks()

        assertEquals(listOf("Archive", "bridge"), viewModel.landmarks.value.map { it.label })
        assertEquals(viewModel.landmarks.value, cache.read())
    }

    @Test
    fun replaceAndRemoveUpdateTheCachedCollection() {
        val original = BusinessLandmark(landmarkId = "l1", label = "Clock")
        val cache = InMemoryBusinessLandmarkCache(listOf(original))
        val viewModel = BusinessLandmarksViewModel(
            BusinessLandmarkDataSource { BusinessLandmarkListResponse() },
            cache,
        )

        viewModel.replaceLandmark(original.copy(shortDescription = "Updated"))
        assertEquals("Updated", cache.read().single().shortDescription)

        viewModel.removeLandmark("l1")
        assertEquals(emptyList<BusinessLandmark>(), cache.read())
    }
}
