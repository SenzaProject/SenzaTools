package com.senzaproject.senzatools.platform.flashtool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser GUID Partition Table (GPT) — format standar UEFI yang dipakai Android modern di
 * eMMC/UFS (bukan format khusus MediaTek). Ini bukan dugaan: struktur byte-nya sudah baku dan
 * publik selama bertahun-tahun, dipakai di hampir semua OS dan tool partisi.
 *
 * Tata letak yang dibaca (ukuran sektor diasumsikan 512 byte, standar untuk eMMC/UFS Android):
 *  - Sektor 0: Protective MBR (dilewati, tidak dipakai)
 *  - Sektor 1 (offset 512): GPT Header, diawali signature 8 byte "EFI PART"
 *      offset 0x08: revision, 0x0C: header_size, 0x10: header_crc32,
 *      0x48: partition_entry_lba (sektor awal tabel entri),
 *      0x50: jumlah entri partisi, 0x54: ukuran tiap entri (biasanya 128 byte)
 *  - Tabel entri partisi: tiap entri punya type-GUID(16) + unique-GUID(16) +
 *      first_lba(8) + last_lba(8) + flags(8) + nama partisi (UTF-16LE, 72 byte)
 *
 * Fungsi ini hanya PARSING data mentah yang sudah dibaca dari device — tidak membaca device
 * sendiri (lihat XFlashProtocol.readData untuk itu).
 */
object GptParser {

    data class Entry(val name: String, val firstLba: Long, val lastLba: Long) {
        val sizeBytes: Long get() = (lastLba - firstLba + 1) * 512L
    }

    sealed class Result {
        data class Ok(val entries: List<Entry>, val headerInfo: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(raw: ByteArray): Result {
        if (raw.size < 1024) return Result.Error("Data terlalu kecil untuk berisi GPT (${raw.size} byte, minimal 1024).")
        val sig = String(raw, 512, 8, Charsets.US_ASCII)
        if (sig != "EFI PART") {
            return Result.Error(
                "Signature \"EFI PART\" tidak ditemukan di sektor 1 (yang ada: \"$sig\"). " +
                    "Ini tanda data yang dibaca bukan GPT valid — bisa karena parameter baca (storage/parttype) " +
                    "belum tepat untuk chip ini, bukan berarti device tidak punya GPT."
            )
        }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val entryLba = bb.getLong(512 + 0x48)
        val numEntries = bb.getInt(512 + 0x50)
        val entrySize = bb.getInt(512 + 0x54)
        if (numEntries <= 0 || numEntries > 512 || entrySize < 128) {
            return Result.Error("Field GPT header tidak masuk akal (numEntries=$numEntries, entrySize=$entrySize) — header kemungkinan rusak/salah baca.")
        }
        val entryTableStart = (entryLba * 512).toInt()
        val entries = mutableListOf<Entry>()
        for (i in 0 until numEntries) {
            val off = entryTableStart + i * entrySize
            if (off + entrySize > raw.size) break // tabel entri lebih panjang dari data yang kita baca, wajar — kita cuma baca beberapa sektor awal
            val typeGuidZero = (0 until 16).all { raw[off + it] == 0.toByte() }
            if (typeGuidZero) continue // slot kosong, bukan partisi
            val firstLba = bb.getLong(off + 32)
            val lastLba = bb.getLong(off + 40)
            val nameBytes = raw.copyOfRange(off + 56, off + 56 + 72)
            val name = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000')
            entries.add(Entry(name, firstLba, lastLba))
        }
        if (entries.isEmpty()) {
            return Result.Error(
                "Signature GPT ditemukan, tapi tidak ada entri partisi terbaca dalam data yang dibaca. " +
                    "Kemungkinan perlu membaca lebih banyak sektor (tabel entri ada di luar jangkauan data saat ini)."
            )
        }
        return Result.Ok(entries, "entries table @ LBA $entryLba, $numEntries slot x $entrySize byte")
    }
}
