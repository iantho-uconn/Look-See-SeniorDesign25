package looksee.angelll.com.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface BusinessLandmarkCache {
    fun read(): List<BusinessLandmark>
    fun write(items: List<BusinessLandmark>)
}

class SharedPreferencesBusinessLandmarkCache(
    context: Context,
    private val gson: Gson = Gson(),
) : BusinessLandmarkCache {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): List<BusinessLandmark> {
        val json = preferences.getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<BusinessLandmark>>() {}.type
            gson.fromJson<List<BusinessLandmark>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun write(items: List<BusinessLandmark>) {
        preferences.edit().putString(CACHE_KEY, gson.toJson(items)).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "looksee_business_landmarks"
        const val CACHE_KEY = "BusinessLandmarksCache"
    }
}

internal class InMemoryBusinessLandmarkCache(
    initial: List<BusinessLandmark> = emptyList(),
) : BusinessLandmarkCache {
    private var items = initial

    override fun read(): List<BusinessLandmark> = items

    override fun write(items: List<BusinessLandmark>) {
        this.items = items
    }
}

class BusinessLandmarksViewModel(
    private val service: BusinessLandmarkDataSource = BusinessLandmarkService(),
    private val cache: BusinessLandmarkCache = InMemoryBusinessLandmarkCache(),
) {
    private val _landmarks = MutableStateFlow(sorted(cache.read()))
    val landmarks: StateFlow<List<BusinessLandmark>> = _landmarks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun loadLandmarks() {
        if (_isLoading.value) return
        _isLoading.value = true
        _errorMessage.value = null
        try {
            replaceAll(service.fetchBusinessLandmarks().items)
        } catch (error: Throwable) {
            _errorMessage.value = error.message ?: "Failed to load business landmarks."
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun refresh() = loadLandmarks()

    fun clearError() {
        _errorMessage.value = null
    }

    fun replaceLandmark(updatedLandmark: BusinessLandmark) {
        val current = _landmarks.value.toMutableList()
        val index = current.indexOfFirst { it.landmarkId == updatedLandmark.landmarkId }
        if (index < 0) return
        current[index] = updatedLandmark
        replaceAll(current)
    }

    fun replaceLandmarks(updatedLandmarks: List<BusinessLandmark>) {
        if (updatedLandmarks.isEmpty()) return
        val updatedById = updatedLandmarks.associateBy(BusinessLandmark::landmarkId)
        replaceAll(_landmarks.value.map { updatedById[it.landmarkId] ?: it })
    }

    fun removeLandmark(landmarkId: String) {
        replaceAll(_landmarks.value.filterNot { it.landmarkId == landmarkId })
    }

    fun removeLandmarks(landmarkIds: Set<String>) {
        if (landmarkIds.isEmpty()) return
        replaceAll(_landmarks.value.filterNot { it.landmarkId in landmarkIds })
    }

    private fun replaceAll(items: List<BusinessLandmark>) {
        val sorted = sorted(items)
        _landmarks.value = sorted
        cache.write(sorted)
    }

    private companion object {
        fun sorted(items: List<BusinessLandmark>): List<BusinessLandmark> =
            items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
