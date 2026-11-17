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
import android.provider.OpenableColumns
import com.senzaproject.senzatools.data.OperationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    EMI_SENDING, EMI_ACCEPTED, DA2_SENDING, DA2_READY, GPT_READING, GPT_READY, ERROR
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
    val folderScanInfo: String? = null,
    val gptEntries: List<GptParser.Entry> = emptyList()
)

/**
 * Pilihan file & status sesi (DA, AUTH, preloader, scatter). Sengaja dipisah dari [FlashToolState]
 * supaya pilihan file tidak ikut terhapus setiap kali state USB di-reset.
 */
data class FlashFiles(
    val daName: String? = null,
    val daInfo: String? = null,
    val authName: String? = null,
    val preloaderName: String? = null,
    val scatterName: String? = null,
    val scatter: ScatterFile.Parsed? = null,
    val selected: Set<String> = emptySet(),
    val running: Boolean = false,
    val waiting: Boolean = false,
    val preview: String? = null,
    val note: String? = null
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

    private val _files = MutableStateFlow(FlashFiles())
    val files: StateFlow<FlashFiles> = _files.asStateFlow()

    @Volatile private var selectedDaBytes: ByteArray? = null
    @Volatile private var selectedAuthBytes: ByteArray? = null
    @Volatile private var selectedPreloaderBytes: ByteArray? = null
    private var pollJob: Job? = null

    init {
        // Penggerak otomatis: tiap kali tahap berubah, lanjutkan langkah berikutnya.
        scope.launch {
            _state.map { it.stage }.distinctUntilChanged().collect { onStage(it) }
        }
    }

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
        val allDevices = usbManager.deviceList.values.toList()
        val candidates = allDevices.filter { it.vendorId == MTK_VID }
        if (candidates.isEmpty()) {
            val hasUsbHost = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)
            val message = when {
                !hasUsbHost ->
                    "HP yang menjalankan Senza Tools ini TIDAK mendukung mode USB Host (OTG) menurut sistem Android-nya sendiri. " +
                        "Tanpa fitur ini, HP tidak akan pernah bisa membaca device lain lewat USB, apa pun yang dilakukan di sisi device target."
                allDevices.isEmpty() ->
                    "Sistem Android di HP ini sama sekali TIDAK melihat ada USB device tersambung (daftar device kosong total). " +
                        "Ini biasanya berarti: (1) kabel yang dipakai cuma kabel charge-only (tanpa jalur data), " +
                        "(2) belum pakai adaptor OTG kalau port HP ini USB-C ke USB-C tanpa dukungan host langsung, atau " +
                        "(3) device target belum benar-benar masuk mode BROM (preloader-nya mungkin sudah lanjut booting normal)."
                else ->
                    "Ada ${allDevices.size} USB device terlihat, tapi tidak ada yang Vendor ID-nya MediaTek (0x0E8D). " +
                        "Yang terdeteksi: " + allDevices.joinToString("; ") {
                            "VID 0x${it.vendorId.toString(16)} PID 0x${it.productId.toString(16)} (${it.deviceName})"
                        } + ". Device target kemungkinan belum masuk mode BROM, atau masuk ke mode USB lain (mis. mode charging/MTP biasa)."
            }
            _state.value = FlashToolState(
                stage = FlashStage.ERROR,
                errorStage = "scan",
                errorMessage = message
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
                pendingDaBytes = bytes
                withContext(Dispatchers.Main) {
                    log.add("FlashTool", "Parse DA", "OK: $summary")
                    _state.value = _state.value.copy(stage = FlashStage.DA_MATCHED, daSummary = summary)
                }
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

    /**
     * Tahap 5: upload DA2 ke alamat yang ditentukan file DA (biasanya DRAM 0x40000000), lalu BOOT_TO.
     * Lihat catatan jujur di XFlashProtocol.uploadDa2AndBootTo — ini bagian paling tidak pasti sejauh ini,
     * termasuk kemungkinan device reconnect USB di titik ini sehingga sesi lama tidak valid lagi.
     */
    fun uploadDa2() {
        val sess = session
        val entry = sess?.matchedEntry
        val daBytes = pendingDaBytes
        if (sess == null || entry == null || daBytes == null || _state.value.stage != FlashStage.EMI_ACCEPTED) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "da2_not_ready",
                errorMessage = "Kalibrasi EMI belum selesai (EMI_ACCEPTED). Selesaikan Tahap 4 dulu."
            )
            return
        }
        val region = DaFile.da2Of(entry)
        if (region == null) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "da2_no_region",
                errorMessage = "File DA ini tidak punya region DA2 (index 2) — tidak bisa lanjut upload stage 2."
            )
            return
        }
        _state.value = _state.value.copy(stage = FlashStage.DA2_SENDING, errorStage = null, errorMessage = null)
        scope.launch {
            val fileEnd = minOf(region.fileOffset + region.totalLength, daBytes.size)
            if (region.fileOffset < 0 || region.fileOffset >= daBytes.size || fileEnd <= region.fileOffset) {
                postDaError(sess, "da2_region_bounds", "Region DA2 di luar batas file DA (offset/panjang tidak valid).")
                return@launch
            }
            val payload = daBytes.copyOfRange(region.fileOffset, fileEnd)
            val result = try {
                XFlashProtocol.uploadDa2AndBootTo(sess.conn, sess.intf, payload, region.loadAddress)
            } catch (t: Throwable) {
                XFlashProtocol.XResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is XFlashProtocol.XResult.Ok -> {
                        log.add("FlashTool", "Upload DA2 + BOOT_TO", "OK")
                        _state.value = _state.value.copy(
                            stage = FlashStage.DA2_READY,
                            info = "DA2 diterima & BOOT_TO diterima. BELUM ada baca/tulis partisi — dan kalau langkah " +
                                "berikutnya gagal, kemungkinan device reconnect USB, coba pindai ulang dari Tahap 1."
                        )
                    }
                    is XFlashProtocol.XResult.Error -> {
                        log.add("FlashTool", "Upload DA2 + BOOT_TO", "ERROR[${result.stage}]: ${result.message}")
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

    /**
     * Tahap 6: baca sektor awal storage (cukup untuk GPT: MBR + header + tabel entri) dan parse
     * jadi daftar partisi. Murni baca — tidak menulis apa pun ke device.
     */
    fun readPartitionTable() {
        val sess = session
        if (sess == null || _state.value.stage != FlashStage.DA2_READY) {
            _state.value = _state.value.copy(
                stage = FlashStage.ERROR,
                errorStage = "gpt_not_ready",
                errorMessage = "DA2 belum siap (DA2_READY). Selesaikan Tahap 5 dulu."
            )
            return
        }
        _state.value = _state.value.copy(stage = FlashStage.GPT_READING, errorStage = null, errorMessage = null)
        scope.launch {
            // 34 sektor x 512 byte = MBR (1) + GPT header (1) + tabel entri standar (32, cukup untuk 128 entri x 128 byte).
            val sectorsToRead = 34
            val sizeBytes = sectorsToRead.toLong() * 512L
            val result = try {
                XFlashProtocol.readData(sess.conn, sess.intf, XFlashProtocol.STORAGE_EMMC, XFlashProtocol.PARTTYPE_USER, 0L, sizeBytes)
            } catch (t: Throwable) {
                XFlashProtocol.XResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is XFlashProtocol.XResult.Ok -> {
                        when (val parsed = GptParser.parse(result.raw)) {
                            is GptParser.Result.Ok -> {
                                log.add("FlashTool", "Baca GPT", "OK: ${parsed.entries.size} partisi, ${parsed.headerInfo}")
                                _state.value = _state.value.copy(
                                    stage = FlashStage.GPT_READY,
                                    gptEntries = parsed.entries,
                                    info = "${parsed.entries.size} partisi terbaca dari GPT (${parsed.headerInfo})."
                                )
                            }
                            is GptParser.Result.Error -> {
                                log.add("FlashTool", "Baca GPT", "ERROR[gpt_parse]: ${parsed.message}")
                                _state.value = _state.value.copy(
                                    stage = FlashStage.ERROR,
                                    errorStage = "gpt_parse",
                                    errorMessage = parsed.message
                                )
                            }
                        }
                    }
                    is XFlashProtocol.XResult.Error -> {
                        log.add("FlashTool", "Baca GPT", "ERROR[${result.stage}]: ${result.message}")
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


    // ------------------------------------------------------------------ Pilihan file & sesi

    private fun displayName(uri: Uri): String {
        val fromProvider = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) { null }
        return fromProvider ?: uri.lastPathSegment ?: "file"
    }

    private fun readBytes(uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Throwable) { null }

    /** Pilih file DA. Divalidasi dulu; kalau device sudah teridentifikasi, langsung dicocokkan. */
    fun selectDa(uri: Uri) {
        scope.launch {
            val name = displayName(uri)
            val bytes = readBytes(uri)
            if (bytes == null) {
                _files.update { it.copy(note = "Gagal membaca file DA \"$name\".") }
                return@launch
            }
            when (val p = DaFile.parse(bytes)) {
                is DaFile.ParseResult.Error ->
                    _files.update { it.copy(note = "\"$name\" bukan file DA yang valid: ${p.message}") }
                is DaFile.ParseResult.Ok -> {
                    selectedDaBytes = bytes
                    val chips = p.entries.joinToString(", ") { "0x${it.hwCode.toString(16)}" }
                    _files.update { it.copy(daName = name, daInfo = "HW code didukung: $chips", note = null) }
                    val sess = session
                    if (sess != null && _state.value.stage == FlashStage.IDENTIFIED) {
                        _state.value = _state.value.copy(stage = FlashStage.DA_PARSING, errorStage = null, errorMessage = null)
                        processDaBytes(sess, bytes, "pilihan manual: $name")
                    }
                }
            }
        }
    }

    fun clearDa() {
        selectedDaBytes = null
        _files.update { it.copy(daName = null, daInfo = null) }
    }

    /** Pilih file AUTH (opsional). Disimpan untuk sesi; pengiriman AUTH belum diimplementasikan di protokol. */
    fun selectAuth(uri: Uri) {
        scope.launch {
            val name = displayName(uri)
            val bytes = readBytes(uri)
            if (bytes == null || bytes.isEmpty()) {
                _files.update { it.copy(note = "Gagal membaca file AUTH \"$name\".") }
                return@launch
            }
            selectedAuthBytes = bytes
            _files.update { it.copy(authName = name, note = null) }
        }
    }

    fun clearAuth() {
        selectedAuthBytes = null
        _files.update { it.copy(authName = null) }
    }

    /** Pilih preloader (opsional). Dipakai setelah DA1 + kanal XFlash siap untuk kirim EMI. */
    fun selectPreloader(uri: Uri) {
        scope.launch {
            val name = displayName(uri)
            val bytes = readBytes(uri)
            if (bytes == null || bytes.isEmpty()) {
                _files.update { it.copy(note = "Gagal membaca preloader \"$name\".") }
                return@launch
            }
            selectedPreloaderBytes = bytes
            _files.update { it.copy(preloaderName = name, note = null) }
        }
    }

    fun clearPreloader() {
        selectedPreloaderBytes = null
        _files.update { it.copy(preloaderName = null) }
    }

    /** Kirim EMI dari preloader yang sudah dipilih lewat tombol "Pilih Preloader" (butuh XFLASH_READY). */
    fun sendSelectedPreloader() {
        val bytes = selectedPreloaderBytes
        if (bytes == null) {
            _files.update { it.copy(note = "Belum ada preloader yang dipilih.") }
            return
        }
        val sess = requireSessionForEmi() ?: return
        _state.value = _state.value.copy(stage = FlashStage.EMI_SENDING, errorStage = null, errorMessage = null)
        scope.launch { processPreloaderBytes(sess, bytes) }
    }

    /** Pilih file scatter & parse. Tidak butuh HP tersambung. */
    fun selectScatter(uri: Uri) {
        scope.launch {
            val name = displayName(uri)
            val bytes = readBytes(uri)
            if (bytes == null) {
                _files.update { it.copy(note = "Gagal membaca scatter \"$name\".") }
                return@launch
            }
            when (val r = ScatterFile.parse(String(bytes, Charsets.UTF_8))) {
                is ScatterFile.Result.Error -> _files.update { it.copy(note = r.message) }
                is ScatterFile.Result.Ok -> {
                    val all = r.scatter.partitions.filter { it.flashable }.map { it.name }.toSet()
                    log.add("FlashTool", "Pilih scatter", "OK: $name (${r.scatter.partitions.size} partisi, platform ${r.scatter.platform})")
                    _files.update {
                        it.copy(scatterName = name, scatter = r.scatter, selected = all, preview = null, note = null)
                    }
                }
            }
        }
    }

    fun clearScatter() {
        _files.update { it.copy(scatterName = null, scatter = null, selected = emptySet(), preview = null) }
    }

    fun togglePartition(name: String) {
        _files.update { f ->
            f.copy(selected = if (name in f.selected) f.selected - name else f.selected + name, preview = null)
        }
    }

    fun selectAllPartitions(all: Boolean) {
        _files.update { f ->
            val names = f.scatter?.partitions?.filter { it.flashable }?.map { it.name }?.toSet() ?: emptySet()
            f.copy(selected = if (all) names else emptySet(), preview = null)
        }
    }

    /** TINJAU: ringkasan partisi terpilih dari scatter. Murni baca — tidak ada yang ditulis ke device. */
    fun tinjau() {
        val f = _files.value
        val sc = f.scatter
        if (sc == null) {
            _files.update { it.copy(note = "Pilih file scatter dulu.") }
            return
        }
        val chosen = sc.partitions.filter { it.name in f.selected }
        val text = buildString {
            appendLine("Platform: ${sc.platform.ifBlank { "-" }} | Storage: ${sc.storage.ifBlank { "-" }}")
            appendLine("Partisi terpilih: ${chosen.size} dari ${sc.partitions.count { it.flashable }}")
            var total = 0L
            chosen.forEach { p ->
                total += p.size ?: 0L
                val start = p.linearStart?.let { "0x" + it.toString(16) } ?: "-"
                appendLine("• ${p.name}  [${p.fileName}]  @$start  ${ScatterFile.formatSize(p.size)}")
            }
            append("Total ukuran: ${ScatterFile.formatSize(total)}")
        }
        _files.update { it.copy(preview = text, note = null) }
    }

    private fun hasMtkDevice(): Boolean = usbManager.deviceList.values.any { it.vendorId == MTK_VID }

    /**
     * MULAI / START: jalankan sesi otomatis (identifikasi → cocokkan & kirim DA → kanal XFlash → EMI
     * kalau preloader dipilih). Kalau HP target belum tersambung, tunggu sampai muncul (maks 2 menit).
     * Tidak ada partisi yang ditulis.
     */
    fun start() {
        pollJob?.cancel()
        _files.update { it.copy(running = true, waiting = false, note = null) }
        if (hasMtkDevice()) {
            scanAndConnect()
            return
        }
        _files.update { it.copy(waiting = true) }
        pollJob = scope.launch {
            val deadline = System.currentTimeMillis() + 120_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (hasMtkDevice()) {
                    _files.update { it.copy(waiting = false) }
                    withContext(Dispatchers.Main) { scanAndConnect() }
                    return@launch
                }
                delay(500)
            }
            _files.update {
                it.copy(running = false, waiting = false, note = "Waktu tunggu 2 menit habis — device BROM tidak muncul. Cek kabel dan kombinasi tombol, lalu coba lagi.")
            }
        }
    }

    /** BERHENTI: batalkan penantian, tutup sesi USB, kembali ke keadaan awal (pilihan file tetap). */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _files.update { it.copy(running = false, waiting = false) }
        reset()
    }

    // Peta HW code → platform, hanya yang sudah terverifikasi di katalog bawaan.
    private val knownPlatforms = mapOf(0x1208 to "MT6789")

    private suspend fun onStage(stage: FlashStage) {
        val running = _files.value.running
        val sess = session
        when (stage) {
            FlashStage.IDENTIFIED -> {
                if (sess == null) return
                val sc = _files.value.scatter
                if (running && sc != null && sc.platform.isNotBlank()) {
                    val known = knownPlatforms[sess.hwCode]
                    val note = when {
                        known == null -> "Scatter: ${sc.platform}. HW code 0x${sess.hwCode.toString(16)} belum ada di daftar platform — tidak bisa dicek otomatis."
                        known.equals(sc.platform, ignoreCase = true) -> null
                        else -> "PERINGATAN: scatter untuk ${sc.platform}, tapi chip device ($known, HW 0x${sess.hwCode.toString(16)}) berbeda."
                    }
                    if (note != null) _files.update { it.copy(note = note) }
                }
                val bytes = selectedDaBytes
                if (bytes != null) {
                    _state.value = _state.value.copy(stage = FlashStage.DA_PARSING, errorStage = null, errorMessage = null)
                    processDaBytes(sess, bytes, "pilihan manual: ${_files.value.daName ?: "-"}")
                } else if (running) {
                    val entry = _state.value.catalogMatches.firstOrNull()
                    if (entry != null) {
                        useCatalogDa(entry)
                    } else {
                        _files.update { it.copy(running = false, note = "Pilih file DA dulu — tidak ada DA bawaan yang cocok untuk chip ini.") }
                    }
                }
            }
            FlashStage.DA_MATCHED -> if (running) sendAndRunDa()
            FlashStage.DA_READY -> if (running) xflashHandshake()
            FlashStage.XFLASH_READY -> if (running) {
                val bytes = selectedPreloaderBytes
                if (bytes != null && sess != null) {
                    _state.value = _state.value.copy(stage = FlashStage.EMI_SENDING, errorStage = null, errorMessage = null)
                    processPreloaderBytes(sess, bytes)
                } else {
                    _files.update { it.copy(running = false, note = "DA hidup dan kanal XFlash siap. Belum ada preloader dipilih, jadi kalibrasi EMI dilewati.") }
                }
            }
            FlashStage.EMI_ACCEPTED -> if (running) uploadDa2()
            FlashStage.DA2_READY -> if (running) readPartitionTable()
            FlashStage.GPT_READY -> if (running) {
                _files.update {
                    it.copy(
                        running = false,
                        note = "Sesi siap: DA hidup, kanal XFlash terbuka, EMI diterima, DA2 ter-upload, GPT terbaca " +
                            "(${_state.value.gptEntries.size} partisi). Belum ada tulis ke partisi mana pun."
                    )
                }
            }
            FlashStage.ERROR -> if (running || _files.value.waiting) {
                _files.update { it.copy(running = false, waiting = false) }
            }
            else -> {}
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
