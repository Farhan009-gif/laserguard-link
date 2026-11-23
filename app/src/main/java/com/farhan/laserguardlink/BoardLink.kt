package com.farhan.laserguardlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The board link — the fix for why the old app could not connect.
 *
 * The board's access point has no internet, so Android routes ordinary
 * app traffic over mobile data / the default network and plain HTTP
 * calls never reach 192.168.4.1. Here we explicitly request the
 * board's Wi-Fi network (WifiNetworkSpecifier) and open EVERY board
 * connection through that Network object, so the traffic is pinned
 * to the board's AP no matter what the default network is.
 *
 * Protocol (from laserguard-firmware-wifi.ino, Route 1):
 *   board = http://192.168.4.1:80, AP "LaserGuard-Board" / "laserguard"
 *   GET /            -> status JSON
 *   GET /arm /disarm -> {"ok":true}
 *   GET /reset       -> {"ok":true} or 409 {"ok":false,"reason":"beam still broken"}
 *   GET /sound?m=VOICE|BUZZER|BOTH
 *   GET /voice?url=<url>&v=<version>   (board DOWNLOADS the WAV from url)
 */
data class BoardStatus(
    val armed: Boolean,
    val breached: Boolean,
    val beam: Boolean,
    val voiceCached: Boolean,
    val soundMode: String,
)

data class Resp(val code: Int, val body: String)

class BoardLink(context: Context) {

    companion object {
        const val AP_SSID = "LaserGuard-Board"
        const val AP_PASSWORD = "laserguard"
        const val BOARD_BASE = "http://192.168.4.1"
        const val PHONE_BASE = "http://192.168.4.2:8080"
    }

    val bound = MutableStateFlow(false)
    val status = MutableStateFlow<BoardStatus?>(null)

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Ask Android to join the board's AP and pin our traffic to it. */
    fun connect() {
        if (callback != null) return
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(AP_SSID)
            .setWpa2Passphrase(AP_PASSWORD)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                bound.value = true
            }

            override fun onLost(n: Network) {
                if (network == n) {
                    network = null
                    bound.value = false
                    status.value = null
                }
            }

            override fun onUnavailable() {
                // User denied the join dialog (or it timed out): drop
                // the callback so the next Connect tap can retry.
                runCatching { cm.unregisterNetworkCallback(this) }
                if (callback === this) callback = null
                bound.value = false
            }
        }
        callback = cb
        cm.requestNetwork(request, cb)
    }

    fun disconnect() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        bound.value = false
        status.value = null
    }

    /** One GET to the board, through the bound board network only. */
    suspend fun call(path: String): Resp? = withContext(Dispatchers.IO) {
        val n = network ?: return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = n.openConnection(URL(BOARD_BASE + path)) as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            Resp(code, body)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Poll GET / and update [status]. Returns true if the board answered. */
    suspend fun refreshStatus(): Boolean {
        val r = call("/") ?: return false
        if (r.code != 200) return false
        return try {
            val j = JSONObject(r.body)
            status.value = BoardStatus(
                armed = j.getBoolean("armed"),
                breached = j.getBoolean("breached"),
                beam = j.getBoolean("beam"),
                voiceCached = j.getBoolean("voiceCached"),
                soundMode = j.getString("soundMode"),
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
