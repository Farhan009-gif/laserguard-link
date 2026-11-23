# LaserGuard Link

A minimal companion app for the LaserGuard v2 board firmware
(`laserguard-firmware-wifi.ino`, NodeMCU LoLin V3 / ESP8266).
Package: `com.farhan.laserguardlink`. Kotlin + Jetpack Compose,
single module, no third-party HTTP libraries (plain
`HttpURLConnection` + a tiny raw-socket server).

## Why this app exists — the connection fix

The board runs its own access point, which has **no internet**.
Android routes an ordinary app's traffic over mobile data / the
default network in that situation, so plain HTTP calls to the board
never arrive — that is why the old app could not connect.

This app pins its traffic to the board's network:

- `ConnectivityManager.requestNetwork` with a
  `WifiNetworkSpecifier` for SSID `LaserGuard-Board`
  (password `laserguard`) and `NET_CAPABILITY_INTERNET` removed.
  Android shows a one-time approval dialog to join the AP.
- Every board call is opened through that `Network` object
  (`network.openConnection(...)`), so it reaches 192.168.4.1
  regardless of the default network or mobile data being on.
- The UI shows the bound state ("Connected to LaserGuard-Board")
  before any control is enabled.

## Protocol — implemented exactly as the firmware defines it

Board: `http://192.168.4.1:80` on its own AP
(`LaserGuard-Board` / `laserguard`). All endpoints are GET and
answer `application/json`.

| Route | Parameters | Response |
|---|---|---|
| `GET /` | — | Status: `{"armed":bool,"breached":bool,"beam":bool,"voiceCached":bool,"soundMode":"VOICE"\|"BUZZER"\|"BOTH"}` |
| `GET /arm` | — | `{"ok":true}` |
| `GET /disarm` | — | `{"ok":true}` (also silences a latched alarm) |
| `GET /reset` | — | `{"ok":true}`, or **409** `{"ok":false,"reason":"beam still broken"}` while the beam is broken |
| `GET /sound` | `m=VOICE\|BUZZER\|BOTH` | `{"ok":true,"soundMode":"…"}`; 400 if `m` missing. Applies live, even mid-alarm |
| `GET /voice` | `url=<url-encoded>`, `v=<version>` | `{"ok":true}`; 400 if `url` missing. The board **downloads** the WAV from `url` and caches it in LittleFS, keyed by (url, version) — bump `v` whenever the sentence changes |
| `GET /config` | `ssid`, `pass`, `fb`, `fbs` | Runtime config: home-Wi-Fi (STA) credentials + Firebase URL/secret, saved on the board. **Not used by this app** (Route 2 / cloud is inert without it) |

Board → phone posts (the phone must listen on port **8080**):

| Route | Body | When |
|---|---|---|
| `POST /breach` | `{"beam":false}` | Once per alarm episode |
| `POST /status` | `{"beam":true\|false}` | On beam state change, on reset, on disarm-from-alarm, at boot |

The app's server on 8080 also serves:

| Route | Response |
|---|---|
| `GET /voice.wav` | The current TTS-generated WAV (`audio/x-wav`) — this is the `url` handed to the board in the `/voice` call: `http://192.168.4.2:8080/voice.wav` |

### The phone-IP question (read this before changing firmware)

The firmware **hardcodes** the phone's address:
`PHONE_IP = "192.168.4.2"`, `PHONE_PORT = 8080` (compile-time
constants in the `.ino`). **`/config` does NOT let the app register
a different phone IP** — it only sets STA Wi-Fi and Firebase.
This works because the board's DHCP hands the first (and only)
client on its AP exactly `192.168.4.2`. If a second device ever
joins the AP first, board posts would go to the wrong device;
changing that needs a firmware change, not an app change.

## App behaviour

- **Status**: polls `GET /` every 3 s while bound; also refreshes
  immediately on a `/breach` post.
- **Controls**: Arm, Disarm, Reset (a 409 shows "beam still
  broken"), sound mode VOICE / BUZZER / BOTH.
- **Voice**: the sentence is synthesised on the phone with Android
  TTS to `voice.wav`, a version counter is bumped, and the board is
  told via `/voice?url=…&v=…`. If no voice is cached, the board's
  embedded siren covers (firmware behaviour).
- **Breach**: full-screen-intent notification + in-app alarm banner
  + event log entry.
- **Event log**: in-memory, newest first, mirrored to
  `files/events.log` (last 100 kept).

## Building

Open the project in Android Studio (it generates the Gradle wrapper
on first sync), or from a machine with Gradle 8.9+:
`gradle wrapper --gradle-version 8.9` once, then `./gradlew assembleDebug`.
minSdk 29 (the `WifiNetworkSpecifier` requirement), targetSdk 35.

## Known limits

- The port-8080 listener runs while the app process is alive; it is
  not a foreground service. Keep the app open during a demo.
- While joined to the board's AP the phone has no internet on that
  network (Android keeps the default network for other apps; only
  this app's board traffic is pinned).
