package net.mikecarr.demultiplixator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

class NetworkUtils {
    companion object {
        private const val TAG = "NetworkUtils"

        fun getNetworkInfo(context: Context): NetworkInfo {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val network = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

            val isWifiConnected = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasInternet = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val wifiInfo = wifiManager.connectionInfo
            var ssid = wifiInfo.ssid?.replace("\"", "") ?: "Unknown"

            // Handle cases where SSID is hidden or permission restricted
            if (ssid == "<unknown ssid>" || ssid == "0x" || ssid.isEmpty() || ssid.isBlank()) {
                // Try to get SSID from network name or use a generic identifier based on IP
                val networkInfo = connectivityManager.activeNetworkInfo
                ssid = networkInfo?.extraInfo?.replace("\"", "") ?: run {
                    val ipAddress = getLocalIpAddress()
                    when {
                        ipAddress.startsWith("192.168.1.") -> "Camera Network (192.168.1.x)"
                        ipAddress.startsWith("192.168.") -> "Local WiFi Network"
                        ipAddress.startsWith("10.0.0.") -> "WiFi Network (10.0.0.x)"
                        ipAddress.startsWith("10.") -> "Local Network"
                        else -> "WiFi Network"
                    }
                }
            }

            val ipAddress = getLocalIpAddress()

            //Log.d(TAG, "Network Info - WiFi: $isWifiConnected, Internet: $hasInternet, SSID: '$ssid', IP: $ipAddress")

            return NetworkInfo(
                isWifiConnected = isWifiConnected,
                hasInternet = hasInternet,
                ssid = ssid,
                ipAddress = ipAddress
            )
        }

        private fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            return address.hostAddress ?: "Unknown"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP address", e)
            }
            return "Unknown"
        }

        fun isOpenIPCNetwork(networkInfo: NetworkInfo): Boolean {
            // Accept any WiFi connection as potentially a camera
            // The real test is whether we receive video packets, not the SSID name
            val isWiFi = networkInfo.isWifiConnected
            val isLocalNetwork = networkInfo.ipAddress.startsWith("192.168.") ||
                    networkInfo.ipAddress.startsWith("10.") ||
                    networkInfo.ipAddress.startsWith("172.")

            // If we're on WiFi with a local IP, assume it could be a camera
            // The app will try to receive video regardless of SSID
            return isWiFi && isLocalNetwork
        }
    }

    data class NetworkInfo(
        val isWifiConnected: Boolean,
        val hasInternet: Boolean,
        val ssid: String,
        val ipAddress: String
    )
}