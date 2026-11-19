package com.senzaproject.senzatools.platform.romflash

import android.content.Context
import android.net.Uri
import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.platform.root.SafeAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Flash ROM (Custom ROM) langsung dari apk, TANPA masuk recovery manual — lewat root, bukan lewat
 * USB/BROM seperti Flash Tool. Jalur ini sepenuhnya terpisah dari FlashToolEngine.
 *
 * ATURAN KERAS YANG TIDAK BISA DILEWATI DARI UI MANA PUN (ditegakkan di kode, bukan cuma saran):
 *  - TIDAK PERNAH menulis ke partisi slot yang sedang aktif/di-boot. Semua flash partisi WAJIB ke
 *    slot sebaliknya. Ini yang membuat fitur ini aman walau flash-nya gagal total: slot lama yang
 *    sedang berjalan tidak pernah disentuh, jadi device tetap bisa di-boot ke sana kapan pun
 *    (lewat `bootctl set-active-boot-slot` balik, atau device A/B modern yang punya rollback
 *    otomatis di level bootloader kalau slot baru gagal boot berkali-kali — itu mekanisme asli
 *    Android, bukan sesuatu yang saya buat sendiri di sini).
 *
 * YANG TIDAK BISA DIJAMIN FITUR INI: "auto-fallback ke recovery kalau ROM baru gagal boot" itu
 * TIDAK diimplementasikan di sini sebagai kode aplikasi — karena kalau device gagal boot, aplikasi
 * ini sendiri tidak akan pernah jalan lagi untuk mendeteksi itu. Keamanannya murni berasal dari
 * fakta bahwa slot lama tidak pernah ditulis.
 *
 * Wipe data memakai mekanisme BCB (lihat BootloaderMessage.kt) — standar AOSP publik, bukan
 * device-specific, dan recovery yang menjalankan wipe-nya (bukan kode kita langsung format
 * filesystem) supaya parameter format selalu benar untuk device itu.
 */
class RomFlashEngine(private val ctx: Context, private val access: AccessManager) {

    enum class Stage { IDLE, DETECTING, SLOT_KNOWN, PICKING, VALIDATING, READY_TO_FLASH, FLASHING, FLASH_DONE, WIPING, ERROR }

    data class PartitionTarget(
        val baseName: String,       // nama tanpa akhiran slot, mis. "boot", "vendor_boot"
        val devicePath: String,     // path block device slot TIDAK aktif yang akan ditulis
        val deviceSizeBytes: Long
    )

    data class PendingFlash(
        val target: PartitionTarget,
        val imageUri: Uri,
        val imageSizeBytes: Long,
        val fits: Boolean
    )

    data class State(
        val stage: Stage = Stage.IDLE,
        val currentSlot: String? = null,   // "a" atau "b"
        val targetSlot: String? = null,    // kebalikan dari currentSlot
        val partitions: List<PartitionTarget> = emptyList(),
        val queuedFlashes: List<PendingFlash> = emptyList(), // sudah dikonfirmasi, menunggu dieksekusi
        val pendingConfirm: PendingFlash? = null,
        val errorMessage: String? = null,
        val log: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun appendLog(line: String) {
        _state.value = _state.value.copy(log = _state.value.log + line)
    }

    /** Baca slot aktif sekarang via getprop (read-only) dan tentukan slot target (kebalikannya). */
    suspend fun detectSlot() {
        _state.value = State(stage = Stage.DETECTING)
        val r = access.run(SafeAction.GetProp("ro.boot.slot_suffix"), asRoot = true)
        val suffix = r.output.trim().removePrefix("_").lowercase()
        if (suffix != "a" && suffix != "b") {
            _state.value = State(
                stage = Stage.ERROR,
                errorMessage = "Tidak bisa baca slot aktif dari ro.boot.slot_suffix (hasil: \"${r.output.trim()}\"). " +
                    "Device ini mungkin bukan A/B, atau property-nya berbeda nama."
            )
            return
        }
        val target = if (suffix == "a") "b" else "a"
        _state.value = _state.value.copy(stage = Stage.SLOT_KNOWN, currentSlot = suffix, targetSlot = target)
        appendLog("Slot aktif: _$suffix. Target flash: _$target (slot tidak aktif).")
        loadPartitionList(target)
    }

    /** Daftar partisi slot target beserta ukuran block device-nya (read-only). */
    private suspend fun loadPartitionList(targetSlot: String) {
        _state.value = _state.value.copy(stage = Stage.PICKING)
        val listing = access.run(SafeAction.PartitionsByName, asRoot = true).output
        val names = Regex("""(\S+)\s*$""", RegexOption.MULTILINE)
            .findAll(listing)
            .map { it.groupValues[1].substringAfterLast('/') }
            .filter { it.endsWith("_$targetSlot") }
            .toList()
        val targets = mutableListOf<PartitionTarget>()
        for (name in names) {
            val path = "/dev/block/by-name/$name"
            val sizeOut = access.run(
                SafeAction.ReadScript("blockdev --getsize64 '$path' 2>/dev/null", "Ukuran partisi $name"),
                asRoot = true
            ).output.trim()
            val size = sizeOut.toLongOrNull() ?: continue
            targets.add(PartitionTarget(name.removeSuffix("_$targetSlot"), path, size))
        }
        _state.value = _state.value.copy(stage = Stage.SLOT_KNOWN, partitions = targets)
        appendLog("${targets.size} partisi ditemukan di slot _$targetSlot.")
    }

    /** Pilih gambar untuk satu partisi target. Hanya memvalidasi ukuran — belum menulis apa pun. */
    suspend fun pickImage(target: PartitionTarget, uri: Uri) {
        val size = try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (t: Throwable) { -1L }
        if (size < 0) {
            _state.value = _state.value.copy(stage = Stage.ERROR, errorMessage = "Gagal membaca ukuran file gambar.")
            return
        }
        _state.value = _state.value.copy(
            stage = Stage.VALIDATING,
            pendingConfirm = PendingFlash(target, uri, size, fits = size <= target.deviceSizeBytes)
        )
    }

    fun cancelPending() {
        _state.value = _state.value.copy(pendingConfirm = null, stage = Stage.SLOT_KNOWN)
    }

    /** Masukkan satu flash yang sudah dikonfirmasi ke antrean (belum dieksekusi). */
    fun confirmQueue() {
        val pending = _state.value.pendingConfirm ?: return
        if (!pending.fits) return
        _state.value = _state.value.copy(
            queuedFlashes = _state.value.queuedFlashes + pending,
            pendingConfirm = null,
            stage = Stage.READY_TO_FLASH
        )
    }

    fun removeFromQueue(target: PartitionTarget) {
        _state.value = _state.value.copy(queuedFlashes = _state.value.queuedFlashes.filterNot { it.target == target })
    }

    /**
     * Eksekusi semua flash yang sudah diantre, ke slot TIDAK AKTIF SAJA (dicek ulang di sini,
     * bukan cuma percaya UI). Setelah semua sukses, pindahkan slot aktif lewat bootctl.
     * BELUM reboot dan BELUM wipe data — itu langkah terpisah ([wipeDataAndReboot]) yang wajib
     * dikonfirmasi lagi secara eksplisit karena irreversible.
     */
    suspend fun flashQueueToInactiveSlot() {
        val st = _state.value
        val target = st.targetSlot
        if (target == null || st.currentSlot == null || st.queuedFlashes.isEmpty()) {
            _state.value = st.copy(stage = Stage.ERROR, errorMessage = "Tidak ada yang diantre untuk di-flash, atau slot belum terdeteksi.")
            return
        }
        _state.value = st.copy(stage = Stage.FLASHING)
        for (item in st.queuedFlashes) {
            // Penjaga keras: tolak kalau path tidak diakhiri slot target (bukan slot aktif).
            if (!item.target.devicePath.endsWith("_$target")) {
                _state.value = _state.value.copy(
                    stage = Stage.ERROR,
                    errorMessage = "DIBATALKAN demi keamanan: target ${item.target.devicePath} tidak cocok pola slot tidak aktif (_$target)."
                )
                return
            }
            val cacheFile = File(ctx.cacheDir, "romflash_${item.target.baseName}.img")
            try {
                ctx.contentResolver.openInputStream(item.imageUri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output, bufferSize = 4 * 1024 * 1024) }
                } ?: throw IllegalStateException("Tidak bisa membuka file gambar")
            } catch (t: Throwable) {
                _state.value = _state.value.copy(stage = Stage.ERROR, errorMessage = "Gagal menyalin gambar ${item.target.baseName}: ${t.message}")
                return
            }
            val r = access.runRootEdit(
                "dd if='${cacheFile.absolutePath}' of='${item.target.devicePath}' bs=4M 2>&1",
                "Flash ${item.target.baseName} ke slot _$target",
                timeoutMs = 300_000
            )
            cacheFile.delete()
            if (!r.ok) {
                _state.value = _state.value.copy(
                    stage = Stage.ERROR,
                    errorMessage = "Flash ${item.target.baseName} gagal (exit=${r.exitCode}): ${r.output.take(500)}"
                )
                return
            }
            appendLog("OK: ${item.target.baseName} -> ${item.target.devicePath}")
        }

        val slotNumber = if (target == "a") "0" else "1"
        val bc = access.runRootEdit("bootctl set-active-boot-slot $slotNumber", "Set slot aktif ke _$target")
        if (!bc.ok) {
            _state.value = _state.value.copy(
                stage = Stage.ERROR,
                errorMessage = "Semua partisi berhasil di-flash, TAPI gagal pindah slot aktif (bootctl exit=${bc.exitCode}). " +
                    "Slot lama (_${st.currentSlot}) masih aktif dan aman — device tidak dalam bahaya, tapi ROM baru belum akan dipakai sampai ini diperbaiki."
            )
            return
        }
        appendLog("Slot aktif dipindah ke _$target.")
        _state.value = _state.value.copy(stage = Stage.FLASH_DONE, queuedFlashes = emptyList())
    }

    /**
     * Wipe data via BCB lalu reboot ke recovery — recovery yang mengeksekusi wipe (bukan kode kita
     * langsung format filesystem), lalu recovery reboot balik ke system secara otomatis setelah
     * selesai (perilaku standar AOSP). Ini IRREVERSIBLE — pemanggil WAJIB sudah minta konfirmasi
     * eksplisit dari pengguna sebelum memanggil fungsi ini.
     */
    suspend fun wipeDataAndReboot() {
        if (_state.value.stage != Stage.FLASH_DONE) {
            _state.value = _state.value.copy(stage = Stage.ERROR, errorMessage = "Flash partisi belum selesai — wipe data dibatalkan demi keamanan urutan.")
            return
        }
        _state.value = _state.value.copy(stage = Stage.WIPING)
        val bcbFile = File(ctx.cacheDir, "bcb_wipe_data.bin")
        bcbFile.writeBytes(BootloaderMessage.wipeDataMessage())
        val misc = "/dev/block/by-name/misc"
        val r = access.runRootEdit("dd if='${bcbFile.absolutePath}' of='$misc' bs=${BootloaderMessage.SIZE} count=1 2>&1", "Tulis BCB wipe_data ke misc")
        bcbFile.delete()
        if (!r.ok) {
            _state.value = _state.value.copy(
                stage = Stage.ERROR,
                errorMessage = "Gagal menulis BCB ke partisi misc (exit=${r.exitCode}): ${r.output.take(500)}. " +
                    "Flash partisi SUDAH selesai sebelumnya; cuma pemicu wipe+reboot ini yang gagal — bisa dicoba ulang."
            )
            return
        }
        appendLog("BCB wipe_data tertulis. Reboot ke recovery...")
        access.runRootEdit("reboot recovery", "Reboot ke recovery untuk eksekusi wipe_data")
        // Setelah baris di atas, device akan reboot — aplikasi ini tidak akan sempat update state lagi.
    }

    fun reset() {
        _state.value = State()
    }
}
