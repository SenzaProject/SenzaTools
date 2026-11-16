package com.senzaproject.senzatools.platform.flashtool

/**
 * Ekstraksi data kalibrasi DRAM (EMI) dari file preloader.bin resmi sebuah device.
 *
 * CATATAN JUJUR: pola ekstraksi di bawah ("cari penanda ASCII MTK_BIN, data EMI mulai 12 byte
 * setelahnya sampai akhir file") diambil dari implementasi yang sama dipakai lintas banyak model
 * preloader MediaTek di tool sejenis. Tapi preloader beda chip/vendor bisa punya variasi kecil
 * (misalnya data tambahan di akhir file, atau marker ganda). Kalau hasil ekstraksi ternyata salah,
 * device akan menolak lewat status eksplisit ("EMI data NOT accepted") saat dikirim — bukan diam
 * seolah berhasil.
 */
object PreloaderFile {

    private val MARKER = "MTK_BIN".toByteArray(Charsets.US_ASCII)

    sealed class ExtractResult {
        data class Ok(val emi: ByteArray) : ExtractResult()
        data class Error(val message: String) : ExtractResult()
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun extractEmi(preloaderBytes: ByteArray): ExtractResult {
        val idx = indexOf(preloaderBytes, MARKER)
        if (idx < 0) {
            return ExtractResult.Error(
                "Penanda \"MTK_BIN\" tidak ditemukan di file ini — kemungkinan ini bukan preloader.bin yang valid, " +
                    "atau formatnya beda dari yang dikenal fungsi ini."
            )
        }
        val start = idx + 0x0C
        if (start >= preloaderBytes.size) {
            return ExtractResult.Error("Penanda \"MTK_BIN\" ditemukan, tapi tidak ada data sesudahnya (file terlalu pendek/terpotong).")
        }
        val emi = preloaderBytes.copyOfRange(start, preloaderBytes.size)
        if (emi.size < 16) {
            return ExtractResult.Error("Data EMI hasil ekstraksi terlalu kecil (${emi.size} byte) — kemungkinan offset tidak tepat untuk preloader ini.")
        }
        return ExtractResult.Ok(emi)
    }
}
