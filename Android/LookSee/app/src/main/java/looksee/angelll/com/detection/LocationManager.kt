package looksee.angelll.com.detection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.logging.Logger

data class LookSeeLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
) {
    fun isUsable(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0 &&
                accuracyMeters.isFinite() && accuracyMeters > 0f &&
                accuracyMeters <= MAX_MODEL_LOCATION_ACCURACY_METERS

    companion object {
        const val MAX_MODEL_LOCATION_ACCURACY_METERS = 100f
    }
}

sealed interface LookSeeLocationState {
    data object PermissionRequired : LookSeeLocationState
    data object Searching : LookSeeLocationState
    data class Ready(val fix: LookSeeLocationFix) : LookSeeLocationState
    data class Unavailable(val message: String) : LookSeeLocationState
}

/**
 * Small framework-location bridge used by model delivery and the detector.
 *
 * It deliberately avoids a Google Play Services dependency, works with the
 * Android emulator's injected GPS position, and ignores fixes rougher than
 * 100 meters before they can trigger a model refresh.
 */
class LocationManager(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val platformManager =
        applicationContext.getSystemService(Context.LOCATION_SERVICE) as AndroidLocationManager

    private val _state = MutableStateFlow<LookSeeLocationState>(
        if (hasLocationPermission()) {
            LookSeeLocationState.Searching
        } else {
            LookSeeLocationState.PermissionRequired
        },
    )
    val state: StateFlow<LookSeeLocationState> = _state.asStateFlow()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publish(location)
        }

        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) {
            if (enabledProviders().isEmpty()) {
                _state.value = LookSeeLocationState.Unavailable(
                    "Location is disabled on this device.",
                )
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

    /** Call again after the runtime permission result changes. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasLocationPermission()) {
            _state.value = LookSeeLocationState.PermissionRequired
            return
        }

        stopUpdatesOnly()
        _state.value = LookSeeLocationState.Searching

        val providers = enabledProviders()
        if (providers.isEmpty()) {
            _state.value = LookSeeLocationState.Unavailable(
                "Location is disabled. Enable it or set an emulator location.",
            )
            return
        }

        providers.forEach { provider ->
            runCatching {
                platformManager.getLastKnownLocation(provider)?.let(::publish)
                platformManager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_INTERVAL_MILLIS,
                    MIN_UPDATE_DISTANCE_METERS,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { error ->
                logger.warning("Unable to start $provider location updates: ${error.message}")
            }
        }
    }

    fun stop() {
        stopUpdatesOnly()
    }

    override fun close() {
        stop()
    }

    private fun enabledProviders(): List<String> =
        listOf(
            AndroidLocationManager.GPS_PROVIDER,
            AndroidLocationManager.NETWORK_PROVIDER,
        ).filter { provider ->
            runCatching { platformManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

    private fun publish(location: Location) {
        val fix = LookSeeLocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
        )
        if (!fix.isUsable()) {
            logger.info(
                "Ignoring rough/invalid location fix: accuracy=${location.accuracy}m.",
            )
            return
        }
        _state.value = LookSeeLocationState.Ready(fix)
    }

    private fun stopUpdatesOnly() {
        runCatching { platformManager.removeUpdates(listener) }
    }

    private companion object {
        const val MIN_UPDATE_INTERVAL_MILLIS = 15_000L
        const val MIN_UPDATE_DISTANCE_METERS = 15f
        val logger: Logger = Logger.getLogger(LocationManager::class.java.name)
    }
}
