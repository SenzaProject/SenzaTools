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
    private const val CMD_BOOT_TO = 0x10008

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

    /**
     * Kirim data kalibrasi DRAM (EMI, diekstrak dari preloader.bin lewat PreloaderFile) ke DA.
     * Ini LANGKAH SEBELUM device mau membalas info storage/partition table — bukan menulis ke
     * penyimpanan permanen, murni data konfigurasi RAM untuk sesi DA ini saja.
     *
     * CATATAN JUJUR: urutan "kirim panjang EMI dulu, lalu kirim data EMI, lalu baca status terima/tolak"
     * diambil dari pola yang konsisten dipakai tool sejenis untuk EMI pada sesi XFlash. Device yang
     * menolak (status bukan 0) berarti data EMI ini tidak cocok untuk chip/board ini — kemungkinan
     * besar preloader.bin yang dipakai bukan untuk unit yang sama persis, bukan bug di pengiriman.
     */
    fun sendEmi(conn: UsbDeviceConnection, intf: UsbInterface, emi: ByteArray): XResult {
        val eps = findBulkEndpoints(intf) ?: return XResult.Error("endpoints", "Endpoint bulk USB tidak ditemukan untuk sesi XFlash")
        return sendLengthPrefixedBlock(conn, eps, emi, "emi")
    }

    /** Kirim blok data (panjang dulu, lalu data, lalu baca status) — pola yang sama dipakai EMI dan upload DA2. */
    private fun sendLengthPrefixedBlock(conn: UsbDeviceConnection, eps: Endpoints, data: ByteArray, tag: String): XResult {
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        if (!sendPacket(conn, eps.out, 1, lenBytes)) {
            return XResult.Error("${tag}_send_len", "Gagal mengirim panjang data ($tag) ke DA")
        }
        val echoLen = recvPacket(conn, eps.`in`)
            ?: return XResult.Error("${tag}_len_ack_timeout", "DA tidak membalas setelah panjang data ($tag) dikirim")
        if (echoLen.first != MAGIC) {
            return XResult.Error("${tag}_len_bad_magic", "Balasan DA untuk panjang data ($tag) tidak valid", echoLen.third)
        }
        if (!sendPacket(conn, eps.out, 1, data)) {
            return XResult.Error("${tag}_send_data", "Gagal mengirim data $tag (panjang ${data.size} byte) ke DA")
        }
        val statusPkt = recvPacket(conn, eps.`in`)
            ?: return XResult.Error("${tag}_status_timeout", "Tidak ada balasan status dari DA setelah data $tag dikirim")
        if (statusPkt.first != MAGIC) {
            return XResult.Error("${tag}_status_bad_magic", "Paket status $tag dari DA tidak valid", statusPkt.third)
        }
        val statusBytes = statusPkt.third
        val status = if (statusBytes.size >= 4) ByteBuffer.wrap(statusBytes).order(ByteOrder.LITTLE_ENDIAN).int else -1
        if (status != 0) {
            return XResult.Error("${tag}_rejected", "DA menolak data $tag (status 0x${status.toString(16)}).", statusBytes)
        }
        return XResult.Ok(statusBytes)
    }

    /**
     * Upload DA2 ke alamat yang diminta DA file (biasanya 0x40000000 di DRAM), lalu kirim BOOT_TO
     * untuk menjalankannya.
     *
     * CATATAN JUJUR — INI BAGIAN PALING TIDAK PASTI SEJAUH INI:
     * - Pola "kirim panjang, kirim data, baca status" untuk upload DA2 adalah ASUMSI bahwa upload
     *   DA2 memakai mekanisme generik yang sama dengan kirim EMI. Belum ada konfirmasi langsung
     *   byte-level untuk ini seperti yang sudah didapat untuk EMI/preloader.
     * - CMD_BOOT_TO (0x10008) dipakai dengan menambahkan alamat tujuan (4 byte) sebagai payload
     *   tambahan setelah kode command — ini juga asumsi, belum dikonfirmasi byte-per-byte.
     * - Setelah BOOT_TO diterima, beberapa device MELEPAS KONEKSI USB SAAT INI dan muncul lagi
     *   dengan parameter USB berbeda ("reconnect dengan kecepatan lebih tinggi"). Fungsi ini TIDAK
     *   menangani reconnect — kalau itu terjadi, pembacaan status setelah BOOT_TO akan timeout,
     *   dan FlashToolEngine akan melaporkan error dengan saran pindai ulang dari Tahap 1, bukan
     *   berpura-pura berhasil.
     */
    fun uploadDa2AndBootTo(conn: UsbDeviceConnection, intf: UsbInterface, da2: ByteArray, bootAddress: Long): XResult {
        val eps = findBulkEndpoints(intf) ?: return XResult.Error("endpoints", "Endpoint bulk USB tidak ditemukan untuk sesi XFlash")

        val uploadResult = sendLengthPrefixedBlock(conn, eps, da2, "da2")
        if (uploadResult is XResult.Error) return uploadResult

        val bootPayload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CMD_BOOT_TO).putInt(bootAddress.toInt()).array()
        if (!sendPacket(conn, eps.out, 1, bootPayload)) {
            return XResult.Error("boot_to_send", "Gagal mengirim command BOOT_TO ke DA")
        }
        val echoBoot = recvPacket(conn, eps.`in`)
            ?: return XResult.Error(
                "boot_to_echo_timeout",
                "DA tidak membalas BOOT_TO. Kemungkinan device sudah reconnect/USB berubah di titik ini — " +
                    "kalau begitu, pindai ulang dari Tahap 1, bukan ulangi tombol ini."
            )
        if (echoBoot.first != MAGIC) {
            return XResult.Error("boot_to_bad_magic", "Balasan BOOT_TO tidak valid", echoBoot.third)
        }
        val statusPkt = recvPacket(conn, eps.`in`)
            ?: return XResult.Error(
                "boot_to_status_timeout",
                "Tidak ada status setelah BOOT_TO. Device kemungkinan sedang reconnect USB (stage 2 biasanya " +
                    "tersambung ulang dengan kecepatan berbeda) — coba pindai ulang dari Tahap 1."
            )
        if (statusPkt.first != MAGIC) {
            return XResult.Error("boot_to_status_bad_magic", "Paket status BOOT_TO tidak valid", statusPkt.third)
        }
        val statusBytes = statusPkt.third
        val status = if (statusBytes.size >= 4) ByteBuffer.wrap(statusBytes).order(ByteOrder.LITTLE_ENDIAN).int else -1
        if (status != 0) {
            return XResult.Error("boot_to_rejected", "DA menolak BOOT_TO (status 0x${status.toString(16)})", statusBytes)
        }
        return XResult.Ok(statusBytes)
    }
}
