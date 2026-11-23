package com.farhan.laserguardlink

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The phone side of the link, on port 8080 — the address the firmware
 * has hardcoded for us (PHONE_IP 192.168.4.2, PHONE_PORT 8080 in the
 * .ino; there is no route to change it, and the board's DHCP gives
 * the first/only client exactly .2).
 *
 * Receives the board's posts:
 *   POST /breach  {"beam":false}   — once per alarm episode
 *   POST /status  {"beam":true|false}
 * And serves the voice file the board downloads after a /voice call:
 *   GET  /voice.wav
 *
 * A deliberately tiny raw-socket HTTP server — no library needed for
 * three routes.
 */
class PhoneServer(
    private val voiceFile: () -> File?,
    private val onBreach: () -> Unit,
    private val onBeam: (Boolean) -> Unit,
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "lg-phone-server") {
            try {
                val srv = ServerSocket(8080)
                server = srv
                while (running) {
                    val socket = try {
                        srv.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) { handle(socket) }
                }
            } catch (e: Exception) {
                // Port busy or socket closed — the app keeps running;
                // board posts simply won't arrive until a restart.
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: java.net.Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().buffered()
            val reader = input.bufferedReader(Charsets.US_ASCII)
            val requestLine = reader.readLine() ?: return closeQuietly(socket)
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "POST" && path == "/breach" -> {
                    respond(socket, 200, "application/json", "{\"ok\":true}".toByteArray())
                    onBreach()
                }
                method == "POST" && path == "/status" -> {
                    respond(socket, 200, "application/json", "{\"ok\":true}".toByteArray())
                    // Body is {"beam":true} or {"beam":false} per the firmware.
                    onBeam(body.contains("\"beam\":true"))
                }
                method == "GET" && path == "/voice.wav" -> {
                    val f = voiceFile()
                    if (f != null && f.exists() && f.length() > 0) {
                        respond(socket, 200, "audio/x-wav", f.readBytes())
                    } else {
                        respond(socket, 404, "text/plain", "no voice".toByteArray())
                    }
                }
                else -> respond(socket, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: Exception) {
            // A half-open board connection must never kill the server.
        } finally {
            closeQuietly(socket)
        }
    }

    private fun respond(socket: java.net.Socket, code: Int, type: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "Not Found"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun closeQuietly(socket: java.net.Socket) {
        runCatching { socket.close() }
    }
}
