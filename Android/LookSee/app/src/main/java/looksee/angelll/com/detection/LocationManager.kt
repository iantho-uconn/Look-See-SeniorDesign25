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
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LookSeeLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
) {
    fun isUsable(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0
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
        Log.d("LookSee_Debug_Location", "Starting LocationManager...")
        if (!hasLocationPermission()) {
            Log.e("LookSee_Debug_Location", "No permission!")
            _state.value = LookSeeLocationState.PermissionRequired
            return
        }

        stopUpdatesOnly()
        _state.value = LookSeeLocationState.Searching
        cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.d("LookSee_Debug_Location", "Cached location found: acc=${location.accuracy}m")
                publish(location)
            } else {
                Log.d("LookSee_Debug_Location", "No cached location available.")
            }
        }.addOnFailureListener { error ->
            Log.e("LookSee_Debug_Location", "Failed to get cached location", error)
        }

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            cancellationTokenSource!!.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.d("LookSee_Debug_Location", "Current fix fetched: acc=${location.accuracy}m")
                publish(location)
            }
        }

        // Match iOS distance filter 15 meters
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(15f)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        ).addOnFailureListener { error ->
            Log.e("LookSee_Debug_Location", "Update request failed", error)
            _state.value = LookSeeLocationState.Unavailable(error.localizedMessage ?: "Unknown error")
        }
    }

    fun stop() {
        Log.d("LookSee_Debug_Location", "Stopping LocationManager...")
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
            Log.w("LookSee_Debug_Location", "Location not usable (inf/nan)")
            return
        }

        Log.d("LookSee_Debug_Location", "Publishing fix: lat=${fix.latitude}, lon=${fix.longitude}, acc=${fix.accuracyMeters}m")

        // Android emulators are notoriously bad at location accuracy.
        // We will push the state to the UI regardless so it doesn't say "Requesting...",
        // but log a warning if it's over the 100m iOS limit.
        if (fix.accuracyMeters > 100f) {
            Log.w("LookSee_Debug_Location", "⚠️ Rough location fix! >100m accuracy. Map might be inaccurate.")
        }

        _state.value = LookSeeLocationState.Ready(fix)
    }

    private fun stopUpdatesOnly() {
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}