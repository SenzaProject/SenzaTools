package com.senzaproject.senzaai.ai

import android.content.Context
import android.os.Build
import com.senzaproject.senzaai.data.DeviceRepository
import com.senzaproject.senzaai.domain.model.Certainty
import com.senzaproject.senzaai.domain.model.DiagnosticReport
import com.senzaproject.senzaai.domain.model.Finding
import com.senzaproject.senzaai.domain.model.Severity
import com.senzaproject.senzaai.utils.Sys
import com.senzaproject.senzaai.utils.f1
import com.senzaproject.senzaai.utils.formatBytes
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Senza Smart Diagnostics: mesin aturan lokal (tanpa jaringan) yang menggabungkan data perangkat.
 * Setiap temuan membawa bukti, tingkat kepastian, dan rekomendasi aman.
 */
class SmartDiagnostics(private val ctx: Context, private val device: DeviceRepository) {

    fun run(): DiagnosticReport {
        val f = mutableListOf<Finding>()
        val bat = device.battery()
        val mem = device.memory()
        val sto = device.storage()
        val cpu = device.cpuSnapshot(null)
        val p = Sys.props()

        bat.tempC?.let { t ->
            when {
                t >= 45f -> f += Finding("Suhu baterai tinggi", "Suhu baterai ${t.f1()} °C", Certainty.PROVEN, Severity.ERROR,
                    "Hentikan pengisian/penggunaan berat sampai dingin, lepas casing, hindari panas langsung.")
                t >= 40f -> f += Finding("Suhu baterai agak tinggi", "Suhu baterai ${t.f1()} °C", Certainty.PROVEN, Severity.WARN,
                    "Kurangi beban (game/kamera) saat mengisi daya.")
            }
        }
        if (bat.health != "Baik" && bat.health != "Tidak diketahui") {
            f += Finding("Kesehatan baterai: ${bat.health}", "Status kesehatan dari sistem: ${bat.health}", Certainty.PROVEN, Severity.ERROR,
                "Pertimbangkan servis/ganti baterai.")
        }
        bat.cycleCount?.let { c ->
            if (c >= 800) f += Finding("Siklus baterai tinggi", "Cycle count $c", Certainty.PROVEN, Severity.WARN,
                "Kapasitas kemungkinan menurun; periksa kapasitas efektif.")
        }
        if (mem.percent >= 90f) {
            f += Finding("RAM hampir penuh", "RAM terpakai ${mem.percent.f1()} % (${mem.availBytes.formatBytes()} tersedia dari ${mem.totalBytes.formatBytes()})",
                Certainty.PROVEN, Severity.WARN, "Tutup aplikasi latar belakang yang tidak dipakai. Catatan: Android memang memakai RAM untuk cache.")
        }
        if (sto.percent >= 92f) {
            f += Finding("Penyimpanan hampir penuh", "Terpakai ${sto.percent.f1()} % (sisa ${sto.freeBytes.formatBytes()})", Certainty.PROVEN, Severity.ERROR,
                "Kosongkan ruang: hapus file besar/cache. Penyimpanan penuh dapat memicu lag dan gagal update.")
        } else if (sto.percent >= 85f) {
            f += Finding("Penyimpanan mulai penuh", "Terpakai ${sto.percent.f1()} % (sisa ${sto.freeBytes.formatBytes()})", Certainty.PROVEN, Severity.WARN,
                "Sisakan minimal 10–15% agar performa stabil.")
        }
        cpu.tempC?.let { t ->
            if (t >= 80f) f += Finding("Suhu CPU/SoC tinggi", "Zona termal tertinggi terkait CPU: ${t.f1()} °C", Certainty.PROBABLE, Severity.WARN,
                "Throttling kemungkinan terjadi. Pembacaan zona termal berbeda antar perangkat.")
        }

        val se = device.selinuxMode()
        if (se.equals("Permissive", true)) f += Finding("SELinux permissive", "Mode SELinux: $se", Certainty.PROVEN, Severity.WARN,
            "Keamanan berkurang. Normal untuk pengembangan ROM, tidak untuk pemakaian harian.")

        if (p["ro.debuggable"] == "1") f += Finding("Build debuggable", "ro.debuggable=1", Certainty.PROVEN, Severity.WARN, "Build ini ditujukan untuk pengembangan.")
        if (p["ro.build.tags"]?.contains("test-keys") == true) f += Finding("Ditandatangani test-keys", "ro.build.tags=${p["ro.build.tags"]}", Certainty.PROVEN, Severity.INFO,
            "Umum pada ROM kustom; aplikasi tertentu (mis. perbankan) dapat menolak.")
        p["ro.boot.verifiedbootstate"]?.let {
            if (it != "green") f += Finding("Verified boot: $it", "ro.boot.verifiedbootstate=$it", Certainty.PROVEN, Severity.INFO,
                "Bootloader terbuka atau image dimodifikasi; Play Integrity dapat gagal.")
        }

        val patch = Build.VERSION.SECURITY_PATCH
        val days: Long? = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        if (days != null) {
            if (days > 365) {
                f += Finding("Security patch sangat lama", "Patch $patch (${days / 30} bulan lalu)", Certainty.PROVEN, Severity.WARN,
                    "Perangkat mungkin rentan terhadap celah yang sudah diperbaiki; cek update dari produsen/ROM.")
            } else if (days > 180) {
                f += Finding("Security patch cukup lama", "Patch $patch (${days / 30} bulan lalu)", Certainty.PROVEN, Severity.INFO,
                    "Cek ketersediaan pembaruan.")
            }
        }

        val ps = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        if (ps == 16384L) f += Finding("Kernel memakai page size 16 KB", "Page size $ps", Certainty.PROVEN, Severity.INFO,
            "Aplikasi dengan library native 64-bit yang tidak selaras 16 KB dapat gagal. Cek lewat APK Analyzer.")

        if (f.none { it.severity != Severity.INFO }) {
            f += Finding("Tidak ada masalah mencolok", "Seluruh pemeriksaan lokal lulus ambang batas.", Certainty.PROVEN, Severity.INFO,
                "Pemeriksaan ini terbatas pada data yang dapat dibaca. Untuk dugaan masalah tertentu, gunakan AI Assistant dengan log terkait.")
        }
        return DiagnosticReport(System.currentTimeMillis(), f.sortedByDescending { it.severity.ordinal })
    }

    fun toText(r: DiagnosticReport): String = buildString {
        appendLine("Senza Smart Diagnostics — ${r.time.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it)) }}")
        appendLine("Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine()
        r.findings.forEachIndexed { i, x ->
            appendLine("${i + 1}. [${x.severity}] ${x.title}")
            appendLine("   Bukti: ${x.evidence}")
            appendLine("   Kepastian: ${x.certainty.label}")
            appendLine("   Rekomendasi: ${x.recommendation}")
        }
    }
}
