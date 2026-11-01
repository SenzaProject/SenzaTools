package com.senzaproject.senzatools.platform.root

import android.content.Context
import com.senzaproject.senzatools.platform.AccessManager
import java.io.File

/**
 * Fake Device: menyamarkan nama/merek/model perangkat dan nama SoC supaya game menampilkan opsi grafis
 * (kualitas, FPS) yang biasanya hanya dibuka untuk perangkat tertentu.
 *
 * Cara kerja (butuh root + Magisk/KernelSU/APatch karena memakai `resetprop`):
 *  1. Nilai asli tiap properti disimpan dulu di /data/adb/senza_fakedevice/orig.conf (sekali per properti).
 *  2. Properti ro.product.* (model, brand, manufacturer, marketname) di semua partisi + ro.soc.* ditimpa.
 *  3. Hasilnya dibaca ulang lewat getprop dan dibandingkan; yang gagal dilaporkan, bukan diasumsikan berhasil.
 *  4. Bila "tetap setelah reboot" aktif, dipasang module systemless (system.prop + post-fs-data.sh).
 *
 * Yang SENGAJA tidak diubah: ro.board.platform, ro.hardware*, ro.product.device/name, fingerprint. Properti itu dipakai
 * sistem untuk memuat driver GPU/HAL; mengubahnya bisa membuat grafis, kamera, atau boot rusak.
 *
 * Aplikasi membaca Build.MODEL sekali saat prosesnya (dan zygote) dimulai, jadi perubahan baru terlihat penuh
 * setelah restart perangkat.
 */
class FakeDeviceEngine(private val ctx: Context, private val access: AccessManager) {

    enum class Vendor(val label: String, val manufacturer: String) {
        QUALCOMM("Snapdragon", "QTI"),
        MEDIATEK("MediaTek", "Mediatek")
    }

    data class Device(
        val id: String,
        val marketName: String,
        val brand: String,
        val manufacturer: String,
        val model: String
    )

    data class Soc(val id: String, val name: String, val vendor: Vendor, val code: String, val note: String)

    data class Status(val deviceId: String?, val socId: String?, val bootInstalled: Boolean, val resetprop: String?)

    data class Outcome(val ok: Boolean, val message: String, val failures: List<String>)

    companion object {
        /** Perangkat kelas atas dari berbagai merek. Nilai model = Build.MODEL asli perangkat tersebut. */
        val DEVICES = listOf(
            Device("s26u", "Samsung Galaxy S26 Ultra", "samsung", "samsung", "SM-S948B"),
            Device("rog9p", "ASUS ROG Phone 9 Pro", "asus", "asus", "ASUS_AI2501"),
            Device("rm11p", "RedMagic 11 Pro", "nubia", "nubia", "NX809J"),
            Device("op13", "OnePlus 13", "OnePlus", "OnePlus", "CPH2655"),
            Device("mi15u", "Xiaomi 15 Ultra", "Xiaomi", "Xiaomi", "25010PN30G"),
            Device("x500pm", "vivo X500 Pro Max", "vivo", "vivo", "V2610"),
            Device("px10pxl", "Google Pixel 10 Pro XL", "google", "Google", "Pixel 10 Pro XL"),
            Device("fx8p", "OPPO Find X8 Pro", "OPPO", "OPPO", "CPH2659"),
            Device("mg7p", "HONOR Magic7 Pro", "HONOR", "HONOR", "PTP-AN10"),
            Device("gt7p", "realme GT 7 Pro", "realme", "realme", "RMX5010")
        )

        /** SoC kelas atas yang beredar saat ini (kode = nilai ro.soc.model / nomor model chip). */
        val SOCS = listOf(
            Soc("sd8ee6", "Snapdragon 8 Elite Extreme Gen 6", Vendor.QUALCOMM, "SM8975", "2nm, rilis September 2026"),
            Soc("sd8e6", "Snapdragon 8 Elite Gen 6", Vendor.QUALCOMM, "SM8950", "2nm, rilis September 2026"),
            Soc("sd8e5", "Snapdragon 8 Elite Gen 5", Vendor.QUALCOMM, "SM8850", "3nm, rilis September 2025"),
            Soc("sd8e", "Snapdragon 8 Elite", Vendor.QUALCOMM, "SM8750", "3nm, rilis Oktober 2024"),
            Soc("d9600p", "Dimensity 9600 Pro", Vendor.MEDIATEK, "MT6995", "2nm, rilis September 2026"),
            Soc("d9500", "Dimensity 9500", Vendor.MEDIATEK, "MT6993", "3nm, rilis September 2025"),
            Soc("d9400p", "Dimensity 9400+", Vendor.MEDIATEK, "MT6991", "3nm, rilis 2025"),
            Soc("d9300p", "Dimensity 9300+", Vendor.MEDIATEK, "MT6989", "4nm, rilis 2024")
        )

        private val PARTS = listOf("", "system.", "system_ext.", "vendor.", "odm.", "product.", "bootimage.")
        private val FIELDS = listOf("model", "brand", "manufacturer", "marketname")
        private val SOC_KEYS = listOf("ro.soc.model", "ro.soc.manufacturer")

        /** Semua properti yang boleh disentuh fitur ini (urutan tetap). */
        val ALL_KEYS: List<String> = PARTS.flatMap { p -> FIELDS.map { f -> "ro.product.$p$f" } } + SOC_KEYS

        private val SAFE_VALUE = Regex("^[A-Za-z0-9 ._+-]{1,48}$")
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{0,24}$")

        fun device(id: String?): Device? = DEVICES.firstOrNull { it.id == id }
        fun soc(id: String?): Soc? = SOCS.firstOrNull { it.id == id }

        /** Daftar properti yang akan ditulis untuk pilihan ini (kosong bila keduanya null). */
        fun propsFor(device: Device?, soc: Soc?): Map<String, String> {
            val m = linkedMapOf<String, String>()
            if (device != null) {
                for (p in PARTS) {
                    m["ro.product.${p}model"] = device.model
                    m["ro.product.${p}brand"] = device.brand
                    m["ro.product.${p}manufacturer"] = device.manufacturer
                    m["ro.product.${p}marketname"] = device.marketName
                }
            }
            if (soc != null) {
                m["ro.soc.model"] = soc.code
                m["ro.soc.manufacturer"] = soc.vendor.manufacturer
            }
            m.forEach { (k, v) ->
                require(k in ALL_KEYS) { "Properti tidak dikenal: $k" }
                require(SAFE_VALUE.matches(v)) { "Nilai tidak aman untuk $k: $v" }
            }
            return m
        }
    }

    private fun buildScript(mode: String, props: Map<String, String>, persist: Boolean, deviceId: String?, socId: String?): String {
        require(mode == "apply" || mode == "restore" || mode == "status") { "Mode tidak valid: $mode" }
        require(SAFE_ID.matches(deviceId.orEmpty()) && SAFE_ID.matches(socId.orEmpty())) { "ID tidak valid" }
        val tpl = ctx.assets.open("senza_fakedevice.sh").bufferedReader().use { it.readText() }
        val sb = StringBuilder()
        if (mode != "status") {
            for (k in ALL_KEYS) {
                val v = props[k]
                if (mode == "apply" && v != null) sb.append("setp '").append(k).append("' '").append(v).append("'\n")
                else sb.append("restorep '").append(k).append("'\n")
            }
        }
        val values = props.entries.joinToString("\n") { "${it.key}=${it.value}" }
        return tpl
            .replace("#MODE#", mode)
            .replace("#PERSIST#", if (persist) "1" else "0")
            .replace("#DEVICE_ID#", deviceId.orEmpty())
            .replace("#SOC_ID#", socId.orEmpty())
            .replace("#PROPS#", sb.toString().trimEnd())
            .replace("#VALUES#", values)
    }

    private suspend fun runScript(script: String, label: String): com.senzaproject.senzatools.utils.ShellResult {
        val tmp = File(ctx.cacheDir, "senza_fakedevice.sh.tmp")
        return try {
            tmp.writeText(script)
            access.runRootEdit("sh '${tmp.absolutePath}'", label, 40_000)
        } finally {
            tmp.delete()
        }
    }

    /** Pilihan yang sedang aktif (dibaca dari berkas status di perangkat), module boot, dan lokasi resetprop. */
    suspend fun status(): Status {
        val r = runScript(buildScript("status", emptyMap(), false, null, null), "cek status fake device")
        var dev: String? = null
        var soc: String? = null
        var boot = false
        var rp: String? = null
        r.output.lines().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("device=") -> dev = t.removePrefix("device=").ifBlank { null }
                t.startsWith("soc=") -> soc = t.removePrefix("soc=").ifBlank { null }
                t == "boot=1" -> boot = true
                t.startsWith("rp=") -> rp = t.removePrefix("rp=").ifBlank { null }
            }
        }
        return Status(dev, soc, boot, rp)
    }

    /** Terapkan pilihan (salah satu boleh null = tetap asli). Hasil diperiksa ulang oleh skrip. */
    suspend fun apply(device: Device?, soc: Soc?, persist: Boolean): Outcome {
        if (device == null && soc == null) return Outcome(false, "Pilih device atau SoC dulu, atau gunakan Kembalikan ke asli.", emptyList())
        val props = propsFor(device, soc)
        val script = buildScript("apply", props, persist, device?.id, soc?.id)
        val r = runScript(script, "terapkan fake device")
        return parse(r.output, r.exitCode, props.size, persist)
    }

    /** Kembalikan semua properti ke nilai asli dan hapus module boot. */
    suspend fun restore(): Outcome {
        val r = runScript(buildScript("restore", emptyMap(), false, null, null), "pulihkan device asli")
        val fails = r.output.lines().map { it.trim() }.filter { it.startsWith("GAGAL") }
        return if (r.ok && fails.isEmpty()) Outcome(true, "Dikembalikan ke nilai asli. Restart perangkat agar semua aplikasi memakai nilai asli.", emptyList())
        else Outcome(false, "Gagal memulihkan: ${r.output.trim().take(160)}", fails)
    }

    private fun parse(output: String, exitCode: Int, total: Int, persist: Boolean): Outcome {
        val lines = output.lines().map { it.trim() }
        val fails = lines.filter { it.startsWith("GAGAL") }
        val okCount = lines.count { it.startsWith("OK ro.") }
        if (exitCode != 0 || (fails.isEmpty() && okCount == 0)) {
            val why = fails.firstOrNull() ?: output.trim().take(160).ifBlank { "tidak ada keluaran" }
            return Outcome(false, "Gagal menerapkan: $why", fails)
        }
        val bootNote = when {
            !persist -> " Tidak permanen: hilang setelah restart."
            lines.any { it == "OK module boot" } -> " Module boot terpasang, tetap aktif setelah restart."
            else -> " Module boot gagal dipasang, hilang setelah restart."
        }
        val msg = if (fails.isEmpty()) "Berhasil: $okCount dari $total properti diterapkan." else "Sebagian berhasil: $okCount dari $total properti, ${fails.size} gagal."
        return Outcome(fails.isEmpty(), msg + bootNote + " Restart perangkat agar game membaca nilai baru.", fails)
    }
}
