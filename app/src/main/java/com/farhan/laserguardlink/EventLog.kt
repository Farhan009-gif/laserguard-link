package com.farhan.laserguardlink

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** In-memory event log, mirrored to a small append-only file. */
class EventLog(private val file: File) {

    data class Entry(val atMs: Long, val text: String)

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    init {
        entries.value = runCatching {
            if (!file.exists()) emptyList()
            else file.readLines().mapNotNull { line ->
                val sep = line.indexOf('|')
                if (sep < 0) null
                else Entry(line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null,
                           line.substring(sep + 1))
            }.takeLast(100).reversed()
        }.getOrDefault(emptyList())
    }

    fun add(text: String) {
        val e = Entry(System.currentTimeMillis(), text)
        entries.value = (listOf(e) + entries.value).take(100)
        runCatching { file.appendText("${e.atMs}|$text\n") }
    }
}
