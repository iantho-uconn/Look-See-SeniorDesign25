package looksee.angelll.com.detection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android counterpart to NetworkMonitor.swift.
 *
 * Observe [isConnected] from Compose or a ViewModel. The monitor intentionally
 * reports true only when Android has validated that the active network can
 * reach the internet, which prevents queued uploads from starting on a Wi-Fi
 * network that has no usable internet connection.
 */
class NetworkMonitor private constructor(context: Context) : AutoCloseable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isConnected = MutableStateFlow(readCurrentConnection())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateConnection(connectivityManager.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            updateConnection(networkCapabilities)
        }

        override fun onLost(network: Network) {
            updateConnection(readCurrentConnection())
        }

        override fun onUnavailable() {
            updateConnection(false)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun readCurrentConnection(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        return hasUsableInternet(
            connectivityManager.getNetworkCapabilities(activeNetwork),
        )
    }

    private fun updateConnection(capabilities: NetworkCapabilities?) {
        updateConnection(hasUsableInternet(capabilities))
    }

    private fun updateConnection(connected: Boolean) {
        if (_isConnected.value == connected) return

        _isConnected.value = connected
        Log.i(
            TAG,
            "Network status changed: ${if (connected) "Connected" else "Disconnected"}",
        )
    }

    private fun hasUsableInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    override fun close() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        @Volatile
        private var instance: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor =
            instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context).also { instance = it }
            }
    }
}