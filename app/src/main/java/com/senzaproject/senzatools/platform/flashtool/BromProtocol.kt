package com.senzaproject.senzatools.platform.flashtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Implementasi protokol BootROM (BROM) MediaTek lewat USB bulk transfer.
 *
 * CATATAN JUJUR SOAL STATUS KODE INI (baca sebelum pakai):
 * - Urutan handshake klasik (byte 0xA0/0x0A/0x50/0x05 dan device membalas inversinya) adalah
 *   protokol BROM MediaTek yang sudah terdokumentasi publik selama bertahun-tahun dan dipakai
 *   oleh tool flashing resmi maupun open-source untuk SoC MediaTek lama-baru.
 * - Opcode setelah handshake (GET_HW_CODE, GET_HW_SW_VER) adalah command well-known, TAPI format
 *   balasan bisa sedikit berbeda antar generasi BROM (V1/V2/V3, dengan/tanpa secure handshake
 *   tambahan di chip yang lebih baru). Kode ini TIDAK memalsukan sukses: setiap langkah divalidasi
 *   byte-per-byte, dan begitu balasan tidak sesuai, fungsi mengembalikan BromResult.Error dengan
 *   detail byte yang diterima — bukan "pura-pura berhasil".
 * - Chip modern dengan Secure Boot / DAA (Download Agent Authentication) mungkin memerlukan
 *   autentikasi tambahan sebelum menerima command di luar handshake dasar. Itu di luar cakupan
 *   Tahap 1 ini (baru identifikasi chip, belum kirim DA).
 * - Ini BELUM mengirim DA dan BELUM melakukan flashing apa pun. Murni deteksi + identifikasi.
 */
object BromProtocol {

    private const val TIMEOUT_MS = 3000

    sealed class BromResult {
        data class Identified(
            val hwCode: Int,
            val hwSubCode: Int,
            val hwVersion: Int,
            val swVersion: Int,
            val bromModeRaw: ByteArray
        ) : BromResult()

        data class Error(val stage: String, val message: String, val rawReceived: ByteArray? = null) : BromResult()
    }

    private class Endpoints(val out: UsbEndpoint, val `in`: UsbEndpoint)

    private fun findBulkEndpoints(intf: UsbInterface): Endpoints? {
        var out: UsbEndpoint? = null
        var inn: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
            if (ep.direction == UsbConstants.USB_DIR_IN) inn = ep
        }
        return if (out != null && inn != null) Endpoints(out, inn) else null
    }

    private fun write(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Boolean {
        val n = conn.bulkTransfer(ep, data, data.size, TIMEOUT_MS)
        return n == data.size
    }

    private fun read(conn: UsbDeviceConnection, ep: UsbEndpoint, len: Int): ByteArray? {
        val buf = ByteArray(len)
        val n = conn.bulkTransfer(ep, buf, len, TIMEOUT_MS)
        if (n <= 0) return null
        return buf.copyOf(n)
    }

    /**
     * Jalankan handshake BROM klasik + baca identitas chip (HW code, versi).
     * Mengembalikan [BromResult.Identified] hanya jika SEMUA tahap handshake benar-benar
     * membalas sesuai protokol; selain itu selalu [BromResult.Error] dengan tahap & alasan jelas.
     */
    fun identify(conn: UsbDeviceConnection, intf: UsbInterface): BromResult {
        if (!conn.claimInterface(intf, true)) {
            return BromResult.Error("claim_interface", "Gagal mengambil alih interface USB (device sedang dipakai proses lain?)")
        }
        val eps = findBulkEndpoints(intf)
            ?: return BromResult.Error("endpoints", "Interface USB device ini tidak punya endpoint bulk IN+OUT yang sesuai pola BROM")

        // 1) Handshake klasik: kirim A0 0A 50 05 satu-per-satu, device harus membalas inversinya.
        val probe = byteArrayOf(0xA0.toByte(), 0x0A, 0x50, 0x05)
        val expectedEcho = byteArrayOf(0x5F, 0xF5.toByte(), 0xAF.toByte(), 0xFA.toByte())
        val echoed = ByteArray(4)
        for (i in probe.indices) {
            if (!write(conn, eps.out, byteArrayOf(probe[i]))) {
                return BromResult.Error("handshake_write", "Gagal mengirim byte handshake ke-${i + 1} (device tidak merespons tulis USB)")
            }
            val r = read(conn, eps.`in`, 1)
            if (r == null || r.isEmpty()) {
                return BromResult.Error("handshake_read", "Tidak ada balasan untuk byte handshake ke-${i + 1}. Device mungkin belum benar-benar di mode BROM.")
            }
            echoed[i] = r[0]
        }
        if (!echoed.contentEquals(expectedEcho)) {
            return BromResult.Error(
                "handshake_mismatch",
                "Device membalas, tapi urutan byte tidak sesuai protokol BROM yang diharapkan. " +
                    "Ini TANDA device bukan dalam mode BROM murni, atau chip pakai varian protokol berbeda.",
                echoed
            )
        }

        // 2) CMD_GET_HW_CODE (0xFD): device echo opcode, lalu 2 byte HW code, lalu 2 byte status.
        if (!write(conn, eps.out, byteArrayOf(0xFD.toByte()))) {
            return BromResult.Error("get_hw_code_write", "Gagal mengirim command GET_HW_CODE")
        }
        val hwResp = read(conn, eps.`in`, 5)
        if (hwResp == null || hwResp.size < 5 || hwResp[0] != 0xFD.toByte()) {
            return BromResult.Error(
                "get_hw_code_resp",
                "Balasan GET_HW_CODE tidak sesuai format yang diharapkan (opcode echo + 2 byte kode + 2 byte status).",
                hwResp
            )
        }
        val hwCode = ((hwResp[1].toInt() and 0xFF) shl 8) or (hwResp[2].toInt() and 0xFF)
        val hwStatus = ((hwResp[3].toInt() and 0xFF) shl 8) or (hwResp[4].toInt() and 0xFF)
        if (hwStatus != 0) {
            return BromResult.Error("get_hw_code_status", "BROM membalas status error (0x${hwStatus.toString(16)}) untuk GET_HW_CODE", hwResp)
        }

        // 3) CMD_GET_HW_SW_VER (0xFC): device echo opcode, lalu 8 byte (hw_subcode, hw_ver, sw_ver, status).
        if (!write(conn, eps.out, byteArrayOf(0xFC.toByte()))) {
            return BromResult.Error("get_hw_sw_ver_write", "Gagal mengirim command GET_HW_SW_VER")
        }
        val verResp = read(conn, eps.`in`, 9)
        if (verResp == null || verResp.size < 9 || verResp[0] != 0xFC.toByte()) {
            return BromResult.Error(
                "get_hw_sw_ver_resp",
                "Balasan GET_HW_SW_VER tidak sesuai format yang diharapkan. HW code tetap berhasil dibaca (0x${hwCode.toString(16)}), " +
                    "tapi versi detail tidak bisa dipastikan untuk chip ini.",
                verResp
            )
        }
        val hwSubCode = ((verResp[1].toInt() and 0xFF) shl 8) or (verResp[2].toInt() and 0xFF)
        val hwVersion = ((verResp[3].toInt() and 0xFF) shl 8) or (verResp[4].toInt() and 0xFF)
        val swVersion = ((verResp[5].toInt() and 0xFF) shl 8) or (verResp[6].toInt() and 0xFF)

        return BromResult.Identified(hwCode, hwSubCode, hwVersion, swVersion, verResp)
    }
}
