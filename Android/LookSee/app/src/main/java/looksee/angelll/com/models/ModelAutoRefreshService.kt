package looksee.angelll.com.models

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.logging.Logger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ModelRefreshLocation(
    val latitude: Double,
    val longitude: Double,
) {
    fun isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}

/**
 * Periodically checks for model releases after meaningful user movement.
 *
 * Every usable location is forwarded to [ModelSelector] immediately, allowing
 * fast switching among releases already on disk. Backend refreshes are kept
 * separate and run only after the polling interval, cooldown, and movement
 * rules allow them.
 */
class ModelAutoRefreshService internal constructor(
    private val refreshModels: suspend (latitude: Double, longitude: Double) -> Boolean,
    private val updateModelSelection: (latitude: Double, longitude: Double) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val minimumMovementBeforeRefreshMeters: Double =
        DEFAULT_MINIMUM_MOVEMENT_BEFORE_REFRESH_METERS,
    private val minimumTimeBetweenRefreshesMillis: Long =
        DEFAULT_MINIMUM_TIME_BETWEEN_REFRESHES_MILLIS,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val waitForNextPoll: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    constructor(
        modelService: ModelService,
        modelSelector: ModelSelector,
    ) : this(
        refreshModels = modelService::refreshModelsSilentlyIfNeeded,
        updateModelSelection = modelSelector::updateUserLocation,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val refreshMutex = Mutex()
    private val lifecycleLock = Any()
    private val locationLock = Any()

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private val _lastRefreshAtMillis = MutableStateFlow<Long?>(null)
    val lastRefreshAtMillis: StateFlow<Long?> = _lastRefreshAtMillis.asStateFlow()

    private val _lastRefreshLocation = MutableStateFlow<ModelRefreshLocation?>(null)
    val lastRefreshLocation: StateFlow<ModelRefreshLocation?> =
        _lastRefreshLocation.asStateFlow()

    private val _lastRefreshReason = MutableStateFlow(NOT_STARTED_REASON)
    val lastRefreshReason: StateFlow<String> = _lastRefreshReason.asStateFlow()

    private val _lastRefreshChangedModels = MutableStateFlow(false)
    val lastRefreshChangedModels: StateFlow<Boolean> =
        _lastRefreshChangedModels.asStateFlow()

    private var latestLocation: ModelRefreshLocation? = null
    private var pollingJob: Job? = null
    private var pollingGeneration = 0L

    init {
        require(pollIntervalMillis > 0L) { "pollIntervalMillis must be positive." }
        require(
            minimumMovementBeforeRefreshMeters.isFinite() &&
                minimumMovementBeforeRefreshMeters >= 0.0,
        ) {
            "minimumMovementBeforeRefreshMeters must be finite and non-negative."
        }
        require(minimumTimeBetweenRefreshesMillis >= 0L) {
            "minimumTimeBetweenRefreshesMillis must be non-negative."
        }
    }

    /** Starts one polling loop. The first backend check occurs after a full interval. */
    fun start() {
        synchronized(lifecycleLock) {
            if (pollingJob?.isActive == true) {
                logger.info("Model auto-refresh already running.")
                return
            }

            pollingGeneration += 1L
            val generation = pollingGeneration
            _isPolling.value = true
            _lastRefreshReason.value = STARTED_REASON

            pollingJob = serviceScope.launch {
                logger.info(
                    "Model auto-refresh started; first backend poll will occur " +
                        "in ${pollIntervalMillis / 60_000.0} minutes.",
                )

                try {
                    while (isActive) {
                        // The normal launch flow performs the first model load.
                        // Sleeping first avoids racing that load with polling.
                        waitForNextPoll(pollIntervalMillis)
                        if (!isActive) break
                        performRefreshIfNeeded(force = false)
                    }
                } finally {
                    synchronized(lifecycleLock) {
                        if (pollingGeneration == generation) {
                            pollingJob = null
                            _isPolling.value = false
                            _lastRefreshReason.value = STOPPED_POLLING_REASON
                        }
                    }
                    logger.info("Model auto-refresh polling loop stopped.")
                }
            }
        }
    }

    fun stop() {
        val jobToCancel = synchronized(lifecycleLock) {
            pollingGeneration += 1L
            val current = pollingJob
            pollingJob = null
            _isPolling.value = false
            _lastRefreshReason.value = STOPPED_MANUALLY_REASON
            current
        }
        jobToCancel?.cancel()
        logger.info("Model auto-refresh manually stopped.")
    }

    /**
     * Stores the latest usable WGS84 location and immediately re-runs local
     * release selection. Invalid coordinates are ignored.
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        val location = ModelRefreshLocation(latitude = latitude, longitude = longitude)
        if (!location.isValid()) {
            logger.warning(
                "Ignoring invalid model-refresh location " +
                    "latitude=$latitude, longitude=$longitude.",
            )
            return
        }

        synchronized(locationLock) {
            latestLocation = location
        }
        updateModelSelection(latitude, longitude)
    }

    /** Manual refresh bypasses the cooldown and movement gates, but still needs a location. */
    suspend fun refreshNow() {
        performRefreshIfNeeded(force = true)
    }

    /** Internal visibility keeps deterministic local tests independent of real timers. */
    internal suspend fun performRefreshIfNeeded(force: Boolean) {
        refreshMutex.withLock {
            val location = synchronized(locationLock) { latestLocation }
            if (location == null) {
                _lastRefreshReason.value = SKIPPED_NO_LOCATION_REASON
                logger.warning("Model auto-refresh skipped: no location available yet.")
                return
            }

            val now = currentTimeMillis()
            if (!force) {
                val lastRefresh = _lastRefreshAtMillis.value
                if (lastRefresh != null) {
                    val elapsed = now - lastRefresh
                    if (elapsed in 0L until minimumTimeBetweenRefreshesMillis) {
                        _lastRefreshReason.value = SKIPPED_COOLDOWN_REASON
                        logger.info("Model auto-refresh skipped: cooldown active.")
                        return
                    }
                }

                val previousLocation = _lastRefreshLocation.value
                if (previousLocation != null) {
                    val movement = distanceMeters(previousLocation, location)
                    if (movement < minimumMovementBeforeRefreshMeters) {
                        val formattedMovement = String.format(Locale.US, "%.1f", movement)
                        _lastRefreshReason.value =
                            "Skipped: moved only ${formattedMovement}m"
                        logger.info(
                            "Model auto-refresh skipped: moved only ${formattedMovement}m.",
                        )
                        return
                    }
                }
            }

            _lastRefreshAtMillis.value = now
            _lastRefreshLocation.value = location
            _lastRefreshReason.value =
                if (force) MANUAL_REFRESH_REASON else POLLING_REFRESH_REASON

            val changed = refreshModels(location.latitude, location.longitude)
            _lastRefreshChangedModels.value = changed

            logger.info(
                if (changed) {
                    "Model auto-refresh updated the loaded model set."
                } else {
                    "Model auto-refresh finished with no model-set change."
                },
            )
        }
    }

    /** Stops polling and releases this instance's coroutine scope. */
    override fun close() {
        stop()
        serviceScope.cancel()
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 10L * 60L * 1_000L
        const val DEFAULT_MINIMUM_MOVEMENT_BEFORE_REFRESH_METERS = 50.0
        const val DEFAULT_MINIMUM_TIME_BETWEEN_REFRESHES_MILLIS = 60L * 1_000L

        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val NOT_STARTED_REASON = "Not started"
        private const val STARTED_REASON = "Started polling"
        private const val STOPPED_POLLING_REASON = "Stopped polling"
        private const val STOPPED_MANUALLY_REASON = "Stopped manually"
        private const val SKIPPED_NO_LOCATION_REASON =
            "Skipped: no location available yet"
        private const val SKIPPED_COOLDOWN_REASON = "Skipped: cooldown active"
        private const val MANUAL_REFRESH_REASON = "Manual refresh"
        private const val POLLING_REFRESH_REASON = "Polling refresh"
        private val logger = Logger.getLogger(ModelAutoRefreshService::class.java.name)

        @Volatile
        private var sharedInstance: ModelAutoRefreshService? = null

        /** Android's application-context-initialized equivalent of Swift's `shared`. */
        fun shared(context: Context): ModelAutoRefreshService =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ModelAutoRefreshService(
                    modelService = ModelService.shared(context.applicationContext),
                    modelSelector = ModelSelector.shared(context.applicationContext),
                ).also { sharedInstance = it }
            }

        private fun distanceMeters(
            from: ModelRefreshLocation,
            to: ModelRefreshLocation,
        ): Double {
            val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
            val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
            val fromLatitude = Math.toRadians(from.latitude)
            val toLatitude = Math.toRadians(to.latitude)

            val haversine =
                sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
                    cos(fromLatitude) * cos(toLatitude) *
                    sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
            val bounded = haversine.coerceIn(0.0, 1.0)
            val angularDistance = 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
            return EARTH_RADIUS_METERS * angularDistance
        }
    }
}
