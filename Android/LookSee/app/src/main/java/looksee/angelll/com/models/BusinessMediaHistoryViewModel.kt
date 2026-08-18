package looksee.angelll.com.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BusinessMediaHistoryViewModel(
    val landmarkId: String,
    landmarkLabel: String,
    private val pageSize: Int = 25,
    private val service: BusinessMediaHistoryDataSource = BusinessMediaHistoryService(),
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _nextToken = MutableStateFlow<String?>(null)
    val nextToken: StateFlow<String?> = _nextToken.asStateFlow()

    private var hasLoaded = false

    val hasMoreItems: Boolean
        get() = !_nextToken.value.isNullOrBlank()

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
