package looksee.angelll.com.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryServiceTest {
    @Test
    fun fetchesClusterLibraryAndDecodesSnakeCaseDescription(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            libraryResponse("landmark-1", "Historic tower", clusterId = 7),
        )
        val service = LibraryService(
            activeClusterIds = MutableStateFlow(null),
            httpClient = http,
            observeActiveCluster = false,
        )

        service.fetchLandmarks(7)

        assertTrue(http.requests.single().url.contains("cluster_id=7"))
        assertEquals("landmark-1", service.items.value.single().id)
        assertEquals("Historic tower", service.items.value.single().shortDescription)
        assertFalse(service.isLoading.value)
        assertNull(service.errorMessage.value)
        service.close()
    }

    @Test
    fun activeClusterChangesAutomaticallyRefreshAndNullClearsLibrary(): Unit = runBlocking {
        val activeCluster = MutableStateFlow<String?>(null)
        val http = RecordingBusinessHttpClient(
            libraryResponse("landmark-2", "Second", clusterId = 2),
        )
        val service = LibraryService(
            activeClusterIds = activeCluster,
            httpClient = http,
            dispatcher = Dispatchers.Unconfined,
        )

        activeCluster.value = "2"
        yield()
        assertEquals("landmark-2", service.items.value.single().id)

        activeCluster.value = null
        yield()
        assertTrue(service.items.value.isEmpty())
        assertNull(service.errorMessage.value)
        service.close()
    }

    @Test
    fun serverFailureLeavesLibraryEmptyAndPublishesDetails(): Unit = runBlocking {
        val service = LibraryService(
            activeClusterIds = MutableStateFlow(null),
            httpClient = RecordingBusinessHttpClient(
                jsonResponse("denied", statusCode = 403),
            ),
            observeActiveCluster = false,
        )

        service.fetchLandmarks(9)

        assertTrue(service.items.value.isEmpty())
        assertTrue(service.errorMessage.value.orEmpty().contains("403"))
        service.close()
    }

    private fun libraryResponse(
        landmarkId: String,
        description: String,
        clusterId: Int,
    ) = jsonResponse(
        """{"items":[{"landmarkId":"$landmarkId","label":"Tower","short_description":"$description","clusterId":$clusterId}]}""",
    )
}
