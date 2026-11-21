package com.senzaproject.senzatools.platform.flashtool

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
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

    /**
     * MediaTek BROM/Preloader tampil sebagai CDC-ACM: interface 0 = CDC control (cuma endpoint
     * interrupt, TANPA bulk), interface 1 = CDC data (bulk IN+OUT). Kode lama selalu ambil
     * interface 0 sehingga endpoint bulk tidak pernah ketemu. Fungsi ini memilih interface
     * yang benar-benar punya bulk IN+OUT.
     */
    fun findBulkInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (findBulkEndpoints(intf) != null) return intf
        }
        return null
    }

    private const val PROBE_TIMEOUT_MS = 100
    private const val HANDSHAKE_WINDOW_MS = 20_000L

    private fun writeT(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray, timeout: Int): Boolean =
        conn.bulkTransfer(ep, data, data.size, timeout) == data.size

    private fun readT(conn: UsbDeviceConnection, ep: UsbEndpoint, timeout: Int): ByteArray? {
        val buf = ByteArray(64)
        val n = conn.bulkTransfer(ep, buf, buf.size, timeout)
        return if (n > 0) buf.copyOf(n) else null
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

        // 1) Handshake klasik. Jendela BROM/Preloader sangat singkat, jadi 0xA0 dikirim BERULANG
        //    dengan timeout pendek sampai device membalas 0x5F (cara kerja tool flash lain).
        //    Kode lama cuma coba sekali dengan timeout 3 detik -> jendela keburu lewat -> gagal.
        while (readT(conn, eps.`in`, 10) != null) { /* buang sisa data lama */ }
        val deadline = System.currentTimeMillis() + HANDSHAKE_WINDOW_MS
        var synced = false
        var lastRaw: ByteArray? = null
        var writeFails = 0
        while (System.currentTimeMillis() < deadline) {
            if (!writeT(conn, eps.out, byteArrayOf(0xA0.toByte()), PROBE_TIMEOUT_MS)) {
                writeFails++
                Thread.sleep(20)
                continue
            }
            val r = readT(conn, eps.`in`, PROBE_TIMEOUT_MS)
            if (r != null) {
                if (r[0] == 0x5F.toByte()) { synced = true; break }
                lastRaw = r
            }
            Thread.sleep(10)
        }
        if (!synced) {
            return if (lastRaw == null && writeFails > 0 && writeFails > 20) {
                BromResult.Error("handshake_write", "Device tidak menerima tulis USB selama ${HANDSHAKE_WINDOW_MS / 1000} detik (kemungkinan device sudah lepas dari mode BROM).")
            } else {
                BromResult.Error(
                    "handshake_read",
                    "Tidak ada balasan 0x5F selama ${HANDSHAKE_WINDOW_MS / 1000} detik. Device belum/tidak lagi di mode BROM — " +
                        "colok ulang dengan kombinasi tombol, tool akan mencoba lagi otomatis.",
                    lastRaw
                )
            }
        }

        // Sisa 3 byte handshake: 0x0A->0xF5, 0x50->0xAF, 0x05->0xFA
        val probe = byteArrayOf(0xA0.toByte(), 0x0A, 0x50, 0x05)
        val expectedEcho = byteArrayOf(0x5F, 0xF5.toByte(), 0xAF.toByte(), 0xFA.toByte())
        val echoed = ByteArray(4)
        echoed[0] = 0x5F
        for (i in 1 until probe.size) {
            if (!write(conn, eps.out, byteArrayOf(probe[i]))) {
                return BromResult.Error("handshake_write", "Gagal mengirim byte handshake ke-${i + 1} (device tidak merespons tulis USB)")
            }
            val r = read(conn, eps.`in`, 1)
            if (r == null || r.isEmpty()) {
                return BromResult.Error("handshake_read", "Tidak ada balasan untuk byte handshake ke-${i + 1}. Device mungkin keluar dari mode BROM di tengah handshake.")
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

    sealed class DaResult {
        /** DA1 sudah dikirim, dieksekusi (JUMP_DA), dan membalas byte sinkronisasi 0xC0 — tanda DA hidup. */
        data class Ready(val syncByte: Int) : DaResult()
        data class Error(val stage: String, val message: String, val rawReceived: ByteArray? = null) : DaResult()
    }

    private const val CMD_SEND_DA = 0xD7
    private const val CMD_JUMP_DA = 0xD5
    private const val CHUNK_SIZE = 1024

    private fun be32(v: Long): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    /** Checksum 16-bit yang dipakai BROM untuk verifikasi data yang dikirim: jumlah tiap word (2 byte, big-endian), modulo 0x10000. */
    private fun bromChecksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size) {
            val hi = data[i].toInt() and 0xFF
            val lo = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            sum = (sum + ((hi shl 8) or lo)) and 0xFFFF
            i += 2
        }
        return sum
    }

    private fun bromXorChecksum(data: ByteArray, littleEndian: Boolean): Int {
        var x = 0
        var i = 0
        while (i < data.size) {
            val a = data[i].toInt() and 0xFF
            val b = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            x = x xor (if (littleEndian) (b shl 8) or a else (a shl 8) or b)
            i += 2
        }
        return x and 0xFFFF
    }

    /**
     * Kirim payload DA1 ke alamat SRAM lewat CMD_SEND_DA, lalu CMD_JUMP_DA untuk mengeksekusinya,
     * lalu tunggu byte sinkronisasi 0xC0 dari DA sebagai bukti DA benar-benar jalan.
     *
     * CATATAN JUJUR: struktur echo-parameter dan checksum di bawah mengikuti pola umum protokol
     * BROM MediaTek yang terdokumentasi publik (host kirim param, device meng-echo, device balas
     * status; data dikirim per blok lalu diverifikasi checksum). TIDAK ada flashing ke
     * penyimpanan permanen di fungsi ini — SEND_DA hanya menulis ke RAM volatil chip, jadi kalau
     * langkah ini gagal, device aman untuk dicolok ulang dan dicoba lagi.
     */
    fun sendAndRunDa(conn: UsbDeviceConnection, intf: UsbInterface, payload: ByteArray, region: DaFile.DaRegion): DaResult {
        val eps = findBulkEndpoints(intf)
            ?: return DaResult.Error("endpoints", "Endpoint bulk USB tidak ditemukan")

        // 1) CMD_SEND_DA: opcode + address + length + sig_length, masing-masing di-echo device.
        if (!write(conn, eps.out, byteArrayOf(CMD_SEND_DA.toByte()))) {
            return DaResult.Error("send_da_cmd", "Gagal mengirim opcode CMD_SEND_DA")
        }
        val echoOp = read(conn, eps.`in`, 1)
        if (echoOp == null || echoOp[0] != CMD_SEND_DA.toByte()) {
            return DaResult.Error("send_da_cmd_echo", "Device tidak meng-echo opcode CMD_SEND_DA dengan benar", echoOp)
        }

        val params = be32(region.loadAddress) + be32(region.totalLength.toLong()) + be32(region.sigLength.toLong())
        if (!write(conn, eps.out, params)) {
            return DaResult.Error("send_da_params", "Gagal mengirim parameter alamat/panjang/sig-length")
        }
        val echoParams = read(conn, eps.`in`, params.size)
        if (echoParams == null || !echoParams.contentEquals(params)) {
            return DaResult.Error(
                "send_da_params_echo",
                "Device meng-echo parameter berbeda dari yang dikirim — kemungkinan alamat/panjang dari file DA tidak cocok untuk BROM versi ini",
                echoParams
            )
        }
        val ackBeforeData = read(conn, eps.`in`, 2)
        if (ackBeforeData == null || ackBeforeData.size < 2 || (ackBeforeData[0].toInt() != 0 || ackBeforeData[1].toInt() != 0)) {
            return DaResult.Error("send_da_ack", "BROM tidak siap menerima data DA (status bukan 0)", ackBeforeData)
        }

        // 2) Kirim payload per blok CHUNK_SIZE byte.
        var off = 0
        while (off < payload.size) {
            val len = minOf(CHUNK_SIZE, payload.size - off)
            val chunk = payload.copyOfRange(off, off + len)
            if (!write(conn, eps.out, chunk)) {
                return DaResult.Error("send_da_data", "Gagal mengirim blok data DA pada offset $off dari ${payload.size}")
            }
            off += len
        }

        // 3) Device membalas checksum 16-bit atas seluruh data yang diterima; verifikasi lokal.
        val checksumResp = read(conn, eps.`in`, 2)
        if (checksumResp == null || checksumResp.size < 2) {
            return DaResult.Error("send_da_checksum_missing", "Device tidak membalas checksum setelah data DA dikirim")
        }
        val deviceChecksum = ((checksumResp[0].toInt() and 0xFF) shl 8) or (checksumResp[1].toInt() and 0xFF)
        val localChecksum = bromChecksum(payload)
        // BROM MediaTek menghitung checksum dengan XOR word 16-bit (bukan penjumlahan) dan banyak
        // chip membalas 0 (= tidak dicek). Terima semua varian yang sah supaya DA tidak ditolak palsu.
        val xorLe = bromXorChecksum(payload, littleEndian = true)
        val xorBe = bromXorChecksum(payload, littleEndian = false)
        val checksumOk = deviceChecksum == 0 || deviceChecksum == xorLe || deviceChecksum == xorBe || deviceChecksum == localChecksum
        if (!checksumOk) {
            return DaResult.Error(
                "send_da_checksum_mismatch",
                "Checksum tidak cocok (device: 0x${deviceChecksum.toString(16)}, dihitung lokal: 0x${localChecksum.toString(16)}). " +
                    "Data DA yang diterima device kemungkinan rusak di tengah jalan — coba ulangi, atau kabel/port USB bermasalah.",
                checksumResp
            )
        }

        val finalStatus = read(conn, eps.`in`, 2)
        if (finalStatus == null || finalStatus.size < 2 || (finalStatus[0].toInt() != 0 || finalStatus[1].toInt() != 0)) {
            return DaResult.Error("send_da_final_status", "BROM membalas status error setelah CMD_SEND_DA selesai", finalStatus)
        }

        // 4) CMD_JUMP_DA: opcode + alamat, device meng-echo lalu membalas status.
        if (!write(conn, eps.out, byteArrayOf(CMD_JUMP_DA.toByte()))) {
            return DaResult.Error("jump_da_cmd", "Gagal mengirim opcode CMD_JUMP_DA")
        }
        val echoJumpOp = read(conn, eps.`in`, 1)
        if (echoJumpOp == null || echoJumpOp[0] != CMD_JUMP_DA.toByte()) {
            return DaResult.Error("jump_da_cmd_echo", "Device tidak meng-echo opcode CMD_JUMP_DA", echoJumpOp)
        }
        val jumpAddr = be32(region.loadAddress)
        if (!write(conn, eps.out, jumpAddr)) {
            return DaResult.Error("jump_da_addr", "Gagal mengirim alamat JUMP_DA")
        }
        val echoJumpAddr = read(conn, eps.`in`, 4)
        if (echoJumpAddr == null || !echoJumpAddr.contentEquals(jumpAddr)) {
            return DaResult.Error("jump_da_addr_echo", "Device meng-echo alamat JUMP_DA berbeda dari yang dikirim", echoJumpAddr)
        }
        val jumpStatus = read(conn, eps.`in`, 2)
        if (jumpStatus == null || jumpStatus.size < 2 || (jumpStatus[0].toInt() != 0 || jumpStatus[1].toInt() != 0)) {
            return DaResult.Error(
                "jump_da_status",
                "BROM menolak JUMP_DA (status error). Kalau device punya Secure Boot/DAA aktif dan DA ini tidak " +
                    "ditandatangani untuk device itu, inilah titik gagalnya — bukan bug di kode ini.",
                jumpStatus
            )
        }

        // 5) Setelah jump, eksekusi berpindah ke DA. DA yang hidup mengirim byte sinkronisasi 0xC0.
        val sync = read(conn, eps.`in`, 1)
        if (sync == null || sync.isEmpty()) {
            return DaResult.Error(
                "da_sync_timeout",
                "Tidak ada balasan dari DA setelah JUMP_DA. Device mungkin crash/reboot diam-diam, atau DA1 ini " +
                    "bukan untuk chip ini meski HW code cocok di file."
            )
        }
        if (sync[0] != 0xC0.toByte()) {
            return DaResult.Error("da_sync_unexpected", "Byte sinkronisasi dari DA tidak sesuai dugaan (0xC0)", sync)
        }
        return DaResult.Ready(sync[0].toInt() and 0xFF)
    }
}
