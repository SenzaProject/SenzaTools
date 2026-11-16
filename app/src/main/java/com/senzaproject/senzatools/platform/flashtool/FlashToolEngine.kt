package com.senzaproject.senzatools.platform.flashtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import com.senzaproject.senzatools.data.OperationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VID MediaTek = 0x0E8D. PID yang umum dipakai perangkat masuk mode Preloader/BROM.
 * Daftar ini tidak lengkap untuk semua chip MediaTek yang pernah dirilis — kalau device kamu
 * tidak terdeteksi dengan PID di bawah, itu artinya PID device ini belum ada di daftar,
 * BUKAN berarti fitur ini otomatis dianggap "berhasil" atau "gagal total".
 */
private val KNOWN_MTK_PIDS = setOf(0x0003, 0x2000, 0x2001, 0x201D)
private const val MTK_VID = 0x0E8D

enum class FlashStage {
    IDLE, WAITING_DEVICE, PERMISSION_NEEDED, HANDSHAKING, IDENTIFIED,
    DA_PARSING, DA_MATCHED, DA_SENDING, DA_READY, XFLASH_SYNCING, XFLASH_READY,
    EMI_SENDING, EMI_ACCEPTED, ERROR
}

data class FlashToolState(
    val stage: FlashStage = FlashStage.IDLE,
    val deviceName: String? = null,
    val info: String? = null,
    val hwCode: Int? = null,
    val daSummary: String? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null,
    val catalogMatches: List<FirmwareCatalog.Entry> = emptyList(),
    val folderDaCandidates: List<FolderFirmwareSearch.DaCandidate> = emptyList(),
    val folderPreloaderCandidates: List<FolderFirmwareSearch.PreloaderCandidate> = emptyList(),
    val folderScanInfo: String? = null
)

/**
 * Flash Tool — Tahap 1 (deteksi USB + identifikasi chip lewat BROM) dan Tahap 2
 * (parse file DA, cocokkan dengan chip yang teridentifikasi, kirim DA1 lalu jalankan).
 * Koneksi USB disimpan terbuka selama satu sesi BROM supaya Tahap 2 bisa lanjut dari hasil
 * identifikasi Tahap 1 tanpa buka-tutup koneksi (yang berisiko membuat BROM melepas sesi).
 * Lihat BromProtocol.kt / DaFile.kt untuk detail protokol, asumsi, dan batasannya.
 */
class FlashToolEngine(private val ctx: Context, private val log: OperationLog) {

    private val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(FlashToolState())
    val state: StateFlow<FlashToolState> = _state.asStateFlow()

    private val actionUsbPermission = "${ctx.packageName}.USB_PERMISSION_FLASHTOOL"
    private var receiverRegistered = false

    // Sesi BROM yang sedang aktif (null kalau belum ada device teridentifikasi).
    private var session: Session? = null

    private class Session(
        val device: UsbDevice,
        val conn: UsbDeviceConnection,
        val intf: UsbInterface,
        val hwCode: Int,
        var daEntries: List<DaFile.DaEntry> = emptyList(),
        var matchedEntry: DaFile.DaEntry? = null
    )

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != actionUsbPermission) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (device == null) return
            if (granted) {
                runHandshake(device)
            } else {
                _state.value = FlashToolState(
                    stage = FlashStage.ERROR,
                    deviceName = device.deviceName,
                    errorStage = "usb_permission",
                    errorMessage = "Izin akses USB ditolak oleh pengguna/sistem. Tanpa izin ini, handshake BROM tidak bisa dimulai."
                )
            }
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    /** Dipanggil saat tab Flash Tool ditutup. Tidak menutup sesi BROM yang masih aktif secara otomatis. */
    fun dispose() {
        if (receiverRegistered) {
            try { ctx.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
    }

    private fun closeSession() {
        session?.let { try { it.conn.close() } catch (_: Throwable) {} }
        session = null
    }

    /** Pindai USB host yang tersambung sekarang dan cari device yang cocok pola MediaTek BROM/Preloader. */
    fun scanAndConnect() {
        closeSession()
        _state.value = FlashToolState(stage = FlashStage.WAITING_DEVICE)
        val candidates = usbManager.deviceList.values.filter { it.vendorId == MTK_VID }
        if (candidates.isEmpty()) {
            _state.value = FlashToolState(
                stage = FlashStage.ERROR,
                errorStage = "scan",
                errorMessage = "Tidak ada USB device dengan Vendor ID MediaTek (0x0E8D) terdeteksi. " +
                    "Pastikan HP dalam kondisi mati, baterai cukup, lalu colok kabel USB sambil tahan tombol volume " +
                    "(naik/turun, tergantung model) untuk masuk mode BROM/Preloader."
            )
            return
        }
        val device = candidates.firstOrNull { it.productId in KNOWN_MTK_PIDS } ?: candidates.first()
        val matchedKnownPid = device.productId in KNOWN_MTK_PIDS

        if (!usbManager.hasPermission(device)) {
            ensureReceiver()
            _state.value = FlashToolState(
                stage = FlashStage.PERMISSION_NEEDED,
                deviceName = device.deviceName,
                info = if (matchedKnownPid) null else
                    "PID 0x${device.productId.toString(16)} belum ada di daftar PID BROM yang dikenal — tetap dicoba, tapi hasil bisa berbeda."
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(actionUsbPermission), flags)
            usbManager.requestPermission(device, pi)
            return
        }
        runHandshake(device)
    }

    private fun runHandshake(device: UsbDevice) {
        _state.value = FlashToolState(stage = FlashStage.HANDSHAKING, deviceName = device.deviceName)
        scope.launch {
            val conn = usbManager.openDevice(device)
            if (conn == null) {
                postError(device.deviceName, "open_device", "Gagal membuka koneksi USB ke device (openDevice null).")
                return@launch
            }
            val intf = device.getInterface(0)
            val result = try {
                BromProtocol.identify(conn, intf)
            } catch (t: Throwable) {
                BromProtocol.BromResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is BromProtocol.BromResult.Identified -> {
                        val msg = "HW code: 0x${result.hwCode.toString(16)} | HW sub-code: 0x${result.hwSubCode.toString(16)} | " +
                            "HW ver: 0x${result.hwVersion.toString(16)} | SW ver: 0x${result.swVersion.toString(16)}"
                        log.add("FlashTool", "BROM identify ${device.deviceName}", "OK: $msg")
                        session = Session(device, conn, intf, result.hwCode)
                        val catalogMatches = FirmwareCatalog.findForHwCode(result.hwCode)
                        _state.value = FlashToolState(
                            stage = FlashStage.IDENTIFIED,
                            deviceName = device.deviceName,
                            info = msg,
                            hwCode = result.hwCode,
                            catalogMatches = catalogMatches
                        )
                    }
                    is BromProtocol.BromResult.Error -> {
                        log.add("FlashTool", "BROM identify ${device.deviceName}", "ERROR[${result.stage}]: ${result.message}")
                        try { conn.close() } catch (_: Throwable) {}
                        _state.value = FlashToolState(
                            stage = FlashStage.ERROR,
                            deviceName = device.deviceName,
                            errorStage = result.stage,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Baca & parse file DA yang dipilih pengguna (SAF), cocokkan dengan HW code device yang
     * sudah teridentifikasi di sesi ini. Tidak mengirim apa pun ke device di langkah ini.
     */
    fun pickDaFile(uri: Uri) {
        val sess = requireSessionForDa() ?: return
        _state.value = _state.value.copy(stage = FlashStage.DA_PARSING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "da_read", "Gagal membaca file DA yang dipilih (izin akses file atau file tidak valid).")
                return@launch
            }
            processDaBytes(sess, bytes, "pilihan manual")
        }
    }

    /** Pakai DA dari katalog bawaan APK (sudah diverifikasi cocok HW code) — tanpa perlu buka file picker. */
    fun useCatalogDa(entry: FirmwareCatalog.Entry) {
        val sess = requireSessionForDa() ?: return
        _state.value = _state.value.copy(stage = FlashStage.DA_PARSING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                FirmwareCatalog.readAsset(ctx, entry.daAsset)
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "da_asset_read", "Gagal membaca DA bawaan dari APK (\"${entry.daAsset}\").")
                return@launch
            }
            processDaBytes(sess, bytes, "katalog bawaan: ${entry.label}")
        }
    }

    /** Pakai DA hasil pencarian folder pengguna (sudah divalidasi cocok HW code saat scan). */
    fun useFolderDa(candidate: FolderFirmwareSearch.DaCandidate) {
        val sess = requireSessionForDa() ?: return
        _state.value = _state.value.copy(stage = FlashStage.DA_PARSING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                ctx.contentResolver.openInputStream(candidate.uri)?.use { it.readBytes() }
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "da_folder_read", "Gagal membaca ulang file DA \"${candidate.name}\" dari folder.")
                return@launch
            }
            processDaBytes(sess, bytes, "folder: ${candidate.name}")
        }
    }

    private fun requireSessionForDa(): Session? {
        val sess = session
        if (sess == null) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "da_no_session",
                errorMessage = "Belum ada device yang teridentifikasi. Lakukan \"Pindai & Deteksi\" dulu sebelum memilih file DA."
            )
        }
        return sess
    }

    private suspend fun processDaBytes(sess: Session, bytes: ByteArray, sourceLabel: String) {
        when (val parsed = DaFile.parse(bytes)) {
            is DaFile.ParseResult.Error -> postDaError(sess, "da_parse", parsed.message)
            is DaFile.ParseResult.Ok -> {
                val match = parsed.entries.firstOrNull { it.hwCode == sess.hwCode }
                if (match == null) {
                    val available = parsed.entries.joinToString(", ") { "0x${it.hwCode.toString(16)}" }
                    postDaError(
                        sess, "da_no_match",
                        "File DA ini (\"${parsed.daId}\", sumber: $sourceLabel) tidak punya entri untuk HW code device kamu " +
                            "(0x${sess.hwCode.toString(16)}). Chip yang didukung file ini: $available."
                    )
                    return
                }
                val da1 = DaFile.da1Of(match)
                if (da1 == null) {
                    postDaError(sess, "da_no_region", "Entri DA untuk chip ini ketemu, tapi tidak punya region DA1 yang bisa dibaca.")
                    return
                }
                sess.daEntries = parsed.entries
                sess.matchedEntry = match
                val summary = "DA ($sourceLabel) cocok untuk HW code 0x${sess.hwCode.toString(16)} | " +
                    "DA1 @ 0x${da1.loadAddress.toString(16)}, panjang 0x${da1.totalLength.toString(16)} " +
                    "(sig 0x${da1.sigLength.toString(16)})"
                withContext(Dispatchers.Main) {
                    log.add("FlashTool", "Parse DA", "OK: $summary")
                    _state.value = _state.value.copy(stage = FlashStage.DA_MATCHED, daSummary = summary)
                }
                pendingDaBytes = bytes
            }
        }
    }

    @Volatile private var pendingDaBytes: ByteArray? = null
    @Volatile private var pendingPreloaderBytes: ByteArray? = null

    /** Cari file DA/preloader yang cocok di folder milik pengguna (untuk device di luar katalog bawaan). */
    fun searchFolder(treeUri: Uri) {
        val sess = session ?: run {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "folder_no_session",
                errorMessage = "Belum ada device yang teridentifikasi."
            )
            return
        }
        scope.launch {
            val result = try {
                FolderFirmwareSearch.search(ctx, treeUri, sess.hwCode)
            } catch (t: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                if (result == null) {
                    _state.value = _state.value.copy(folderScanInfo = "Gagal membuka folder yang dipilih.")
                    return@withContext
                }
                val info = buildString {
                    append("${result.filesScanned} file .bin dipindai")
                    append(" — DA cocok: ${result.daCandidates.size}, kandidat preloader (dari nama file): ${result.preloaderCandidates.size}")
                    if (result.tooDeep) append(" (pencarian dihentikan di kedalaman folder tertentu supaya tidak terlalu lama)")
                }
                log.add("FlashTool", "Cari folder firmware", info)
                _state.value = _state.value.copy(
                    folderDaCandidates = result.daCandidates,
                    folderPreloaderCandidates = result.preloaderCandidates,
                    folderScanInfo = info
                )
            }
        }
    }

    /** Kirim DA1 yang sudah cocok ke device, lalu JUMP_DA, lalu tunggu konfirmasi DA hidup (byte 0xC0). */
    fun sendAndRunDa() {
        val sess = session
        val entry = sess?.matchedEntry
        val daBytes = pendingDaBytes
        if (sess == null || entry == null || daBytes == null) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "da_not_ready",
                errorMessage = "Belum ada file DA yang cocok dan terpilih untuk dikirim."
            )
            return
        }
        val region = DaFile.da1Of(entry)!!
        _state.value = _state.value.copy(stage = FlashStage.DA_SENDING, errorStage = null, errorMessage = null)
        scope.launch {
            val fileEnd = minOf(region.fileOffset + region.totalLength, daBytes.size)
            if (region.fileOffset < 0 || region.fileOffset >= daBytes.size || fileEnd <= region.fileOffset) {
                postDaError(sess, "da_region_bounds", "Region DA1 di luar batas file DA (offset/panjang tidak valid).")
                return@launch
            }
            val payload = daBytes.copyOfRange(region.fileOffset, fileEnd)
            val result = try {
                BromProtocol.sendAndRunDa(sess.conn, sess.intf, payload, region)
            } catch (t: Throwable) {
                BromProtocol.DaResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is BromProtocol.DaResult.Ready -> {
                        log.add("FlashTool", "Kirim+jalankan DA", "OK: DA mengirim sync 0x${result.syncByte.toString(16)}")
                        _state.value = _state.value.copy(
                            stage = FlashStage.DA_READY,
                            info = "DA siap (sync byte: 0x${result.syncByte.toString(16)}). Belum ada flashing — ini baru konfirmasi DA hidup."
                        )
                    }
                    is BromProtocol.DaResult.Error -> {
                        log.add("FlashTool", "Kirim+jalankan DA", "ERROR[${result.stage}]: ${result.message}")
                        _state.value = _state.value.copy(
                            stage = FlashStage.ERROR,
                            errorStage = result.stage,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    /** Tahap 3 (revisi): buka kanal command XFlash + minta versi DA. Belum baca partisi, belum upload DA2. */
    fun xflashHandshake() {
        val sess = session
        if (sess == null || _state.value.stage != FlashStage.DA_READY) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "xflash_not_ready",
                errorMessage = "DA belum dalam keadaan siap (DA_READY). Selesaikan Tahap 2 dulu."
            )
            return
        }
        _state.value = _state.value.copy(stage = FlashStage.XFLASH_SYNCING, errorStage = null, errorMessage = null)
        scope.launch {
            val result = try {
                XFlashProtocol.handshakeAndGetVersion(sess.conn, sess.intf)
            } catch (t: Throwable) {
                XFlashProtocol.XResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is XFlashProtocol.XResult.Ok -> {
                        val hex = result.raw.joinToString(" ") { "%02x".format(it) }
                        log.add("FlashTool", "XFlash handshake", "OK: raw=$hex")
                        _state.value = _state.value.copy(
                            stage = FlashStage.XFLASH_READY,
                            info = "Kanal XFlash terbuka. Balasan GET_DA_VERSION (raw hex): $hex"
                        )
                    }
                    is XFlashProtocol.XResult.Error -> {
                        log.add("FlashTool", "XFlash handshake", "ERROR[${result.stage}]: ${result.message}")
                        _state.value = _state.value.copy(
                            stage = FlashStage.ERROR,
                            errorStage = result.stage,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    /** Tahap 4: baca preloader.bin pilihan manual, ekstrak EMI, kirim ke DA sebagai kalibrasi DRAM. */
    fun pickPreloaderAndSendEmi(uri: Uri) {
        val sess = requireSessionForEmi() ?: return
        _state.value = _state.value.copy(stage = FlashStage.EMI_SENDING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "emi_read", "Gagal membaca file preloader.bin yang dipilih.")
                return@launch
            }
            processPreloaderBytes(sess, bytes)
        }
    }

    /** Pakai preloader dari katalog bawaan APK untuk kirim EMI, tanpa file picker. */
    fun useCatalogPreloader(entry: FirmwareCatalog.Entry) {
        val assetPath = entry.preloaderAsset
        if (assetPath == null) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "emi_no_catalog_preloader",
                errorMessage = "Entri katalog \"${entry.label}\" tidak punya preloader bawaan — pilih file preloader.bin secara manual."
            )
            return
        }
        val sess = requireSessionForEmi() ?: return
        _state.value = _state.value.copy(stage = FlashStage.EMI_SENDING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                FirmwareCatalog.readAsset(ctx, assetPath)
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "emi_asset_read", "Gagal membaca preloader bawaan dari APK (\"$assetPath\").")
                return@launch
            }
            processPreloaderBytes(sess, bytes)
        }
    }

    /** Pakai kandidat preloader hasil pencarian folder (dipilih dari nama file, belum tentu valid sampai dicoba). */
    fun useFolderPreloader(candidate: FolderFirmwareSearch.PreloaderCandidate) {
        val sess = requireSessionForEmi() ?: return
        _state.value = _state.value.copy(stage = FlashStage.EMI_SENDING, errorStage = null, errorMessage = null)
        scope.launch {
            val bytes = try {
                ctx.contentResolver.openInputStream(candidate.uri)?.use { it.readBytes() }
            } catch (t: Throwable) { null }
            if (bytes == null) {
                postDaError(sess, "emi_folder_read", "Gagal membaca ulang file \"${candidate.name}\" dari folder.")
                return@launch
            }
            processPreloaderBytes(sess, bytes)
        }
    }

    private fun requireSessionForEmi(): Session? {
        val sess = session
        if (sess == null || _state.value.stage != FlashStage.XFLASH_READY) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "emi_not_ready",
                errorMessage = "Kanal XFlash belum siap (XFLASH_READY). Selesaikan Tahap 3 dulu."
            )
            return null
        }
        return sess
    }

    private suspend fun processPreloaderBytes(sess: Session, bytes: ByteArray) {
        when (val extracted = PreloaderFile.extractEmi(bytes)) {
            is PreloaderFile.ExtractResult.Error -> postDaError(sess, "emi_extract", extracted.message)
            is PreloaderFile.ExtractResult.Ok -> {
                val result = try {
                    XFlashProtocol.sendEmi(sess.conn, sess.intf, extracted.emi)
                } catch (t: Throwable) {
                    XFlashProtocol.XResult.Error("exception", t.message ?: t.toString())
                }
                withContext(Dispatchers.Main) {
                    when (result) {
                        is XFlashProtocol.XResult.Ok -> {
                            log.add("FlashTool", "Kirim EMI", "OK: EMI ${extracted.emi.size} byte diterima")
                            pendingPreloaderBytes = bytes
                            _state.value = _state.value.copy(
                                stage = FlashStage.EMI_ACCEPTED,
                                info = "Data EMI (${extracted.emi.size} byte) diterima DA. Kalibrasi DRAM selesai — " +
                                    "belum ada baca/tulis partisi."
                            )
                        }
                        is XFlashProtocol.XResult.Error -> {
                            log.add("FlashTool", "Kirim EMI", "ERROR[${result.stage}]: ${result.message}")
                            _state.value = _state.value.copy(
                                stage = FlashStage.ERROR,
                                errorStage = result.stage,
                                errorMessage = result.message
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun postError(deviceName: String?, stage: String, message: String) {
        withContext(Dispatchers.Main) {
            log.add("FlashTool", "BROM identify $deviceName", "ERROR[$stage]: $message")
            _state.value = FlashToolState(stage = FlashStage.ERROR, deviceName = deviceName, errorStage = stage, errorMessage = message)
        }
    }

    private suspend fun postDaError(sess: Session, stage: String, message: String) {
        withContext(Dispatchers.Main) {
            log.add("FlashTool", "DA", "ERROR[$stage]: $message")
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                deviceName = sess.device.deviceName,
                hwCode = sess.hwCode,
                errorStage = stage,
                errorMessage = message
            )
        }
    }

    /** Reset total: tutup sesi USB yang masih terbuka dan kembali ke keadaan awal. */
    fun reset() {
        closeSession()
        pendingDaBytes = null
        pendingPreloaderBytes = null
        _state.value = FlashToolState()
    }
}
