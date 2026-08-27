package looksee.angelll.com.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BusinessMediaHistoryViewModel(
    val landmarkId: String,
    landmarkLabel: String,
    private val pageSize: Int = 25,
    private val service: BusinessMediaHistoryDataSource = BusinessMediaHistoryService(),
    private val retryService: HardNegativeRetryDataSource = BusinessLandmarkService(),
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val _items = MutableStateFlow<List<BusinessMediaHistoryItem>>(emptyList())
    val items: StateFlow<List<BusinessMediaHistoryItem>> = _items.asStateFlow()

    private val _landmarkLabel = MutableStateFlow(landmarkLabel)
    val landmarkLabel: StateFlow<String> = _landmarkLabel.asStateFlow()

    private val _isLoadingInitial = MutableStateFlow(false)
    val isLoadingInitial: StateFlow<Boolean> = _isLoadingInitial.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isPollingProcessingItems = MutableStateFlow(false)
    val isPollingProcessingItems: StateFlow<Boolean> =
        _isPollingProcessingItems.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _nextToken = MutableStateFlow<String?>(null)
    val nextToken: StateFlow<String?> = _nextToken.asStateFlow()

    private val _retryingItemIds = MutableStateFlow<Set<String>>(emptySet())
    val retryingItemIds: StateFlow<Set<String>> = _retryingItemIds.asStateFlow()

    private val _retryErrorsByItemId = MutableStateFlow<Map<String, String>>(emptyMap())
    val retryErrorsByItemId: StateFlow<Map<String, String>> =
        _retryErrorsByItemId.asStateFlow()

    private val _processingPollRevision = MutableStateFlow(0)
    val processingPollRevision: StateFlow<Int> = _processingPollRevision.asStateFlow()

    private var hasLoaded = false
    private var pollingGeneration = 0

    val hasMoreItems: Boolean
        get() = !_nextToken.value.isNullOrBlank()

    val processingItemIds: List<String>
        get() = _items.value
            .filter { it.lifecycleState == BusinessMediaLifecycleState.PROCESSING }
            .map(BusinessMediaHistoryItem::id)
            .sorted()

    val processingPollKey: String
        get() = processingItemIds.joinToString("|") + "#${_processingPollRevision.value}"

    suspend fun loadInitial() {
        if (hasLoaded || _isLoadingInitial.value) return
        _isLoadingInitial.value = true
        _errorMessage.value = null
        try {
            apply(service.fetchHistory(landmarkId, pageSize), replacingItems = true)
            hasLoaded = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = error.message ?: "Failed to load media history."
        } finally {
            _isLoadingInitial.value = false
        }
    }

    suspend fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _errorMessage.value = null
        try {
            apply(service.fetchHistory(landmarkId, pageSize), replacingItems = true)
            hasLoaded = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = error.message ?: "Failed to refresh media history."
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun loadMore() {
        val token = _nextToken.value?.takeIf(String::isNotBlank) ?: return
        if (_isLoadingMore.value) return
        _isLoadingMore.value = true
        _errorMessage.value = null
        try {
            apply(
                service.fetchHistory(landmarkId, pageSize, token),
                replacingItems = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = error.message ?: "Failed to load more media history."
        } finally {
            _isLoadingMore.value = false
        }
    }

    suspend fun retry() {
        if (_items.value.isEmpty()) {
            hasLoaded = false
            loadInitial()
        } else {
            refresh()
        }
    }

    fun isRetrying(item: BusinessMediaHistoryItem): Boolean =
        item.id in _retryingItemIds.value

    fun retryError(item: BusinessMediaHistoryItem): String? =
        _retryErrorsByItemId.value[item.id]

    suspend fun retryProcessing(item: BusinessMediaHistoryItem) {
        val batchId = item.batchId?.takeIf(String::isNotBlank) ?: return
        if (!item.canRetryProcessing || isRetrying(item)) return

        _retryingItemIds.value = _retryingItemIds.value + item.id
        _retryErrorsByItemId.value = _retryErrorsByItemId.value - item.id
        try {
            retryService.retryHardNegativeProcessing(
                landmarkId = landmarkId,
                batchId = batchId,
                negativeId = item.submissionId,
            )
            refreshForPolling()
            _processingPollRevision.value += 1
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _retryErrorsByItemId.value = _retryErrorsByItemId.value +
                (item.id to (error.message ?: "Retry failed."))
        } finally {
            _retryingItemIds.value = _retryingItemIds.value - item.id
        }
    }

    /**
     * Runs a bounded poll while the backend still reports processing records.
     * Only backend responses can advance an item to ready.
     */
    suspend fun pollProcessingItems(
        maximumAttempts: Int = 6,
        intervalMillis: Long = 12_000L,
    ) {
        require(maximumAttempts >= 0) { "maximumAttempts must be non-negative." }
        require(intervalMillis >= 0) { "intervalMillis must be non-negative." }
        if (processingItemIds.isEmpty()) return

        pollingGeneration += 1
        val generation = pollingGeneration
        _isPollingProcessingItems.value = true
        try {
            repeat(maximumAttempts) {
                if (pollingGeneration != generation || processingItemIds.isEmpty()) return
                pollDelay(intervalMillis)
                if (pollingGeneration != generation) return
                refreshForPolling()
            }
        } finally {
            if (pollingGeneration == generation) {
                _isPollingProcessingItems.value = false
            }
        }
    }

    fun stopPolling() {
        pollingGeneration += 1
        _isPollingProcessingItems.value = false
    }

    private suspend fun refreshForPolling() {
        if (_isLoadingInitial.value || _isRefreshing.value || _isLoadingMore.value) return
        try {
            apply(service.fetchHistory(landmarkId, pageSize), replacingItems = true)
            hasLoaded = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Background polling preserves already-visible content and errors.
        }
    }

    private fun apply(response: BusinessMediaHistoryResponse, replacingItems: Boolean) {
        _landmarkLabel.value = response.landmarkLabel
        _nextToken.value = response.nextToken
        if (replacingItems) {
            _items.value = response.items
        } else {
            val existingIds = _items.value.mapTo(mutableSetOf(), BusinessMediaHistoryItem::id)
            _items.value = _items.value + response.items.filter { existingIds.add(it.id) }
        }
    }
}
