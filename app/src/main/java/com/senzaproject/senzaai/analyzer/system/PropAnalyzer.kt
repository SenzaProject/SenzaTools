package com.senzaproject.senzaai.analyzer.system

import com.senzaproject.senzaai.domain.model.Certainty
import com.senzaproject.senzaai.domain.model.Severity

data class PropLine(val lineNo: Int, val key: String, val value: String)

data class PropIssue(val severity: Severity, val lineNo: Int?, val message: String)

data class PropParseResult(
    val props: LinkedHashMap<String, String>,
    val entries: List<PropLine>,
    val duplicates: Map<String, List<PropLine>>,
    val invalid: List<Pair<Int, String>>,
    val issues: List<PropIssue>
)

data class PropDiff(
    val added: Map<String, String>,
    val removed: Map<String, String>,
    val modified: Map<String, Pair<String, String>>,
    val duplicatesA: Set<String>,
    val duplicatesB: Set<String>,
    val conflicts: List<String>,
    val invalidA: List<Pair<Int, String>>,
    val invalidB: List<Pair<Int, String>>
)

data class CompatFinding(
    val title: String,
    val detail: String,
    val certainty: Certainty,
    val severity: Severity
)

object PropAnalyzer {
    private val KEY_RE = Regex("^[A-Za-z0-9_.@:-]+$")
    private val SDK_TO_RELEASE = mapOf(
        "31" to "12", "32" to "12", "33" to "13", "34" to "14", "35" to "15", "36" to "16"
    )

    fun parse(text: String): PropParseResult {
        val props = linkedMapOf<String, String>()
        val entries = mutableListOf<PropLine>()
        val invalid = mutableListOf<Pair<Int, String>>()
        val issues = mutableListOf<PropIssue>()
        text.lineSequence().forEachIndexed { idx, raw ->
            val no = idx + 1
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("import ")) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq <= 0) {
                invalid += no to line
                issues += PropIssue(Severity.ERROR, no, "Baris tidak berformat key=value: \"${line.take(80)}\"")
                return@forEachIndexed
            }
            val key = line.substring(0, eq).trim()
            val rawValue = line.substring(eq + 1)
            val value = rawValue.trim()
            if (!KEY_RE.matches(key)) {
                invalid += no to line
                issues += PropIssue(Severity.ERROR, no, "Nama properti mengandung karakter tidak valid: \"$key\"")
                return@forEachIndexed
            }
            if (rawValue != value && rawValue.isNotEmpty()) {
                issues += PropIssue(Severity.WARN, no, "Spasi di awal/akhir nilai pada $key (bisa menyebabkan perbandingan nilai gagal)")
            }
            if (!key.startsWith("ro.") && value.length > 91) {
                issues += PropIssue(Severity.WARN, no, "Nilai $key ${value.length} karakter; properti non-ro dibatasi 91 karakter")
            }
            entries += PropLine(no, key, value)
            props[key] = value
        }
        val duplicates = entries.groupBy { it.key }.filter { it.value.size > 1 }
        duplicates.forEach { (k, list) ->
            val distinct = list.map { it.value }.distinct()
            if (distinct.size > 1) {
                issues += PropIssue(
                    Severity.ERROR, list.last().lineNo,
                    "Properti $k didefinisikan ${list.size}× dengan nilai berbeda (baris ${list.joinToString { it.lineNo.toString() }}); yang terakhir dipakai: \"${list.last().value}\""
                )
            } else {
                issues += PropIssue(Severity.WARN, list.last().lineNo, "Properti $k duplikat ${list.size}× dengan nilai sama (baris ${list.joinToString { it.lineNo.toString() }})")
            }
        }
        issues += consistency(props)
        return PropParseResult(props, entries, duplicates, invalid, issues.sortedBy { it.severity.ordinal * -1 })
    }

    private fun consistency(p: Map<String, String>): List<PropIssue> {
        val out = mutableListOf<PropIssue>()
        val sdk = p["ro.build.version.sdk"]
        val rel = p["ro.build.version.release"] ?: p["ro.build.version.release_or_codename"]
        if (sdk != null && rel != null) {
            val expected = SDK_TO_RELEASE[sdk]
            if (expected != null && rel.substringBefore('.') != expected && !rel.any { it.isLetter() }) {
                out += PropIssue(Severity.WARN, null, "SDK $sdk umumnya Android $expected, tetapi release = $rel")
            }
        }
        val first = p["ro.product.first_api_level"]?.toIntOrNull()
        val sdkI = sdk?.toIntOrNull()
        if (first != null && sdkI != null && first > sdkI) {
            out += PropIssue(Severity.ERROR, null, "ro.product.first_api_level ($first) lebih besar dari ro.build.version.sdk ($sdkI)")
        }
        if (p["ro.debuggable"] == "1") out += PropIssue(Severity.WARN, null, "ro.debuggable=1: build dapat di-debug (tidak untuk rilis)")
        if (p["ro.secure"] == "0") out += PropIssue(Severity.WARN, null, "ro.secure=0: adb berjalan sebagai root (tidak aman untuk perangkat harian)")
        p["ro.build.tags"]?.let {
            if (it.contains("test-keys")) out += PropIssue(Severity.INFO, null, "ro.build.tags=test-keys: ROM ditandatangani kunci uji (umum pada ROM kustom)")
        }
        p["ro.build.fingerprint"]?.let { fp ->
            if (fp.count { it == '/' } < 4 || !fp.contains(':')) {
                out += PropIssue(Severity.WARN, null, "Format ro.build.fingerprint tidak lazim: brand/product/device:release/id/incremental:type/tags")
            }
        }
        val abilist = p["ro.product.cpu.abilist"]
        val abi = p["ro.product.cpu.abi"]
        if (abilist != null && abi != null && !abilist.split(',').contains(abi)) {
            out += PropIssue(Severity.WARN, null, "ro.product.cpu.abi ($abi) tidak ada di ro.product.cpu.abilist ($abilist)")
        }
        val zygote = p["ro.zygote"]
        if (zygote != null && abilist != null) {
            val has64 = abilist.contains("64")
            if (!has64 && zygote.contains("64")) {
                out += PropIssue(Severity.ERROR, null, "ro.zygote=$zygote tetapi ABI list tidak memiliki ABI 64-bit")
            }
        }
        return out
    }

    fun recommendations(r: PropParseResult): List<String> {
        val out = mutableListOf<String>()
        if (r.invalid.isNotEmpty()) out += "Perbaiki ${r.invalid.size} baris tidak valid (baris ${r.invalid.take(8).joinToString { it.first.toString() }}). Bukti: lihat daftar temuan."
        val conflicting = r.duplicates.filter { (_, v) -> v.map { it.value }.distinct().size > 1 }
        if (conflicting.isNotEmpty()) out += "Hapus duplikat yang bertentangan: ${conflicting.keys.take(6).joinToString()}. Nilai terakhir yang berlaku saat ini."
        if (r.duplicates.size > conflicting.size) out += "Rapikan duplikat bernilai sama agar file mudah dirawat."
        if (r.issues.any { it.message.contains("first_api_level") }) out += "Periksa ro.product.first_api_level; nilainya tidak boleh melebihi SDK build."
        if (r.props["ro.build.version.sdk"] == null) out += "ro.build.version.sdk tidak ditemukan; pastikan ini file build.prop dari partisi system."
        if (out.isEmpty()) out += "Tidak ada masalah struktural yang terdeteksi. Ini bukan jaminan ROM akan berjalan baik."
        return out
    }

    fun compare(a: PropParseResult, b: PropParseResult): PropDiff {
        val added = b.props.filterKeys { it !in a.props }
        val removed = a.props.filterKeys { it !in b.props }
        val modified = a.props.filter { (k, v) -> b.props.containsKey(k) && b.props[k] != v }
            .mapValues { it.value to b.props.getValue(it.key) }
        val dupA = a.duplicates.keys
        val dupB = b.duplicates.keys
        fun hasConflict(r: PropParseResult, k: String): Boolean =
            (r.duplicates[k]?.map { it.value }?.distinct()?.size ?: 0) > 1
        val conflicts = (dupA + dupB).filter { k -> hasConflict(a, k) || hasConflict(b, k) }.distinct()
        return PropDiff(added, removed, modified, dupA, dupB, conflicts, a.invalid, b.invalid)
    }

    fun diffToText(d: PropDiff): String = buildString {
        appendLine("Ditambahkan: ${d.added.size} · Dihapus: ${d.removed.size} · Diubah: ${d.modified.size}")
        appendLine("Duplikat A: ${d.duplicatesA.size} · Duplikat B: ${d.duplicatesB.size} · Konflik: ${d.conflicts.size}")
        appendLine("Tidak valid A: ${d.invalidA.size} · B: ${d.invalidB.size}")
        d.modified.entries.take(60).forEach { appendLine("~ ${it.key}: ${it.value.first}  →  ${it.value.second}") }
        d.added.entries.take(60).forEach { appendLine("+ ${it.key}=${it.value}") }
        d.removed.entries.take(60).forEach { appendLine("- ${it.key}=${it.value}") }
    }

    // ------------------------------------------------------------ Penjelasan properti

    private val exact = mapOf(
        "ro.build.version.sdk" to "Level API Android (SDK). Dipakai framework dan aplikasi untuk memeriksa kompatibilitas.",
        "ro.build.version.release" to "Nama versi Android yang tampil ke pengguna (mis. 15).",
        "ro.build.version.security_patch" to "Tanggal security patch (YYYY-MM-DD).",
        "ro.build.version.incremental" to "Nomor build internal; berubah di setiap build.",
        "ro.build.id" to "Build ID (mis. AP3A.240905.015).",
        "ro.build.display.id" to "Teks versi build yang ditampilkan di Settings.",
        "ro.build.fingerprint" to "Identitas unik build: brand/product/device:release/id/incremental:type/tags. Dipakai Play Integrity/OTA.",
        "ro.build.type" to "Tipe build: user, userdebug, atau eng.",
        "ro.build.tags" to "Kunci tanda tangan: release-keys atau test-keys.",
        "ro.build.date.utc" to "Waktu build dalam epoch detik.",
        "ro.build.characteristics" to "Karakteristik perangkat (mis. default, tablet, nosdcard).",
        "ro.build.ab_update" to "true bila perangkat memakai update A/B (slot).",
        "ro.product.model" to "Nama model yang tampil ke pengguna.",
        "ro.product.brand" to "Brand perangkat.",
        "ro.product.manufacturer" to "Nama produsen.",
        "ro.product.device" to "Codename perangkat (dipakai ROM/recovery).",
        "ro.product.board" to "Nama board/SoC platform.",
        "ro.product.cpu.abi" to "ABI utama (mis. arm64-v8a).",
        "ro.product.cpu.abilist" to "Daftar ABI yang didukung, berurutan dari prioritas tertinggi.",
        "ro.product.cpu.abilist64" to "Daftar ABI 64-bit yang didukung.",
        "ro.product.cpu.abilist32" to "Daftar ABI 32-bit yang didukung.",
        "ro.product.first_api_level" to "API level saat perangkat pertama dirilis. Menentukan persyaratan vendor/Treble dan GMS.",
        "ro.board.platform" to "Nama platform SoC (mis. kalama, mt6989).",
        "ro.hardware" to "Nama hardware; menentukan file init.<hardware>.rc yang dimuat.",
        "ro.treble.enabled" to "true bila perangkat mendukung Project Treble (vendor terpisah dari system).",
        "ro.vndk.version" to "Versi VNDK yang disediakan vendor; harus cocok dengan library system yang dipakai.",
        "ro.vndk.lite" to "Menandai VNDK Lite (tanpa VNDK penuh).",
        "ro.boot.dynamic_partitions" to "true bila memakai dynamic partitions (super).",
        "ro.virtual_ab.enabled" to "true bila memakai Virtual A/B.",
        "ro.boot.slot_suffix" to "Slot aktif pada perangkat A/B (_a atau _b).",
        "ro.boot.verifiedbootstate" to "Status verified boot: green, yellow, orange, atau red.",
        "ro.boot.flash.locked" to "1 jika bootloader terkunci.",
        "ro.boot.vbmeta.device_state" to "locked/unlocked untuk status AVB.",
        "ro.debuggable" to "1 = proses dapat di-debug dan adbd dapat berjalan sebagai root.",
        "ro.secure" to "0 = adb root diizinkan; 1 = mode aman.",
        "ro.adb.secure" to "1 = adb meminta otorisasi kunci RSA.",
        "persist.sys.usb.config" to "Konfigurasi mode USB (mtp, adb, dll).",
        "persist.sys.timezone" to "Zona waktu sistem.",
        "ro.zygote" to "Mode zygote: zygote32, zygote64, zygote32_64, atau zygote64_32.",
        "ro.config.low_ram" to "true mengaktifkan optimasi perangkat RAM rendah.",
        "dalvik.vm.heapsize" to "Batas heap maksimum per aplikasi.",
        "dalvik.vm.heapgrowthlimit" to "Batas pertumbuhan heap aplikasi normal.",
        "dalvik.vm.dex2oat-flags" to "Argumen tambahan untuk kompilasi dex2oat.",
        "debug.hwui.renderer" to "Pipeline render UI (skiagl/skiavk).",
        "ro.sf.lcd_density" to "Densitas layar default (dpi).",
        "ro.surface_flinger.max_frame_buffer_acquired_buffers" to "Jumlah buffer SurfaceFlinger; memengaruhi latensi/jank.",
        "ro.product.vendor.model" to "Model pada partisi vendor.",
        "ro.soc.manufacturer" to "Produsen SoC.",
        "ro.soc.model" to "Model SoC.",
        "ro.product.cpu.pagesize.max" to "Ukuran halaman memori maksimum yang didukung (Android 15+ untuk 16 KB).",
        "ro.config.vc_call_vol_steps" to "Jumlah langkah volume panggilan."
    )

    private val prefixes = listOf(
        "ro.product." to "Metadata produk (nama, model, brand, device) untuk partisi terkait.",
        "ro.build." to "Metadata build (versi, tipe, waktu).",
        "ro.vendor." to "Konfigurasi khusus partisi vendor.",
        "ro.boot." to "Nilai yang diteruskan bootloader/kernel cmdline; hanya-baca.",
        "ro.config." to "Konfigurasi sistem hanya-baca yang dipakai framework.",
        "persist." to "Properti yang disimpan di /data dan bertahan setelah reboot.",
        "debug." to "Properti debug/eksperimen; tidak bertahan setelah reboot.",
        "dalvik.vm." to "Parameter runtime ART/Dalvik.",
        "ro.hwui." to "Konfigurasi HWUI (render UI).",
        "ro.surface_flinger." to "Konfigurasi SurfaceFlinger (komposisi layar).",
        "ro.telephony." to "Konfigurasi telephony/RIL.",
        "ro.bluetooth." to "Konfigurasi Bluetooth.",
        "ro.audio." to "Konfigurasi audio.",
        "ro.camera." to "Konfigurasi kamera.",
        "ro.com.google." to "Properti terkait layanan Google.",
        "sys." to "Status runtime sistem; ditulis oleh proses sistem.",
        "init.svc." to "Status layanan init (running/stopped).",
        "vendor." to "Properti runtime vendor."
    )

    fun explain(key: String): String? =
        exact[key] ?: prefixes.firstOrNull { key.startsWith(it.first) }?.second
}

object CompatChecker {
    private fun get(m: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> m[k]?.takeIf { it.isNotBlank() } }

    /**
     * Membandingkan properti ROM sumber dan perangkat target.
     * Hanya temuan dengan data di kedua sisi yang ditandai TERBUKTI; selebihnya KEMUNGKINAN/TIDAK PASTI.
     */
    fun check(source: Map<String, String>, target: Map<String, String>): List<CompatFinding> {
        val out = mutableListOf<CompatFinding>()
        fun cmp(title: String, a: String?, b: String?, severity: Severity, mismatchHint: String) {
            when {
                a == null || b == null ->
                    out += CompatFinding(title, "Data ${if (a == null) "ROM sumber" else "perangkat target"} tidak tersedia; tidak dapat dipastikan.", Certainty.UNKNOWN, Severity.INFO)
                a == b -> out += CompatFinding(title, "Sama: $a", Certainty.PROVEN, Severity.INFO)
                else -> out += CompatFinding(title, "Sumber: $a · Target: $b. $mismatchHint", Certainty.PROVEN, severity)
            }
        }
        val sdkA = get(source, "ro.build.version.sdk")
        val sdkB = get(target, "ro.build.version.sdk")
        cmp("Versi SDK", sdkA, sdkB, Severity.WARN, "Beda SDK sering memerlukan vendor/framework yang sesuai.")
        cmp("Versi Android", get(source, "ro.build.version.release"), get(target, "ro.build.version.release"), Severity.INFO, "")

        val abiA = get(source, "ro.product.cpu.abilist", "ro.product.cpu.abi")?.split(',')?.map { it.trim() }
        val abiB = get(target, "ro.product.cpu.abilist", "ro.product.cpu.abi")?.split(',')?.map { it.trim() }
        if (abiA == null || abiB == null) {
            out += CompatFinding("ABI", "Daftar ABI salah satu sisi tidak tersedia.", Certainty.UNKNOWN, Severity.INFO)
        } else {
            val common = abiA.intersect(abiB.toSet())
            if (common.isEmpty()) out += CompatFinding("ABI", "Tidak ada ABI yang sama (sumber: ${abiA.joinToString()} · target: ${abiB.joinToString()}).", Certainty.PROVEN, Severity.ERROR)
            else {
                val missing = abiA.filter { it !in abiB }
                out += CompatFinding(
                    "ABI", "ABI bersama: ${common.joinToString()}" + if (missing.isNotEmpty()) ". ABI sumber yang tidak didukung target: ${missing.joinToString()}" else "",
                    Certainty.PROVEN, if (missing.isNotEmpty()) Severity.WARN else Severity.INFO
                )
            }
        }
        cmp("VNDK", get(source, "ro.vndk.version", "ro.product.vndk.version"), get(target, "ro.vndk.version", "ro.product.vndk.version"), Severity.ERROR,
            "VNDK harus sesuai dengan library vendor target; ketidakcocokan dapat menyebabkan bootloop atau service crash.")
        cmp("Treble", get(source, "ro.treble.enabled"), get(target, "ro.treble.enabled"), Severity.WARN, "Port GSI/ROM Treble memerlukan Treble di kedua sisi.")
        cmp("Dynamic partitions", get(source, "ro.boot.dynamic_partitions"), get(target, "ro.boot.dynamic_partitions"), Severity.WARN, "Struktur super/partisi berbeda.")
        cmp("Virtual A/B", get(source, "ro.virtual_ab.enabled"), get(target, "ro.virtual_ab.enabled"), Severity.INFO, "Memengaruhi cara flash/OTA.")
        cmp("A/B update", get(source, "ro.build.ab_update"), get(target, "ro.build.ab_update"), Severity.WARN, "Skema slot berbeda.")
        cmp("Platform SoC", get(source, "ro.board.platform"), get(target, "ro.board.platform"), Severity.WARN,
            "Platform berbeda: kernel, firmware, dan HAL vendor kemungkinan tidak kompatibel kecuali memakai vendor target.")
        cmp("Page size maksimum", get(source, "ro.product.cpu.pagesize.max"), get(target, "ro.product.cpu.pagesize.max"), Severity.INFO,
            "Terkait dukungan 16 KB page size (Android 15+).")

        val fa = get(target, "ro.product.first_api_level")?.toIntOrNull()
        val sdk = sdkA?.toIntOrNull()
        if (fa != null && sdk != null) {
            if (sdk - fa > 4) {
                out += CompatFinding(
                    "Selisih dengan first API level target",
                    "Target dirilis dengan API $fa, ROM sumber API $sdk (selisih ${sdk - fa}). Vendor lama pada ROM baru dapat bermasalah.",
                    Certainty.PROBABLE, Severity.WARN
                )
            }
        }
        val patchA = get(source, "ro.build.version.security_patch")
        val patchB = get(target, "ro.build.version.security_patch")
        if (patchA != null && patchB != null) {
            out += CompatFinding("Security patch", "Sumber: $patchA · Target: $patchB", Certainty.PROVEN, Severity.INFO)
        }
        return out
    }
}
