package com.senzaproject.senzatools.platform.flashtool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser untuk file DA (Download Agent) MediaTek, format umum yang dipakai SP Flash Tool /
 * mtkclient ("MTK_AllInOne_DA.bin" atau sejenis — ini format file, bukan kode berhak cipta siapa
 * pun, sama seperti parser ZIP atau ELF).
 *
 * Struktur (field yang dipakai di sini saja, offset lain di header diabaikan karena tidak perlu
 * untuk Tahap 2):
 *  0x00-0x12  magic "MTK_DOWNLOAD_AGENT"
 *  0x20-0x60  ID DA (string, menentukan varian: Legacy / MTK_AllInOne_DA_v3 (XFlash) / MTK_DA_v6 (XML))
 *  0x68-0x6C  jumlah entri chip (num_socs), uint32 LE
 *  0x6C..     array entri chip, ukuran per entri 0xD8 (Legacy) atau 0xDC (XFlash/XML)
 *
 * Tiap entri chip:
 *  0x00-0x02  magic entri 0xDA 0xDA
 *  0x02-0x04  HW code (uint16 LE)
 *  0x10-0x12  index region awal (biasanya 0)
 *  0x12-0x14  jumlah region
 *  0x14..     tabel region, tiap region 0x20 byte:
 *             0x00-0x04 offset-di-file, 0x04-0x08 panjang (termasuk signature),
 *             0x08-0x0C alamat muat di memori, 0x0C-0x10 panjang-tanpa-signature,
 *             0x10-0x14 panjang signature
 *
 * CATATAN JUJUR: offset-offset di atas diambil dari dokumentasi publik tentang format file DA
 * MediaTek yang sudah dipakai lintas tool selama bertahun-tahun. Kalau parsing gagal atau entri
 * untuk chip kamu tidak ketemu, fungsi ini melaporkan error spesifik — tidak menebak-nebak offset
 * baru secara diam-diam.
 */
object DaFile {

    data class DaRegion(
        val fileOffset: Int,
        val totalLength: Int,
        val loadAddress: Long,
        val regionLength: Int,
        val sigLength: Int
    )

    data class DaEntry(val hwCode: Int, val regions: List<DaRegion>)

    sealed class ParseResult {
        data class Ok(val daId: String, val entries: List<DaEntry>) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    private fun le32(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun le16(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    fun parse(data: ByteArray): ParseResult {
        if (data.size < 0x6C) return ParseResult.Error("File terlalu kecil untuk header DA (${data.size} byte)")
        val magic = String(data, 0, 0x12, Charsets.US_ASCII)
        if (magic != "MTK_DOWNLOAD_AGENT") {
            return ParseResult.Error("Bukan file DA MediaTek yang valid (magic header tidak cocok: \"$magic\")")
        }
        val daId = String(data, 0x20, 0x40, Charsets.US_ASCII).trim { it == '\u0000' || it.isWhitespace() }
        val entrySize = when {
            daId.contains("v6", ignoreCase = true) -> 0xDC
            daId.contains("AllInOne", ignoreCase = true) -> 0xDC
            else -> 0xD8
        }
        val numSocs = le32(data, 0x68)
        if (numSocs <= 0 || numSocs > 64) {
            return ParseResult.Error("Jumlah entri chip pada file DA tidak masuk akal ($numSocs) — kemungkinan file rusak atau format tidak dikenal")
        }
        val entries = mutableListOf<DaEntry>()
        var pos = 0x6C
        for (i in 0 until numSocs) {
            if (pos + entrySize > data.size) {
                return ParseResult.Error("File DA terpotong: entri chip ke-${i + 1} melewati akhir file")
            }
            if (data[pos] != 0xDA.toByte() || data[pos + 1] != 0xDA.toByte()) {
                return ParseResult.Error("Entri chip ke-${i + 1} tidak diawali magic 0xDADA — struktur file tidak sesuai yang diharapkan")
            }
            val hwCode = le16(data, pos + 0x02)
            val regionCount = le16(data, pos + 0x12)
            if (regionCount <= 0 || regionCount > 16) {
                return ParseResult.Error("Jumlah region pada entri chip 0x${hwCode.toString(16)} tidak masuk akal ($regionCount)")
            }
            val regions = mutableListOf<DaRegion>()
            var rpos = pos + 0x14
            for (r in 0 until regionCount) {
                if (rpos + 0x20 > pos + entrySize) break
                val offset = le32(data, rpos)
                val length = le32(data, rpos + 0x04)
                val addr = le32(data, rpos + 0x08).toLong() and 0xFFFFFFFFL
                val regionLength = le32(data, rpos + 0x0C)
                val sigLength = le32(data, rpos + 0x10)
                regions.add(DaRegion(offset, length, addr, regionLength, sigLength))
                rpos += 0x20
            }
            entries.add(DaEntry(hwCode, regions))
            pos += entrySize
        }
        return ParseResult.Ok(daId, entries)
    }

    /** Region 1 = DA1 (stage pertama yang dijalankan BROM). Region 0 = loader/stub, Region 2 = DA2. */
    fun da1Of(entry: DaEntry): DaRegion? = entry.regions.getOrNull(1) ?: entry.regions.getOrNull(0)
}
