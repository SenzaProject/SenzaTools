package com.senzaproject.senzaai.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long.formatBytes(): String {
    if (this < 0) return "-"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = this.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$this B" else String.format(Locale.US, "%.2f %s", v, units[i])
}

fun Float.f1(): String = String.format(Locale.US, "%.1f", this)
fun Double.f1(): String = String.format(Locale.US, "%.1f", this)
fun Float.f2(): String = String.format(Locale.US, "%.2f", this)

fun Long.formatKhz(): String =
    if (this >= 1_000_000) String.format(Locale.US, "%.2f GHz", this / 1_000_000.0)
    else "${this / 1000} MHz"

fun Long.formatDuration(): String {
    val s = this / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return buildString {
        if (d > 0) append("${d}h ")
        if (h > 0 || d > 0) append("${h}j ")
        append("${m}m ${sec}d")
    }
}

fun Long.formatTime(pattern: String = "yyyy-MM-dd HH:mm:ss"): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(this))

fun String.truncate(max: Int): String =
    if (length <= max) this else take(max) + "\n… (dipotong, total ${length} karakter)"
