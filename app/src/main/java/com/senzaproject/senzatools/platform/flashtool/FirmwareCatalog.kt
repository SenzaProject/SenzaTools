package com.senzaproject.senzatools.platform.flashtool

import android.content.Context

/**
 * Katalog file DA & preloader yang dibundel langsung di dalam APK (folder assets/flashtool/),
 * supaya pengguna tidak perlu cari manual untuk device yang sudah didukung.
 *
 * Entri di bawah ini dibuat dari file nyata yang sudah diverifikasi lewat DaFile.parse():
 * - DA_BR_MT6789.bin   → HW code 0x1208 (umum untuk keluarga Helio G99/G100/G200, chip MT6789)
 * - DA_BR_X6871.bin    → HW code 0x1172
 * - DA_BR_X6882.bin    → HW code 0x1208, khusus device Infinix Hot 50 4G (model X6882)
 * - preloader_x6882_h8915.bin → preloader resmi untuk X6882 (Infinix Hot 50 4G), dipakai Tahap 4 (EMI)
 *
 * Kalau HW code device tidak ada di daftar ini, UI akan tawarkan "cari dari folder" lewat
 * FolderFirmwareSearch supaya pengguna bisa pakai koleksi firmware mereka sendiri.
 */
object FirmwareCatalog {

    data class Entry(
        val label: String,
        val hwCode: Int,
        val daAsset: String,
        val preloaderAsset: String?,
        val note: String
    )

    /** Urutan menentukan prioritas tampil kalau beberapa entri cocok dengan HW code yang sama (paling spesifik duluan). */
    val BUILTIN: List<Entry> = listOf(
        Entry(
            label = "Infinix Hot 50 4G (X6882)",
            hwCode = 0x1208,
            daAsset = "flashtool/DA_BR_X6882.bin",
            preloaderAsset = "flashtool/preloader_x6882_h8915.bin",
            note = "DA + preloader resmi khusus unit X6882 — pilihan paling tepat untuk device ini."
        ),
        Entry(
            label = "Helio G99 / G100 / G200 (MT6789, generik)",
            hwCode = 0x1208,
            daAsset = "flashtool/DA_BR_MT6789.bin",
            preloaderAsset = null,
            note = "DA generik keluarga MT6789 — dipakai kalau DA khusus X6882 bermasalah. Tidak ada preloader bundel untuk ini."
        ),
        Entry(
            label = "Chip HW code 0x1172 (X6871)",
            hwCode = 0x1172,
            daAsset = "flashtool/DA_BR_X6871.bin",
            preloaderAsset = null,
            note = "DA untuk device dengan HW code 0x1172."
        )
    )

    fun findForHwCode(hwCode: Int): List<Entry> = BUILTIN.filter { it.hwCode == hwCode }

    fun readAsset(ctx: Context, assetPath: String): ByteArray =
        ctx.assets.open(assetPath).use { it.readBytes() }
}
