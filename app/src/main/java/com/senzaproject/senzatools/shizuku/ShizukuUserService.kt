package com.senzaproject.senzatools.shizuku

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Implementasi nyata [IShizukuUserService]. Kelas ini TIDAK berjalan di proses aplikasi utama —
 * Shizuku yang menjalankannya di proses terpisah dengan UID shell (mode ADB) atau UID root
 * (kalau Shizuku sendiri jalan lewat root), sesuai konfigurasi Shizuku di device pengguna.
 *
 * Konstruktor kosong WAJIB ada (dipanggil reflektif oleh Shizuku lewat `Shizuku.bindUserService`).
 */
class ShizukuUserService() : IShizukuUserService.Stub() {

    override fun execShell(command: String, exitCodeOut: IntArray?): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exit = process.waitFor()
            if (exitCodeOut != null && exitCodeOut.isNotEmpty()) exitCodeOut[0] = exit
            output
        } catch (t: Throwable) {
            if (exitCodeOut != null && exitCodeOut.isNotEmpty()) exitCodeOut[0] = -1
            "Gagal menjalankan perintah lewat Shizuku: ${t.message}"
        }
    }

    override fun getUid(): Int = android.os.Process.myUid()

    override fun destroy() {
        // Shizuku akan menghentikan proses ini sendiri; tidak ada state yang perlu dibersihkan di sini.
    }
}
