package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMediaHistoryTest {
    @Test
    fun serviceClampsLimitAndEncodesPaginationToken(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"landmarkId":"l1","landmarkLabel":"Clock","items":[],"count":0}""",
            ),
        )
        val service = BusinessMediaHistoryService(fixedToken(), http)

        service.fetchHistory("landmark/1", limit = 500, nextToken = "a+b / c")

        val request = http.requests.single()
        assertTrue(request.url.contains("landmark%2F1"))
        assertTrue(request.url.contains("limit=100"))
        assertTrue(request.url.contains("nextToken=a%2Bb%20%2F%20c"))
        assertEquals("Bearer id-token", request.authorization)
    }

    @Test
    fun viewModelAppendsOnlyNewIdsDuringPagination(): Unit = runBlocking {
        val responses = ArrayDeque(
            listOf(
                BusinessMediaHistoryResponse(
                    landmarkId = "l1",
                    landmarkLabel = "Clock",
                    items = listOf(historyItem("one"), historyItem("two")),
                    count = 2,
                    nextToken = "page-2",
                ),
                BusinessMediaHistoryResponse(
                    landmarkId = "l1",
                    landmarkLabel = "Clock",
                    items = listOf(historyItem("two"), historyItem("three")),
                    count = 2,
                    nextToken = null,
                ),
            ),
        )
        val source = object : BusinessMediaHistoryDataSource {
            override suspend fun fetchHistory(
                landmarkId: String,
                limit: Int,
                nextToken: String?,
            ): BusinessMediaHistoryResponse = responses.removeFirst()
        }
        val viewModel = BusinessMediaHistoryViewModel(
            landmarkId = "l1",
            landmarkLabel = "Old label",
            service = source,
        )

        viewModel.loadInitial()
        viewModel.loadMore()

        assertEquals(listOf("one", "two", "three"), viewModel.items.value.map { it.id })
        assertEquals("Clock", viewModel.landmarkLabel.value)
        assertEquals(false, viewModel.hasMoreItems)
    }

    @Test
    fun itemPresentationFieldsMatchTheSwiftBehavior() {
        val item = BusinessMediaHistoryItem(
            id = "one",
            datasetRole = "positive",
            mediaKind = "video",
            originalFilename = " ",
            status = "ready_for_training",
            uploadedAt = 10,
        )

        assertEquals("Positive • Video", item.roleAndMediaTitle)
        assertEquals("Ready For Training", item.normalizedStatus)
        assertEquals("Unnamed media", item.displayFilename)
    }

    private fun historyItem(id: String) = BusinessMediaHistoryItem(
        id = id,
        submissionId = "submission-$id",
        uploadedAt = 1,
    )
}
