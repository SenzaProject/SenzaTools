package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.utils.StreamResult

/**
 * Pintu masuk shell untuk mesin Logcat dan SELinux. Dipisah dari AccessManager supaya logika
 * pengambilan dan penguraian log bisa diuji di JVM biasa dengan "Android palsu" (skrip logcat/dmesg/getenforce).
 */
interface SystemShell {
    /** True bila akses root sudah diverifikasi. */
    val rootVerified: Boolean

    /** Baca file kecil (mis. sysfs) sebagai aplikasi biasa; null bila tidak terbaca. */
    fun readFile(path: String): String?

    /** Jalankan perintah sebagai aplikasi biasa (sh -c). */
    fun app(command: String, timeoutMs: Long = 5_000): StreamResult

    /** Jalankan perintah sebagai root (su -c); dicatat di log operasi oleh implementasi aslinya. */
    fun root(
        command: String,
        label: String,
        timeoutMs: Long = 30_000,
        tail: Int = Int.MAX_VALUE,
        keep: (String) -> Boolean = { true }
    ): StreamResult
}
