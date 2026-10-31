package com.senzaproject.senzaai.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/** Akses sistem level Normal: membaca file sysfs/procfs yang boleh dibaca aplikasi dan menjalankan getprop. */
object Sys {
    private val PROP_RE = Regex("""^\[(.*?)]: \[(.*)]$""")

    fun readText(path: String): String? = try {
        File(path).readText().trim()
    } catch (_: Throwable) {
        null
    }

    fun readLong(path: String): Long? = readText(path)?.toLongOrNull()

    @Volatile
    private var cachedProps: Map<String, String>? = null

    /** Semua system property (getprop). Di-cache; panggil [refreshProps] untuk memuat ulang. */
    fun props(): Map<String, String> = cachedProps ?: refreshProps()

    fun refreshProps(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val r = execute(listOf("getprop"), 8000)
        r.output.lineSequence().forEach { line ->
            val m = PROP_RE.matchEntire(line.trim())
            if (m != null) out[m.groupValues[1]] = m.groupValues[2]
        }
        cachedProps = out
        return out
    }

    fun prop(key: String, def: String = ""): String = props()[key]?.takeIf { it.isNotEmpty() } ?: def

    fun execute(args: List<String>, timeoutMs: Long = 10_000, maxChars: Int = 300_000): ShellResult {
        return try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) {
                                if (sb.length < maxChars) sb.append(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) p.destroyForcibly()
            reader.join(1500)
            val text = synchronized(sb) { sb.toString() }
            ShellResult(if (finished) p.exitValue() else -1, text, !finished)
        } catch (e: Throwable) {
            ShellResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    fun sh(command: String, timeoutMs: Long = 10_000): ShellResult =
        execute(listOf("sh", "-c", command), timeoutMs)
}

/** Helper Storage Access Framework. */
object Saf {
    fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: (uri.lastPathSegment ?: "file")
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "file"
        }
    }

    fun size(ctx: Context, uri: Uri): Long {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    fun readText(ctx: Context, uri: Uri, maxBytes: Int = 4 * 1024 * 1024): String {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Tidak dapat membuka file")
        return input.use { readLimited(it, maxBytes).toString(Charsets.UTF_8) }
    }

    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** Menyalin Uri ke cache aplikasi agar bisa dibuka sebagai File (mis. untuk APK). */
    fun copyToCache(ctx: Context, uri: Uri, fileName: String): File {
        val dir = File(ctx.cacheDir, "analysis").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, safe)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Tidak dapat membuka file")
        input.use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
        return dest
    }

    fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "analysis").deleteRecursively()
    }
}
