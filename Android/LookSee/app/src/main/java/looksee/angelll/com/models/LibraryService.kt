package looksee.angelll.com.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryLandmark(
    @SerializedName("landmarkId")
    val id: String = "",
    val label: String = "",
    @SerializedName("short_description")
    val shortDescription: String = "",
    val clusterId: Int = 0,
)

data class LibraryLandmarksResponse(
    val items: List<LibraryLandmark> = emptyList(),
)

class LibraryServiceException(
    val statusCode: Int,
    val responseBody: String,
) : Exception("Library API error $statusCode: $responseBody")

/** Keeps the library synchronized with ModelSelector's active cluster. */
class LibraryService internal constructor(
    activeClusterIds: StateFlow<String?>,
    private val httpClient: BusinessHttpClient,
    private val gson: Gson,
    dispatcher: CoroutineDispatcher,
    observeActiveCluster: Boolean = true,
) : AutoCloseable {
    constructor(context: Context) : this(
        activeClusterIds = ModelSelector.shared(context.applicationContext).activeClusterId,
        httpClient = UrlConnectionBusinessHttpClient(),
        gson = Gson(),
        dispatcher = Dispatchers.Default,
    )

    internal constructor(
        activeClusterIds: StateFlow<String?>,
        httpClient: BusinessHttpClient,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        observeActiveCluster: Boolean = true,
    ) : this(
        activeClusterIds,
        httpClient,
        Gson(),
        dispatcher,
        observeActiveCluster,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _items = MutableStateFlow<List<LibraryLandmark>>(emptyList())
    val items: StateFlow<List<LibraryLandmark>> = _items.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Filtered landmarks based on the current [searchText].
     * Matches the filtering logic in iOS Library.swift.
     */
    val filteredItems: StateFlow<List<LibraryLandmark>> = combine(
        _items,
        _searchText,
    ) { items, query ->
        if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.shortDescription.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = serviceScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    init {
        if (observeActiveCluster) {
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                activeClusterIds.collectLatest { activeClusterId ->
                    if (activeClusterId == null) {
                        _items.value = emptyList()
                        _errorMessage.value = null
                        _isLoading.value = false
                    } else {
                        val numericClusterId = activeClusterId.toIntOrNull()
                        if (numericClusterId == null) {
                            _items.value = emptyList()
                            _errorMessage.value =
                                "Invalid active cluster ID: $activeClusterId"
                        } else {
                            fetchLandmarks(numericClusterId)
                        }
                    }
                }
            }
        }
    }

    suspend fun fetchLandmarks(clusterId: Int) {
        _isLoading.value = true
        _errorMessage.value = null
        _items.value = emptyList()
        _searchText.value = ""
        try {
            val response = httpClient.execute(
                BusinessHttpRequest(
                    method = "GET",
                    url = urlWithQuery(
                        "$LOOKSEE_API_BASE_URL/landmarks/by-cluster",
                        mapOf("cluster_id" to clusterId.toString()),
                    ),
                ),
            )
            if (response.statusCode !in 200..299) {
                throw LibraryServiceException(response.statusCode, response.bodyText)
            }
            val decoded = gson.fromJson(
                response.bodyText,
                LibraryLandmarksResponse::class.java,
            ) ?: throw IllegalStateException("The library response was empty.")
            _items.value = decoded.items
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _errorMessage.value = error.message ?: "Unable to load the landmark library."
        } finally {
            _isLoading.value = false
        }
    }

    fun setSearchText(query: String) {
        _searchText.value = query
    }

    override fun close() {
        serviceScope.cancel()
    }

    companion object {
        @Volatile
        private var sharedInstance: LibraryService? = null

        fun shared(context: Context): LibraryService =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: LibraryService(context.applicationContext)
                    .also { sharedInstance = it }
            }
    }
}
