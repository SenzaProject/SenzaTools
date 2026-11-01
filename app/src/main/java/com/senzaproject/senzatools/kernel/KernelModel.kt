package com.senzaproject.senzatools.kernel

/*
 * Model Kernel Manager Senza. Seluruh berkas di paket ini (kecuali KernelEngine/KernelStore) adalah Kotlin murni
 * tanpa dependensi Android, supaya aturan keamanan dan deteksinya bisa diuji sebagai unit test JVM.
 *
 * Daftar node sysfs/procfs di KernelCatalog diporting dari SmartPack-Kernel-Manager (GPLv3) — lihat docs/KERNEL.md.
 */

enum class KGroup(val label: String) {
    CPU("CPU"), GPU("GPU"), RAM("RAM"), THERMAL("Thermal"), IO("I/O"), BATTERY("Baterai"), MISC("Lainnya")
}

/** HIGH: bisa merusak/membahayakan perangkat bila salah; butuh konfirmasi tegas dan TIDAK ikut diterapkan saat boot. */
enum class Risk(val label: String) { LOW("Aman"), MEDIUM("Hati-hati"), HIGH("Berisiko") }

sealed interface KType {
    /** Nilai dua keadaan. Contoh: "1"/"0" atau "Y"/"N" (parameter modul kernel). */
    data class Toggle(val on: String = "1", val off: String = "0") : KType

    /** Bilangan bulat dalam rentang [min, max]. */
    data class Num(val min: Long, val max: Long, val step: Long = 1, val unit: String = "") : KType

    /**
     * Pilihan dari daftar. Daftar diambil dari [availablePath] bila ada; bila tidak, dari berkas nilai itu sendiri
     * (format scheduler: "mq-deadline [kyber] none", token berkurung = aktif). [fallback] dipakai bila daftar kosong.
     */
    data class Choice(val availablePath: String? = null, val fallback: List<String> = emptyList()) : KType

    /** Daftar angka dipisah koma dengan jumlah tetap (mis. LMK minfree). */
    data class Csv(val count: Int, val min: Long, val max: Long) : KType
}

/**
 * Satu pengaturan kernel. [paths] adalah kandidat (varian kernel berbeda); yang pertama ada di perangkat dipakai.
 * Hanya path di sini yang boleh ditulis — mesin root menolak path lain.
 */
data class KNode(
    val id: String,
    val group: KGroup,
    val title: String,
    val desc: String,
    val paths: List<String>,
    val type: KType,
    val risk: Risk = Risk.LOW,
    /** SoC yang diketahui menyediakan node ini; dipakai hanya untuk pesan "tidak tersedia" yang lebih jelas. */
    val vendors: Set<SocVendor> = emptySet(),
    val note: String? = null
) {
    /** Node berisiko tinggi tidak pernah dimasukkan ke penerapan otomatis saat boot. */
    val bootSafe: Boolean get() = risk != Risk.HIGH
}

enum class SocVendor(val label: String) {
    QUALCOMM("Qualcomm"), MEDIATEK("MediaTek"), EXYNOS("Samsung Exynos"), TENSOR("Google Tensor"),
    UNISOC("UNISOC"), UNKNOWN("Tidak dikenali")
}

data class KernelVersion(val major: Int, val minor: Int, val patch: Int, val raw: String) {
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val RE = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /** Mengurai "5.10.168-android12-9-g1234" atau seluruh isi /proc/version. */
        fun parse(text: String?): KernelVersion? {
            val m = RE.find(text.orEmpty()) ?: return null
            val (a, b, c) = m.destructured
            return KernelVersion(a.toInt(), b.toInt(), c.toIntOrNull() ?: 0, m.value)
        }
    }
}

/** Profil perangkat untuk deteksi kompatibilitas. */
data class DeviceProfile(
    val sdk: Int,
    val kernel: KernelVersion?,
    val soc: SocVendor,
    val platform: String,
    val selinux: String,
    val ramMb: Long?
)

sealed interface Support {
    /** Node ada dan bisa ditulis. */
    data class Ready(val path: String) : Support

    /** Node ada tetapi tidak bisa ditulis (kernel/SELinux mengunci). */
    data class ReadOnly(val path: String) : Support

    /** Node ada tetapi tidak terbaca (SELinux/izin) — tidak bisa dipastikan. */
    data class Protected(val path: String) : Support

    data class Missing(val reason: String) : Support
}

val Support.usable: Boolean get() = this is Support.Ready

/** Keadaan nyata satu node pada perangkat. */
data class NodeState(
    val node: KNode,
    val support: Support,
    /** Nilai saat ini sudah dinormalkan (token aktif untuk Choice). */
    val value: String? = null,
    /** Opsi untuk Choice. */
    val options: List<String> = emptyList()
)

data class Change(val nodeId: String, val value: String)

sealed interface WriteResult {
    val nodeId: String

    data class Ok(override val nodeId: String, val old: String, val new: String) : WriteResult
    data class Failed(override val nodeId: String, val reason: String, val rolledBack: Boolean) : WriteResult
}
