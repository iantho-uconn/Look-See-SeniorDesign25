package looksee.angelll.com.models

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            status = "completed",
            rawStatus = "DINO_COMPLETE",
            uploadedAt = 10,
        )

        assertEquals("Positive • Video", item.roleAndMediaTitle)
        assertEquals("Ready", item.normalizedStatus)
        assertEquals("DINO_COMPLETE", item.backendStatusText)
        assertEquals("Unnamed media", item.displayFilename)
    }

    @Test
    fun uploaderRoleKindAndRawStatusUseCompatibilityFallbacks() {
        val item = BusinessMediaHistoryItem(
            datasetRole = " hard_negative ",
            mediaKind = "IMAGE",
            status = null,
            rawStatus = "upload_pending",
            uploadedBy = BusinessMediaHistoryUploader(
                displayName = " ",
                email = "owner@example.com",
                userId = "user-1",
            ),
        )

        assertEquals("owner@example.com", item.uploadedBy.displayText)
        assertEquals(BusinessMediaHistoryRole.HARD_NEGATIVE, item.role)
        assertEquals(BusinessMediaHistoryKind.PHOTO, item.kind)
        assertEquals(BusinessMediaLifecycleState.PROCESSING, item.lifecycleState)
    }

    @Test
    fun delayedHardNegativeCanRetryAndRecentRetryRestartsTheClock() {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        val delayed = BusinessMediaHistoryItem(
            id = "negative",
            submissionId = "negative-1",
            batchId = "batch-1",
            datasetRole = "hard-negative",
            mediaKind = "video",
            status = "processing",
            uploadedAt = now.minusSeconds(2 * 60 * 60).epochSecond,
        )

        assertTrue(delayed.isProcessingDelayedAt(now))
        assertTrue(delayed.canRetryProcessingAt(now))

        val recentlyRetried = delayed.copy(
            lastRetryAt = now.minusSeconds(30 * 60).epochSecond,
        )
        assertFalse(recentlyRetried.isProcessingDelayedAt(now))
        assertFalse(recentlyRetried.canRetryProcessingAt(now))
        assertTrue(delayed.copy(status = "failed").canRetryProcessingAt(now))
    }

    @Test
    fun retryProcessingRequeuesThenRefreshesAndAdvancesPollRevision(): Unit = runBlocking {
        val retryable = retryableHistoryItem(status = "failed")
        val responses = ArrayDeque(
            listOf(
                historyResponse(retryable),
                historyResponse(retryable.copy(status = "processing", retryCount = 1)),
            ),
        )
        val source = queuedHistorySource(responses)
        val retries = mutableListOf<Triple<String, String, String>>()
        val retrySource = HardNegativeRetryDataSource { landmarkId, batchId, negativeId ->
            retries += Triple(landmarkId, batchId, negativeId)
            BusinessHardNegativeCompleteResponse(
                landmarkId = landmarkId,
                batchId = batchId,
                processedCount = 1,
                processed = listOf(
                    BusinessHardNegativeProcessedItem(negativeId, "PROCESSING"),
                ),
            )
        }
        val viewModel = BusinessMediaHistoryViewModel(
            landmarkId = "l1",
            landmarkLabel = "Clock",
            service = source,
            retryService = retrySource,
        )

        viewModel.loadInitial()
        viewModel.retryProcessing(viewModel.items.value.single())

        assertEquals(listOf(Triple("l1", "batch-1", "negative-1")), retries)
        assertEquals(BusinessMediaLifecycleState.PROCESSING, viewModel.items.value.single().lifecycleState)
        assertEquals(1, viewModel.processingPollRevision.value)
        assertTrue(viewModel.retryingItemIds.value.isEmpty())
        assertTrue(viewModel.retryErrorsByItemId.value.isEmpty())
    }

    @Test
    fun boundedPollingStopsWhenBackendReportsReady(): Unit = runBlocking {
        val responses = ArrayDeque(
            listOf(
                historyResponse(retryableHistoryItem(status = "processing")),
                historyResponse(retryableHistoryItem(status = "ready")),
            ),
        )
        var fetchCount = 0
        val source = object : BusinessMediaHistoryDataSource {
            override suspend fun fetchHistory(
                landmarkId: String,
                limit: Int,
                nextToken: String?,
            ): BusinessMediaHistoryResponse {
                fetchCount += 1
                return responses.removeFirst()
            }
        }
        val viewModel = BusinessMediaHistoryViewModel(
            landmarkId = "l1",
            landmarkLabel = "Clock",
            service = source,
            pollDelay = { _ -> },
        )

        viewModel.loadInitial()
        viewModel.pollProcessingItems(maximumAttempts = 3, intervalMillis = 0)

        assertEquals(2, fetchCount)
        assertEquals(BusinessMediaLifecycleState.READY, viewModel.items.value.single().lifecycleState)
        assertFalse(viewModel.isPollingProcessingItems.value)
    }

    private fun historyItem(id: String) = BusinessMediaHistoryItem(
        id = id,
        submissionId = "submission-$id",
        uploadedAt = 1,
    )

    private fun retryableHistoryItem(status: String) = BusinessMediaHistoryItem(
        id = "item-1",
        submissionId = "negative-1",
        batchId = "batch-1",
        datasetRole = "hard-negative",
        mediaKind = "video",
        status = status,
        uploadedAt = 1,
    )

    private fun historyResponse(item: BusinessMediaHistoryItem) =
        BusinessMediaHistoryResponse(
            landmarkId = "l1",
            landmarkLabel = "Clock",
            items = listOf(item),
            count = 1,
        )

    private fun queuedHistorySource(
        responses: ArrayDeque<BusinessMediaHistoryResponse>,
    ) = object : BusinessMediaHistoryDataSource {
        override suspend fun fetchHistory(
            landmarkId: String,
            limit: Int,
            nextToken: String?,
        ): BusinessMediaHistoryResponse = responses.removeFirst()
    }
}
