package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.SystemShell
import com.senzaproject.senzatools.utils.StreamResult

/** Shell palsu di memori. Meniru [com.senzaproject.senzatools.utils.Sys.stream]: penyaring [keep] dan [tail] diterapkan sama persis. */
class FakeShell(override var rootVerified: Boolean = true) : SystemShell {
    class Out(val lines: List<String>, val exit: Int = 0, val stderr: String = "", val complete: Boolean = true, val timedOut: Boolean = false)

    val files = mutableMapOf<String, String?>()
    val rootCmds = mutableMapOf<String, () -> Out>()
    val appCmds = mutableMapOf<String, () -> Out>()
    val calls = mutableListOf<String>()

    private fun run(o: Out, tail: Int, keep: (String) -> Boolean): StreamResult {
        var total = 0L
        val ring = ArrayDeque<String>()
        for (l in o.lines) if (keep(l)) { total++; ring.addLast(l); if (ring.size > tail) ring.removeFirst() }
        return StreamResult(o.exit, ring.toList(), total, o.stderr, o.timedOut, o.complete)
    }

    override fun readFile(path: String): String? = files[path]

    override fun app(command: String, timeoutMs: Long): StreamResult {
        calls += "app:$command"
        val o = appCmds[command]?.invoke() ?: Out(emptyList(), 127, "not found")
        return run(o, Int.MAX_VALUE) { true }
    }

    override fun root(command: String, label: String, timeoutMs: Long, tail: Int, keep: (String) -> Boolean): StreamResult {
        calls += "root:$command"
        if (!rootVerified) return StreamResult(-1, emptyList(), 0, "Akses root belum diverifikasi.", false, false)
        val o = rootCmds[command]?.invoke() ?: Out(emptyList(), 127, "not found")
        return run(o, tail, keep)
    }
}
