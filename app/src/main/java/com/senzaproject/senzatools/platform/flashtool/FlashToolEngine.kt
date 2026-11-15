package com.senzaproject.senzatools.platform.flashtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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

enum class FlashStage { IDLE, WAITING_DEVICE, PERMISSION_NEEDED, HANDSHAKING, IDENTIFIED, ERROR }

data class FlashToolState(
    val stage: FlashStage = FlashStage.IDLE,
    val deviceName: String? = null,
    val info: String? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null
)

/**
 * Tahap 1 Flash Tool: deteksi USB device MediaTek BROM/Preloader + identifikasi chip.
 * BELUM mengirim DA, BELUM melakukan flashing. Lihat BromProtocol.kt untuk detail protokol
 * dan batasan yang sudah diketahui.
 */
class FlashToolEngine(private val ctx: Context, private val log: OperationLog) {

    private val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(FlashToolState())
    val state: StateFlow<FlashToolState> = _state.asStateFlow()

    private val actionUsbPermission = "${ctx.packageName}.USB_PERMISSION_FLASHTOOL"
    private var receiverRegistered = false

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

    fun dispose() {
        if (receiverRegistered) {
            try { ctx.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
    }

    /** Pindai USB host yang tersambung sekarang dan cari device yang cocok pola MediaTek BROM/Preloader. */
    fun scanAndConnect() {
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
            val result = try {
                val conn = usbManager.openDevice(device)
                    ?: return@launch postError(device.deviceName, "open_device", "Gagal membuka koneksi USB ke device (openDevice null).")
                val intf = device.getInterface(0)
                try {
                    BromProtocol.identify(conn, intf)
                } finally {
                    conn.close()
                }
            } catch (t: Throwable) {
                BromProtocol.BromResult.Error("exception", t.message ?: t.toString())
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is BromProtocol.BromResult.Identified -> {
                        val msg = "HW code: 0x${result.hwCode.toString(16)} | HW sub-code: 0x${result.hwSubCode.toString(16)} | " +
                            "HW ver: 0x${result.hwVersion.toString(16)} | SW ver: 0x${result.swVersion.toString(16)}"
                        log.add("FlashTool", "BROM identify ${device.deviceName}", "OK: $msg")
                        _state.value = FlashToolState(
                            stage = FlashStage.IDENTIFIED,
                            deviceName = device.deviceName,
                            info = msg
                        )
                    }
                    is BromProtocol.BromResult.Error -> {
                        log.add("FlashTool", "BROM identify ${device.deviceName}", "ERROR[${result.stage}]: ${result.message}")
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

    private suspend fun postError(deviceName: String?, stage: String, message: String) {
        withContext(Dispatchers.Main) {
            log.add("FlashTool", "BROM identify $deviceName", "ERROR[$stage]: $message")
            _state.value = FlashToolState(stage = FlashStage.ERROR, deviceName = deviceName, errorStage = stage, errorMessage = message)
        }
    }

    fun reset() {
        _state.value = FlashToolState()
    }
}
