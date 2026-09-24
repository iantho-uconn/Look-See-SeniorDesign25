package looksee.angelll.com.detection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
                longitude.isFinite() && longitude in -180.0..180.0

    companion object {
        const val MAX_MODEL_LOCATION_ACCURACY_METERS = 5000f
    }
}

sealed interface LookSeeLocationState {
    data object PermissionRequired : LookSeeLocationState
    data object Searching : LookSeeLocationState
    data class Ready(val fix: LookSeeLocationFix) : LookSeeLocationState
    data class Unavailable(val message: String) : LookSeeLocationState
}

class LocationManager(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(applicationContext)

    private var cancellationTokenSource: CancellationTokenSource? = null

    private val _state = MutableStateFlow<LookSeeLocationState>(
        if (hasLocationPermission()) {
            LookSeeLocationState.Searching
        } else {
            LookSeeLocationState.PermissionRequired
        },
    )
    val state: StateFlow<LookSeeLocationState> = _state.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { publish(it) }
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

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasLocationPermission()) {
            _state.value = LookSeeLocationState.PermissionRequired
            return
        }

        stopUpdatesOnly()
        _state.value = LookSeeLocationState.Searching

        cancellationTokenSource = CancellationTokenSource()

        // 1. Instantly use cached location (how Maps/Uber load immediately)
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                publish(location)
            }
        }.addOnFailureListener { error ->
            logger.warning("Failed to get cached location: ${error.message}")
        }

        // 2. Request immediate single current fix
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            cancellationTokenSource!!.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                publish(location)
            }
        }

        // 3. Keep listening with distance threshold = 0 so it works when stationary
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, MIN_UPDATE_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(0f)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        ).addOnFailureListener { error ->
            _state.value = LookSeeLocationState.Unavailable(
                "Failed to request location updates: ${error.localizedMessage}"
            )
        }
    }

    fun stop() {
        stopUpdatesOnly()
    }

    override fun close() {
        stop()
    }

    private fun publish(location: Location) {
        val fix = LookSeeLocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
        )
        if (!fix.isUsable()) {
            logger.info("Ignoring rough/invalid location fix: accuracy=${location.accuracy}m.")
            return
        }
        _state.value = LookSeeLocationState.Ready(fix)
    }

    private fun stopUpdatesOnly() {
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private companion object {
        const val MIN_UPDATE_INTERVAL_MILLIS = 10_000L
        val logger: Logger = Logger.getLogger(LocationManager::class.java.name)
    }
}