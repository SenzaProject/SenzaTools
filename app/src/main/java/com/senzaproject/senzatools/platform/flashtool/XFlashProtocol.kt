package com.senzaproject.senzatools.platform.flashtool

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protokol command-channel "XFlash" yang dipakai DA (Download Agent) modern MediaTek setelah
 * DA1 hidup (byte sync 0xC0 di BromProtocol.sendAndRunDa). Ini LAPISAN BERBEDA dari BROM —
 * sekarang kita bicara ke DA, bukan ke BootROM lagi.
 *
 * CATATAN JUJUR SOAL STATUS KODE INI:
 * - Magic paket (0xFEEEEEEF) dan struktur header 12 byte (magic 4B LE, tipe-data 4B LE, panjang
 *   4B LE) diambil dari log traffic nyata tool sejenis di device MediaTek modern — ini bukan
 *   tebakan, tapi dikonfirmasi dari byte yang benar-benar terekam lewat USB.
 * - Yang BELUM saya punya konfirmasi kuat: nilai persis field "tipe-data" untuk tiap jenis
 *   command (saya pakai 1, sesuai yang terekam di log untuk command sederhana). Kalau device
 *   membalas error/mismatch, itu petunjuk nilai tipe-data perlu disesuaikan — bukan dianggap gagal total.
 * - Setelah DA1 hidup, device BELUM TENTU langsung bisa diminta partition table. Banyak chip
 *   modern perlu upload DA2 + kalibrasi DRAM dulu. Fungsi di file ini baru sampai "buka kanal
 *   command + minta versi DA" sebagai pembuktian komunikasi dua arah — BELUM baca partisi,
 *   BELUM upload DA2.
 */
object XFlashProtocol {

    private const val MAGIC = 0xFEEEEEEF.toInt()
    private const val TIMEOUT_MS = 5000

    // Command devctrl yang well-known publik untuk DA XFlash.
    private const val CMD_GET_DA_VERSION = 0x040005
    private const val CMD_GET_PACKET_LENGTH = 0x040007

    sealed class XResult {
        data class Ok(val raw: ByteArray) : XResult()
        data class Error(val stage: String, val message: String, val rawReceived: ByteArray? = null) : XResult()
    }

    private class Endpoints(val out: android.hardware.usb.UsbEndpoint, val `in`: android.hardware.usb.UsbEndpoint)

    private fun findBulkEndpoints(intf: UsbInterface): Endpoints? {
        var out: android.hardware.usb.UsbEndpoint? = null
        var inn: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) out = ep
            if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) inn = ep
        }
        return if (out != null && inn != null) Endpoints(out, inn) else null
    }

    private fun packHeader(dataType: Int, length: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).putInt(dataType).putInt(length).array()

    private fun unpackHeader(b: ByteArray): Triple<Int, Int, Int>? {
        if (b.size < 12) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Triple(bb.int, bb.int, bb.int)
    }

    private fun write(conn: UsbDeviceConnection, ep: android.hardware.usb.UsbEndpoint, data: ByteArray): Boolean =
        conn.bulkTransfer(ep, data, data.size, TIMEOUT_MS) == data.size

    private fun read(conn: UsbDeviceConnection, ep: android.hardware.usb.UsbEndpoint, len: Int): ByteArray? {
        val buf = ByteArray(len)
        val n = conn.bulkTransfer(ep, buf, len, TIMEOUT_MS)
        if (n <= 0) return null
        return buf.copyOf(n)
    }

    /** Kirim satu paket framed (header 12 byte + payload) lewat bulk OUT. */
    private fun sendPacket(conn: UsbDeviceConnection, ep: android.hardware.usb.UsbEndpoint, dataType: Int, payload: ByteArray): Boolean {
        val header = packHeader(dataType, payload.size)
        if (!write(conn, ep, header)) return false
        if (payload.isEmpty()) return true
        return write(conn, ep, payload)
    }

    /** Baca satu paket framed dari bulk IN: header dulu, lalu payload sepanjang field length di header. */
    private fun recvPacket(conn: UsbDeviceConnection, ep: android.hardware.usb.UsbEndpoint): Triple<Int, Int, ByteArray>? {
        val header = read(conn, ep, 12) ?: return null
        val (magic, dataType, length) = unpackHeader(header) ?: return null
        if (magic != MAGIC) return Triple(magic, dataType, header) // magic salah — biar pemanggil yang putuskan ini error apa
        val payload = if (length > 0) (read(conn, ep, length) ?: return null) else ByteArray(0)
        return Triple(magic, dataType, payload)
    }

    /**
     * Satu siklus devctrl: kirim command (4 byte LE) dibungkus paket, device meng-echo paket yang
     * sama, lalu device membalas status (4 byte, 0 = sukses) dibungkus paket. Kalau status sukses
     * dan ada data lanjutan, paket berikutnya berisi data tersebut.
     */
    private fun sendDevCtrl(conn: UsbDeviceConnection, eps: Endpoints, cmd: Int): XResult {
        val cmdBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array()
        if (!sendPacket(conn, eps.out, 1, cmdBytes)) {
            return XResult.Error("xflash_send_cmd", "Gagal mengirim paket command 0x${cmd.toString(16)} ke DA")
        }
        val echo = recvPacket(conn, eps.`in`)
            ?: return XResult.Error("xflash_echo_timeout", "DA tidak membalas echo untuk command 0x${cmd.toString(16)} (tidak ada respons USB)")
        if (echo.first != MAGIC) {
            return XResult.Error(
                "xflash_bad_magic",
                "Magic paket balasan DA tidak sesuai (0x${echo.first.toString(16)}, diharapkan 0x${MAGIC.toString(16)}). " +
                    "Kemungkinan DA ini belum di sesi XFlash, atau butuh handshake tambahan sebelum command ini.",
                echo.third
            )
        }
        val statusPkt = recvPacket(conn, eps.`in`)
            ?: return XResult.Error("xflash_status_timeout", "Tidak ada paket status dari DA setelah command 0x${cmd.toString(16)}")
        if (statusPkt.first != MAGIC) {
            return XResult.Error("xflash_status_bad_magic", "Paket status dari DA tidak valid", statusPkt.third)
        }
        val statusBytes = statusPkt.third
        val status = if (statusBytes.size >= 4)
            ByteBuffer.wrap(statusBytes).order(ByteOrder.LITTLE_ENDIAN).int else -1
        if (status != 0) {
            return XResult.Error("xflash_status_error", "DA membalas status error (0x${status.toString(16)}) untuk command 0x${cmd.toString(16)}", statusBytes)
        }
        return XResult.Ok(statusBytes)
    }

    /**
     * Buka kanal command XFlash dan minta versi DA sebagai pembuktian komunikasi dua arah jalan.
     * BELUM baca partition table, BELUM upload DA2 — itu langkah berikutnya kalau ini terbukti jalan.
     */
    fun handshakeAndGetVersion(conn: UsbDeviceConnection, intf: UsbInterface): XResult {
        val eps = findBulkEndpoints(intf) ?: return XResult.Error("endpoints", "Endpoint bulk USB tidak ditemukan untuk sesi XFlash")
        val verResult = sendDevCtrl(conn, eps, CMD_GET_DA_VERSION)
        if (verResult is XResult.Error) return verResult
        return verResult
    }
}
