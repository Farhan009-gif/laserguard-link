package com.farhan.laserguardlink

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0D0F)
private val Surface = Color(0xFF15181C)
private val Surface2 = Color(0xFF1E2329)
private val Accent = Color(0xFF7EE2A0)
private val AlarmRed = Color(0xFFFF6B60)
private val Muted = Color(0xFF9AA3AB)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06230F),
    background = Bg,
    surface = Surface,
    surfaceVariant = Surface2,
    error = AlarmRed,
)

class MainActivity : ComponentActivity() {

    private lateinit var link: BoardLink
    private lateinit var log: EventLog
    private lateinit var voiceMaker: VoiceMaker
    private var server: PhoneServer? = null

    private val beamPush = MutableStateFlow<Boolean?>(null)
    private val breachTick = MutableStateFlow(0L)

    private val voiceFile: File get() = File(filesDir, "voice.wav")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Only connect when the Wi-Fi permission was actually granted.
        // Connecting without it makes requestNetwork throw and crash.
        val wifiPerm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION
        if (results[wifiPerm] == false) {
            link.error.value =
                "That permission is needed to join the board's Wi-Fi. Tap Connect and allow it."
        } else {
            link.connect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createBreachChannel()
        log = EventLog(File(filesDir, "events.log"))
        link = BoardLink(this)
        voiceMaker = VoiceMaker(this)
        server = PhoneServer(
            voiceFile = { voiceFile },
            onBreach = {
                log.add("BREACH — alarm triggered on the board")
                breachTick.value = System.currentTimeMillis()
                notifyBreach()
            },
            onBeam = { intact ->
                beamPush.value = intact
                log.add(if (intact) "Beam restored" else "Beam broken")
            },
        ).also { it.start() }

        setContent {
            MaterialTheme(colorScheme = Scheme) {
                LinkScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Show Connected by itself when the phone is on the board's
        // Wi-Fi — no Connect tap needed.
        if (!link.bound.value) link.tryAdopt()
    }

    override fun onDestroy() {
        server?.stop()
        voiceMaker.shutdown()
        super.onDestroy()
    }

    private fun connectTapped() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 33 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        if (needed.isEmpty()) link.connect() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun createBreachChannel() {
        val channel = NotificationChannel(
            "breach", "Breach alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "LaserGuard alarm alerts from the board" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyBreach() {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "breach")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("LaserGuard — BREACH")
            .setContentText("The beam was broken. Alarm is latched on the board.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    @Composable
    private fun LinkScreen() {
        val scope = rememberCoroutineScope()
        val bound by link.bound.collectAsState()
        val status by link.status.collectAsState()
        val linkError by link.error.collectAsState()
        val phoneIp by link.phoneIp.collectAsState()
        val entries by log.entries.collectAsState()
        val beam by beamPush.collectAsState()
        val breachAt by breachTick.collectAsState()

        var message by remember { mutableStateOf<String?>(null) }
        var sentence by remember {
            mutableStateOf(
                getSharedPreferences("lg", MODE_PRIVATE).getString("sentence", "") ?: ""
            )
        }
        var voiceBusy by remember { mutableStateOf(false) }

        // Status polling while bound to the board network (GET / every 3 s).
        LaunchedEffect(bound) {
            while (bound) {
                link.refreshStatus()
                delay(3000)
            }
        }
        // A breach post also refreshes status right away.
        LaunchedEffect(breachAt) {
            if (breachAt > 0) link.refreshStatus()
        }

        val breached = status?.breached == true || (breachAt > 0 && status == null)
        val beamOk = status?.beam ?: beam

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("LaserGuard", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Board link", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }

            // ---- Connection ----
            Card(colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).background(
                            if (bound) Accent else Muted, CircleShape))
                        Text(
                            if (bound) "Connected to ${BoardLink.AP_SSID}"
                            else "Not connected to the board",
                            color = Color.White, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        if (bound) "All board traffic is pinned to the board's Wi-Fi. " +
                            "Mobile data can stay on."
                        else "If this phone is already on the LaserGuard-Board Wi-Fi, " +
                            "Connect uses it straight away. Otherwise Android asks you " +
                            "to approve joining it.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    linkError?.let {
                        Text(it, color = AlarmRed, style = MaterialTheme.typography.bodySmall)
                    }
                    StatusRow("Board", "192.168.4.1 · port 80")
                    StatusRow("This phone",
                        if (phoneIp != null) "$phoneIp · port 8080" else "— · port 8080")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (bound) {
                            OutlinedButton(onClick = { link.disconnect() }) { Text("Disconnect") }
                        } else {
                            Button(onClick = { connectTapped() }) { Text("Connect to board") }
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }) { Text("Wi-Fi settings") }
                    }
                }
            }

            // ---- Alarm banner ----
            if (breached) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1512)),
                    shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ALARM — BEAM BROKEN", color = AlarmRed,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        Text("The alarm is latched. Restore the beam, then reset.",
                            color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Status ----
            Card(colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", color = Color.White, fontWeight = FontWeight.Medium)
                    if (status != null) {
                        StatusRow("Armed", if (status!!.armed) "Yes" else "No")
                        StatusRow("Beam", when (beamOk) {
                            true -> "OK — hitting the sensor"
                            false -> "Broken / not reaching the sensor"
                            null -> "—"
                        })
                        StatusRow("Sound mode", status!!.soundMode)
                        StatusRow("Voice on board", if (status!!.voiceCached) "Cached" else "Not cached — siren covers")
                    } else {
                        Text(
                            if (bound) "Board not answering yet…" else "Connect to see board status.",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Controls ----
            Card(colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Controls", color = Color.White, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = bound, onClick = {
                            scope.launch {
                                link.call("/arm"); link.refreshStatus()
                                log.add("Armed from app")
                            }
                        }) { Text("Arm") }
                        OutlinedButton(enabled = bound, onClick = {
                            scope.launch {
                                link.call("/disarm"); link.refreshStatus()
                                log.add("Disarmed from app")
                            }
                        }) { Text("Disarm") }
                        OutlinedButton(enabled = bound, onClick = {
                            scope.launch {
                                val r = link.call("/reset")
                                message = when {
                                    r == null -> "Board did not answer."
                                    r.code == 409 -> "Board refused the reset — the beam is still broken."
                                    else -> "Reset sent."
                                }
                                link.refreshStatus()
                            }
                        }) { Text("Reset") }
                    }
                    message?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }

                    Spacer(Modifier.height(4.dp))
                    Text("Sound", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("VOICE", "BUZZER", "BOTH").forEach { mode ->
                            FilterChip(
                                selected = status?.soundMode == mode,
                                enabled = bound,
                                onClick = {
                                    scope.launch {
                                        link.call("/sound?m=$mode"); link.refreshStatus()
                                        log.add("Sound mode -> $mode")
                                    }
                                },
                                label = { Text(mode) },
                            )
                        }
                    }
                }
            }

            // ---- Voice ----
            Card(colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Spoken warning", color = Color.White, fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = sentence,
                        onValueChange = { sentence = it },
                        label = { Text("Sentence the board will speak") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = bound && sentence.isNotBlank() && !voiceBusy,
                        onClick = {
                            voiceBusy = true
                            scope.launch {
                                getSharedPreferences("lg", MODE_PRIVATE).edit()
                                    .putString("sentence", sentence).apply()
                                val ok = voiceMaker.synthesize(sentence, voiceFile)
                                if (!ok) {
                                    message = "Voice could not be created on this phone."
                                } else {
                                    val prefs = getSharedPreferences("lg", MODE_PRIVATE)
                                    val v = prefs.getLong("voiceVersion", 0) + 1
                                    prefs.edit().putLong("voiceVersion", v).apply()
                                    val url = URLEncoder.encode(
                                        "http://${phoneIp ?: "192.168.4.2"}:8080/voice.wav", "UTF-8")
                                    val r = link.call("/voice?url=$url&v=$v")
                                    message = if (r?.code == 200)
                                        "Voice sent — the board is downloading it now."
                                    else "The board did not accept the voice."
                                    log.add("Voice sentence sent (v$v)")
                                }
                                voiceBusy = false
                            }
                        },
                    ) { Text(if (voiceBusy) "Working…" else "Send voice to board") }
                }
            }

            // ---- Event log ----
            Card(colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Event log", color = Color.White, fontWeight = FontWeight.Medium)
                    if (entries.isEmpty()) {
                        Text("Nothing yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        entries.forEach { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(fmt.format(Date(e.atMs)), color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(e.text, color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Board expects this phone at 192.168.4.2:8080 — the live address above should match.",
                color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
