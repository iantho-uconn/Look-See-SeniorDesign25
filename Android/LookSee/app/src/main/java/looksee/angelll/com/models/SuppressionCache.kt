package looksee.angelll.com.models

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SuppressionCache private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("LookSeeSuppressionPrefs", Context.MODE_PRIVATE)

    private var inactiveIds = mutableSetOf<String>()
    private var hiddenMapIds = mutableSetOf<String>()
    private var locallyModifiedIds = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        inactiveIds.addAll(prefs.getStringSet(INACTIVE_KEY, emptySet()) ?: emptySet())
        hiddenMapIds.addAll(prefs.getStringSet(HIDDEN_KEY, emptySet()) ?: emptySet())
        locallyModifiedIds.addAll(prefs.getStringSet(MODIFIED_KEY, emptySet()) ?: emptySet())
    }

    @Synchronized
    fun isInactive(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        return inactiveIds.contains(trimmed)
    }

    @Synchronized
    fun isHidden(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        return hiddenMapIds.contains(trimmed)
    }

    @Synchronized
    fun isLocallyModified(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        return locallyModifiedIds.contains(trimmed)
    }

    @Synchronized
    fun update(inactive: Set<String>, hidden: Set<String>, modified: Set<String>) {
        inactiveIds.clear()
        inactiveIds.addAll(inactive)
        
        hiddenMapIds.clear()
        hiddenMapIds.addAll(hidden)
        
        locallyModifiedIds.clear()
        locallyModifiedIds.addAll(modified)

        val inactiveCopy = inactiveIds.toSet()
        val hiddenCopy = hiddenMapIds.toSet()
        val modifiedCopy = locallyModifiedIds.toSet()

        scope.launch {
            prefs.edit().apply {
                putStringSet(INACTIVE_KEY, inactiveCopy)
                putStringSet(HIDDEN_KEY, hiddenCopy)
                putStringSet(MODIFIED_KEY, modifiedCopy)
                apply()
            }
        }
    }

    @Synchronized
    fun getInitialInactive(): Set<String> = inactiveIds.toSet()

    @Synchronized
    fun getInitialHidden(): Set<String> = hiddenMapIds.toSet()

    @Synchronized
    fun getInitialModified(): Set<String> = locallyModifiedIds.toSet()

    companion object {
        private const val INACTIVE_KEY = "looksee_inactive_landmark_ids"
        private const val HIDDEN_KEY = "looksee_hidden_map_landmark_ids"
        private const val MODIFIED_KEY = "looksee_locally_modified_landmark_ids"

        @Volatile
        private var INSTANCE: SuppressionCache? = null

        fun shared(context: Context): SuppressionCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SuppressionCache(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
