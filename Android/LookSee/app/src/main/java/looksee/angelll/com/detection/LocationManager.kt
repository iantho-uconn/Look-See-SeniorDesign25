package looksee.angelll.com.detection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocationAuthorizationStatus {
    NOT_DETERMINED,
    AUTHORIZED_COARSE,
    AUTHORIZED_FINE,
    DENIED,
}

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double,
    val capturedAtMillis: Long,
)

/**
 * Android counterpart to LocationManager.swift.
 *
 * Android permission dialogs must be launched by an Activity or Composable.
 * After that result is received, call [onPermissionResult]. Valid fixes are
 * forwarded through [onUsableLocation] so ModelAutoRefreshService can be wired
 * in without coupling this platform service to the model-delivery layer.
 */
class LocationManager(
    context: Context,
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext),
) : AutoCloseable {
    private val appContext = context.applicationContext

    private val _latitude = MutableStateFlow<Double?>(null)
    val latitude: StateFlow<Double?> = _latitude.asStateFlow()

    private val _longitude = MutableStateFlow<Double?>(null)
    val longitude: StateFlow<Double?> = _longitude.asStateFlow()

    private val _horizontalAccuracy = MutableStateFlow<Double?>(null)
    val horizontalAccuracy: StateFlow<Double?> = _horizontalAccuracy.asStateFlow()

    private val _authorizationStatus =
        MutableStateFlow(readAuthorizationStatus(permissionResultKnown = false))
    val authorizationStatus: StateFlow<LocationAuthorizationStatus> =
        _authorizationStatus.asStateFlow()

    var onUsableLocation: ((LocationSnapshot) -> Unit)? = null

    val isAuthorized: Boolean
        get() = when (_authorizationStatus.value) {
            LocationAuthorizationStatus.AUTHORIZED_COARSE,
            LocationAuthorizationStatus.AUTHORIZED_FINE,
            -> true

            LocationAuthorizationStatus.NOT_DETERMINED,
            LocationAuthorizationStatus.DENIED,
            -> false
        }

    private var isUpdatingLocation = false

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_INTERVAL_MILLIS,
    )
        .setMinUpdateIntervalMillis(MIN_LOCATION_INTERVAL_MILLIS)
        .setMinUpdateDistanceMeters(MINIMUM_MOVEMENT_METERS)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::handleLocation)
        }
    }

    fun requestPermissionIfNeeded(): Boolean {
        refreshAuthorizationStatus(permissionResultKnown = false)
        return if (isAuthorized) {
            start()
            false
        } else {
            true
        }
    }

    fun onPermissionResult() {
        refreshAuthorizationStatus(permissionResultKnown = true)
        if (isAuthorized) start() else stop()
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        refreshAuthorizationStatus(permissionResultKnown = false)

        if (!isAuthorized) {
            Log.w(TAG, "Location updates not started because permission is missing")
            return false
        }

        if (isUpdatingLocation) return true

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper(),
        )
        isUpdatingLocation = true
        return true
    }

    fun stop() {
        if (!isUpdatingLocation) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isUpdatingLocation = false
    }

    private fun handleLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null

        _latitude.value = location.latitude
        _longitude.value = location.longitude
        _horizontalAccuracy.value = accuracy

        if (accuracy == null || accuracy <= 0.0) return

        if (accuracy > MAXIMUM_ACCEPTED_ACCURACY_METERS) {
            Log.w(TAG, "Ignoring rough location fix: %.1fm accuracy".format(accuracy))
            return
        }

        onUsableLocation?.invoke(
            LocationSnapshot(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracy = accuracy,
                capturedAtMillis = location.time,
            ),
        )
    }

    private fun refreshAuthorizationStatus(permissionResultKnown: Boolean) {
        _authorizationStatus.value = readAuthorizationStatus(permissionResultKnown)
    }

    private fun readAuthorizationStatus(
        permissionResultKnown: Boolean,
    ): LocationAuthorizationStatus {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) return LocationAuthorizationStatus.AUTHORIZED_FINE

        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (coarseGranted) return LocationAuthorizationStatus.AUTHORIZED_COARSE

        return if (permissionResultKnown) {
            LocationAuthorizationStatus.DENIED
        } else {
            LocationAuthorizationStatus.NOT_DETERMINED
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val TAG = "LocationManager"
        private const val LOCATION_INTERVAL_MILLIS = 10_000L
        private const val MIN_LOCATION_INTERVAL_MILLIS = 5_000L
        private const val MINIMUM_MOVEMENT_METERS = 15f
        private const val MAXIMUM_ACCEPTED_ACCURACY_METERS = 100.0
    }
}
