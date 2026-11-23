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
    val error = MutableStateFlow<String?>(null)
    /** This phone's live IP on the board's network (null while unbound). */
    val phoneIp = MutableStateFlow<String?>(null)

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    /** True when [network] is the system Wi-Fi the phone already joined
     *  (via Settings) rather than our own specifier request. */
    private var systemMode = false

    /**
     * The board's network among the phone's current networks, if any:
     * a Wi-Fi network where this phone holds a 192.168.4.x address.
     * When the phone is already on LaserGuard-Board through system
     * Wi-Fi, Android never shows the specifier join dialog (proven on
     * the real phone) — so Connect must adopt this network instead.
     */
    private fun findBoardNetwork(): Network? = try {
        cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            val lp = cm.getLinkProperties(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                lp?.linkAddresses?.any {
                    (it.address.hostAddress ?: "").startsWith("192.168.4.")
                } == true
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Adopt the board's network if the phone is already on it (via
     * system Wi-Fi). Returns true when adopted. Called from Connect
     * and automatically whenever the app comes to the foreground, so
     * the card shows Connected by itself — no tap needed.
     */
    fun tryAdopt(): Boolean {
        findBoardNetwork()?.let { n ->
            network = n
            systemMode = true
            bound.value = true
            error.value = null
            phoneIp.value = localIp(n)
            return true
        }
        return false
    }

    /** Ask Android to join the board's AP and pin our traffic to it. */
    fun connect() {
        error.value = null
        // Already on the board's Wi-Fi via system Settings? Adopt that
        // network — no join dialog exists in that state.
        if (tryAdopt()) return
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
                error.value = null
                phoneIp.value = localIp(n)
            }

            override fun onLost(n: Network) {
                if (network == n) {
                    network = null
                    bound.value = false
                    status.value = null
                    phoneIp.value = null
                }
            }

            override fun onUnavailable() {
                // User denied the join dialog (or it timed out): drop
                // the callback so the next Connect tap can retry.
                runCatching { cm.unregisterNetworkCallback(this) }
                if (callback === this) callback = null
                bound.value = false
                error.value = "The board's Wi-Fi join was not approved. Tap Connect and allow it."
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            // Missing permission (e.g. location denied) makes
            // requestNetwork throw SecurityException — never crash.
            callback = null
            error.value = "Could not ask for the board's Wi-Fi: ${e.message ?: "permission missing"}"
        }
    }

    fun disconnect() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        systemMode = false
        bound.value = false
        status.value = null
        phoneIp.value = null
    }

    /** The phone's own IPv4 address on the bound (board) network. */
    private fun localIp(n: Network): String? = try {
        cm.getLinkProperties(n)?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address }
            ?.address?.hostAddress
    } catch (e: Exception) {
        null
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
        network?.let { phoneIp.value = localIp(it) }
        val r = call("/")
        if (r == null) {
            // In system mode, follow the phone if it leaves/rejoins
            // the board's Wi-Fi; a silent board alone never drops us.
            if (systemMode) {
                val n = findBoardNetwork()
                if (n == null) disconnect()
                else if (n != network) { network = n; phoneIp.value = localIp(n) }
            }
            return false
        }
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
