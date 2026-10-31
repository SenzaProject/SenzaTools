package com.senzaproject.senzaai.analyzer.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.senzaproject.senzaai.analyzer.system.CompatFinding
import com.senzaproject.senzaai.domain.model.Certainty
import com.senzaproject.senzaai.domain.model.Severity
import com.senzaproject.senzaai.utils.formatBytes
import com.senzaproject.senzaai.utils.formatTime
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

data class ApkNote(val severity: Severity, val text: String)
data class ApkComponent(val kind: String, val name: String, val exported: Boolean, val permission: String?)
data class ApkPermission(val name: String, val level: String, val dangerous: Boolean, val note: String?)
data class CertInfo(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val sha256: String,
    val algorithm: String,
    val expired: Boolean
)

data class ApkReport(
    val fileName: String,
    val sizeBytes: Long,
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int?,
    val icon: Bitmap?,
    val abis: List<String>,
    val nativeLibs: List<NativeLib>,
    val dex: List<DexInfo>,
    val components: List<ApkComponent>,
    val permissions: List<ApkPermission>,
    val certs: List<CertInfo>,
    val hasV1Signature: Boolean,
    val resourceFiles: Int,
    val arscBytes: Long,
    val assetFiles: Int,
    val structure: List<Triple<String, Long, Int>>, // nama, ukuran tak terkompresi, jumlah entri
    val notes: List<ApkNote>,
    val compat: List<CompatFinding>
) {
    fun summary(): String = buildString {
        appendLine("APK: $appName ($packageName) v${versionName ?: "?"} ($versionCode), ${sizeBytes.formatBytes()}")
        appendLine("minSdk=$minSdk targetSdk=$targetSdk compileSdk=${compileSdk ?: "?"}")
        appendLine("ABI native: ${abis.joinToString().ifBlank { "tidak ada native lib" }}")
        appendLine("Izin: ${permissions.size} (berbahaya: ${permissions.count { it.dangerous }})")
        appendLine("Komponen: ${components.size} (exported: ${components.count { it.exported }})")
        appendLine("Penandatangan: ${certs.size}; v1=${hasV1Signature}")
        notes.forEach { appendLine("- [${it.severity}] ${it.text}") }
        compat.filter { it.severity != Severity.INFO }.forEach { appendLine("- Kompatibilitas [${it.certainty.label}] ${it.title}: ${it.detail}") }
    }
}

class ApkAnalyzer(private val ctx: Context) {

    private val permNotes = mapOf(
        "CAMERA" to "Mengakses kamera",
        "RECORD_AUDIO" to "Merekam audio dari mikrofon",
        "ACCESS_FINE_LOCATION" to "Lokasi presisi",
        "ACCESS_COARSE_LOCATION" to "Lokasi perkiraan",
        "ACCESS_BACKGROUND_LOCATION" to "Lokasi saat aplikasi di latar belakang",
        "READ_CONTACTS" to "Membaca kontak",
        "WRITE_CONTACTS" to "Mengubah kontak",
        "READ_SMS" to "Membaca SMS",
        "SEND_SMS" to "Mengirim SMS (dapat berbiaya)",
        "READ_CALL_LOG" to "Membaca riwayat panggilan",
        "READ_PHONE_STATE" to "Status telepon dan identitas perangkat",
        "CALL_PHONE" to "Melakukan panggilan telepon",
        "READ_EXTERNAL_STORAGE" to "Membaca penyimpanan bersama",
        "WRITE_EXTERNAL_STORAGE" to "Menulis penyimpanan bersama",
        "MANAGE_EXTERNAL_STORAGE" to "Akses semua file",
        "READ_MEDIA_IMAGES" to "Membaca gambar",
        "READ_MEDIA_VIDEO" to "Membaca video",
        "READ_MEDIA_AUDIO" to "Membaca audio",
        "POST_NOTIFICATIONS" to "Menampilkan notifikasi",
        "INTERNET" to "Akses internet",
        "REQUEST_INSTALL_PACKAGES" to "Meminta memasang aplikasi lain",
        "SYSTEM_ALERT_WINDOW" to "Menggambar di atas aplikasi lain",
        "BIND_ACCESSIBILITY_SERVICE" to "Layanan aksesibilitas (dapat membaca layar)",
        "QUERY_ALL_PACKAGES" to "Melihat semua aplikasi terpasang",
        "RECEIVE_BOOT_COMPLETED" to "Berjalan saat perangkat menyala",
        "FOREGROUND_SERVICE" to "Menjalankan foreground service",
        "WAKE_LOCK" to "Mencegah perangkat tidur",
        "BLUETOOTH_CONNECT" to "Terhubung ke perangkat Bluetooth",
        "BLUETOOTH_SCAN" to "Memindai perangkat Bluetooth"
    )

    @Suppress("DEPRECATION")
    fun analyze(file: File, displayName: String): ApkReport {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val pi = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("Bukan APK utama yang valid (mungkin split APK, AAB, atau file rusak).")
        val ai = pi.applicationInfo ?: throw IllegalArgumentException("ApplicationInfo tidak tersedia pada APK ini.")
        ai.sourceDir = file.absolutePath
        ai.publicSourceDir = file.absolutePath

        val label = try {
            ai.loadLabel(pm).toString()
        } catch (_: Throwable) {
            pi.packageName
        }
        val icon = try {
            ai.loadIcon(pm).toBitmap(128, 128)
        } catch (_: Throwable) {
            null
        }

        val components = mutableListOf<ApkComponent>()
        pi.activities?.forEach { components += ApkComponent("Activity", it.name, it.exported, it.permission) }
        pi.services?.forEach { components += ApkComponent("Service", it.name, it.exported, it.permission) }
        pi.receivers?.forEach { components += ApkComponent("Receiver", it.name, it.exported, it.permission) }
        pi.providers?.forEach { components += ApkComponent("Provider", it.name, it.exported, it.readPermission ?: it.writePermission) }

        val perms = (pi.requestedPermissions ?: emptyArray()).map { name ->
            val info = try {
                pm.getPermissionInfo(name, 0)
            } catch (_: Throwable) {
                null
            }
            val level = when (info?.protection) {
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                PermissionInfo.PROTECTION_INTERNAL -> "internal"
                null -> "kustom/tidak dikenal"
                else -> "lainnya"
            }
            ApkPermission(name, level, info?.protection == PermissionInfo.PROTECTION_DANGEROUS, permNotes[name.substringAfterLast('.')])
        }

        val certs = readCerts(pi.signingInfo)

        // ---- isi ZIP
        val libs = mutableListOf<NativeLib>()
        val dex = mutableListOf<DexInfo>()
        var resFiles = 0
        var arsc = 0L
        var assets = 0
        var v1 = false
        val groups = linkedMapOf<String, Pair<Long, Int>>()
        ZipFile(file).use { zip ->
            for (e in java.util.Collections.list(zip.entries())) {
                val name = e.name
                if (e.isDirectory) continue
                val top = name.substringBefore('/', name)
                val g = groups[top] ?: (0L to 0)
                groups[top] = (g.first + maxOf(e.size, 0L)) to (g.second + 1)
                when {
                    name.startsWith("lib/") && name.endsWith(".so") && libs.size < 600 -> {
                        val abi = name.split('/').getOrNull(1) ?: "?"
                        val elf = try {
                            zip.getInputStream(e).use { ElfAnalyzer.parse(ElfAnalyzer.readHead(it)) }
                        } catch (_: Throwable) {
                            null
                        }
                        libs += NativeLib(name, abi, e.size, elf)
                    }
                    Regex("classes\\d*\\.dex").matches(name) -> {
                        val head = try {
                            zip.getInputStream(e).use { ElfAnalyzer.readHead(it, 112) }
                        } catch (_: Throwable) {
                            ByteArray(0)
                        }
                        dex += ElfAnalyzer.parseDex(name, e.size, head)
                    }
                    name.startsWith("res/") -> resFiles++
                    name == "resources.arsc" -> arsc = e.size
                    name.startsWith("assets/") -> assets++
                    name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name.endsWith(".SF")) -> v1 = true
                }
            }
        }

        val notes = mutableListOf<ApkNote>()
        if ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) notes += ApkNote(Severity.WARN, "Aplikasi bertanda debuggable.")
        if ((ai.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0) notes += ApkNote(Severity.WARN, "Aplikasi bertanda testOnly (tidak dapat dipasang normal tanpa -t).")
        if ((ai.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0) notes += ApkNote(Severity.INFO, "usesCleartextTraffic aktif (lalu lintas HTTP tanpa enkripsi diizinkan secara default).")
        if ((ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) notes += ApkNote(Severity.INFO, "allowBackup aktif.")
        val exportedRisky = components.filter { it.exported && it.permission == null && it.kind != "Activity" }
        if (exportedRisky.isNotEmpty()) {
            notes += ApkNote(Severity.INFO, "${exportedRisky.size} komponen non-Activity exported tanpa permission (periksa apakah memang disengaja): ${exportedRisky.take(4).joinToString { it.name.substringAfterLast('.') }}")
        }
        val dangerous = perms.count { it.dangerous }
        if (dangerous > 0) notes += ApkNote(Severity.INFO, "$dangerous izin berbahaya diminta (runtime permission).")
        if (certs.isEmpty()) notes += ApkNote(Severity.ERROR, "Tidak ada tanda tangan yang dapat dibaca.")
        certs.forEach { c ->
            if (c.expired) notes += ApkNote(Severity.WARN, "Sertifikat penandatangan sudah kedaluwarsa (${c.notAfter}).")
            if (c.subject.contains("Android Debug", ignoreCase = true)) notes += ApkNote(Severity.INFO, "Ditandatangani dengan debug key.")
        }
        notes += ApkNote(Severity.INFO, "Analisis keamanan bersifat indikatif dan bukan jaminan bahwa APK aman.")

        val compat = compatibility(ai, libs, pi.packageName)

        // Setiap argumen dihitung ke variabel bertipe eksplisit agar kesalahan (jika ada) terlokalisasi.
        val vFileName: String = displayName
        val vSize: Long = file.length()
        val vAppName: String = label
        val vPackage: String = pi.packageName
        val vVersionName: String? = pi.versionName
        val vVersionCode: Long = pi.longVersionCode
        val vMinSdk: Int = ai.minSdkVersion
        val vTargetSdk: Int = ai.targetSdkVersion
        val vCompileSdkRaw: Int = 0
        val vCompileSdk: Int? = if (vCompileSdkRaw > 0) vCompileSdkRaw else null
        val vIcon: Bitmap? = icon
        val vAbis: List<String> = libs.map { it.abi }.distinct()
        val vLibs: List<NativeLib> = libs.toList()
        val vDex: List<DexInfo> = dex.toList()
        val vComponents: List<ApkComponent> = components.toList()
        val vPerms: List<ApkPermission> = perms.toList()
        val vCerts: List<CertInfo> = certs.toList()
        val vV1: Boolean = v1
        val vResFiles: Int = resFiles
        val vArsc: Long = arsc
        val vAssets: Int = assets
        val vStructure: List<Triple<String, Long, Int>> =
            groups.entries.sortedByDescending { it.value.first }.map { Triple(it.key, it.value.first, it.value.second) }
        val vNotes: List<ApkNote> = notes.toList()
        val vCompat: List<CompatFinding> = compat.toList()

        return ApkReport(
            vFileName, vSize, vAppName, vPackage, vVersionName, vVersionCode, vMinSdk, vTargetSdk, vCompileSdk,
            vIcon, vAbis, vLibs, vDex, vComponents, vPerms, vCerts, vV1, vResFiles, vArsc, vAssets, vStructure,
            vNotes, vCompat
        )
    }

    private fun readCerts(si: android.content.pm.SigningInfo?): List<CertInfo> {
        if (si == null) return emptyList()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().mapNotNull { sig ->
            try {
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate
                val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
                CertInfo(
                    subject = cert.subjectX500Principal.name,
                    issuer = cert.issuerX500Principal.name,
                    notBefore = cert.notBefore.time.formatTime("yyyy-MM-dd"),
                    notAfter = cert.notAfter.time.formatTime("yyyy-MM-dd"),
                    sha256 = sha,
                    algorithm = cert.sigAlgName,
                    expired = cert.notAfter.time < System.currentTimeMillis()
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun compatibility(ai: ApplicationInfo, libs: List<NativeLib>, pkg: String): List<CompatFinding> {
        val out = mutableListOf<CompatFinding>()
        val sdk = Build.VERSION.SDK_INT
        if (ai.minSdkVersion > sdk) {
            out += CompatFinding("SDK minimum", "APK membutuhkan SDK ${ai.minSdkVersion}, perangkat ini SDK $sdk.", Certainty.PROVEN, Severity.ERROR)
        } else {
            out += CompatFinding("SDK minimum", "minSdk ${ai.minSdkVersion} ≤ SDK perangkat $sdk.", Certainty.PROVEN, Severity.INFO)
        }
        if (ai.targetSdkVersion < 24) {
            out += CompatFinding(
                "Target SDK lama",
                "targetSdk ${ai.targetSdkVersion}: Android terbaru memblokir instalasi aplikasi dengan targetSdk lama (<23 sejak Android 14, <24 sejak Android 15).",
                Certainty.PROBABLE, Severity.WARN
            )
        }
        if (libs.isEmpty()) {
            out += CompatFinding("ABI", "Tidak ada native library; tidak bergantung pada ABI.", Certainty.PROVEN, Severity.INFO)
        } else {
            val abis = libs.map { it.abi }.distinct()
            val device = Build.SUPPORTED_ABIS.toSet()
            val match = abis.filter { it in device }
            if (match.isEmpty()) {
                out += CompatFinding("ABI", "Library hanya untuk ${abis.joinToString()}, perangkat mendukung ${device.joinToString()}.", Certainty.PROVEN, Severity.ERROR)
            } else {
                out += CompatFinding("ABI", "ABI cocok: ${match.joinToString()}", Certainty.PROVEN, Severity.INFO)
            }
            val mismatch = abis.filter { it !in device }
            if (mismatch.isNotEmpty() && match.isNotEmpty()) {
                out += CompatFinding("ABI tambahan", "ABI di APK yang tidak dipakai perangkat ini: ${mismatch.joinToString()}.", Certainty.PROVEN, Severity.INFO)
            }
            val libs64 = libs.filter { it.elf?.is64 == true }
            val bad = libs64.filter { it.elf?.ok16k == false }
            val unknown = libs64.filter { it.elf?.ok16k == null }
            when {
                libs64.isEmpty() -> out += CompatFinding("16 KB page size", "Tidak ada library 64-bit.", Certainty.PROVEN, Severity.INFO)
                bad.isNotEmpty() -> out += CompatFinding(
                    "16 KB page size",
                    "${bad.size} dari ${libs64.size} library 64-bit tidak selaras 16 KB (align < 16384): ${bad.take(4).joinToString { it.path.substringAfterLast('/') }}. Perangkat dengan page size 16 KB dapat gagal memuatnya.",
                    Certainty.PROVEN, Severity.WARN
                )
                unknown.isNotEmpty() -> out += CompatFinding("16 KB page size", "${unknown.size} library tidak dapat diperiksa penuh.", Certainty.UNKNOWN, Severity.INFO)
                else -> out += CompatFinding("16 KB page size", "Semua ${libs64.size} library 64-bit memiliki segmen LOAD selaras ≥ 16 KB. Catatan: penyelarasan di dalam ZIP tidak diperiksa.", Certainty.PROVEN, Severity.INFO)
            }
        }
        val installed = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(pkg, 0)
        } catch (_: Throwable) {
            null
        }
        if (installed != null) {
            out += CompatFinding("Terpasang di perangkat", "Paket sudah terpasang (versi ${installed.versionName}). Pembaruan hanya berhasil jika tanda tangan sama.", Certainty.PROVEN, Severity.INFO)
        }
        return out
    }
}
