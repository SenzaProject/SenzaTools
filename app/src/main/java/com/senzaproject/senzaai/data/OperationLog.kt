package com.senzaproject.senzaai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class LogEntry(val time: Long, val mode: String, val action: String, val result: String)

/** Catatan operasi sensitif (root/shell/AI). Disimpan lokal di app, maksimal 300 entri. */
class OperationLog(ctx: Context) {
    private val file = File(ctx.filesDir, "operations.log")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    private fun load(): List<LogEntry> = try {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size >= 4) LogEntry(p[0].toLongOrNull() ?: 0L, unesc(p[1]), unesc(p[2]), unesc(p[3])) else null
        }
    } catch (_: Throwable) {
        emptyList()
    }

    @Synchronized
    fun add(mode: String, action: String, result: String) {
        val list = (_entries.value + LogEntry(System.currentTimeMillis(), mode, action.take(300), result.take(300))).takeLast(300)
        _entries.value = list
        try {
            file.writeText(list.joinToString("\n") { "${it.time}\t${esc(it.mode)}\t${esc(it.action)}\t${esc(it.result)}" })
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file.delete()
    }

    fun asText(): String = _entries.value.joinToString("\n") {
        "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it.time))} [${it.mode}] ${it.action} -> ${it.result}"
    }
}
