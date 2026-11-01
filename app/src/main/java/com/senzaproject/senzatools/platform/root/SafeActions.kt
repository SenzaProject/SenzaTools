package com.senzaproject.senzatools.platform.root

/**
 * Aksi sistem yang sudah ditentukan dan divalidasi. Semua bersifat READ-ONLY.
 * Teks bebas TIDAK PERNAH dijalankan sebagai perintah root;
 * hanya aksi di bawah ini atau perintah yang diketik pengguna sendiri lalu dikonfirmasi.
 */
object PathGuard {
    private val OK = Regex("^/[A-Za-z0-9_./@:+,=-]*$")
    private val DENY = listOf("/proc/kcore", "/proc/kmsg", "/sys/kernel/debug/", "/dev/")
    private val DEV_LIST_ALLOWED = listOf("/dev/block/by-name", "/dev/block/mapper")

    fun validate(raw: String, allowDevListing: Boolean = false): String {
        val path = raw.trim().trimEnd('/').ifEmpty { "/" }
        require(path.startsWith("/")) { "Path harus absolut (diawali /)" }
        require(OK.matches(path)) { "Path mengandung karakter yang tidak diizinkan" }
        require(!path.split('/').contains("..")) { "Path tidak boleh memuat '..'" }
        require(!Regex("^/proc/[0-9]+/mem$").matches(path)) { "Path diblokir demi keamanan" }
        val devOk = allowDevListing && DEV_LIST_ALLOWED.any { path == it }
        if (!devOk) require(DENY.none { path == it.trimEnd('/') || path.startsWith(it) }) { "Path diblokir demi keamanan" }
        return path
    }
}

sealed class SafeAction(val title: String, val description: String) {
    abstract val command: String

    class ReadFile(path: String) : SafeAction("Baca file", "Membaca maksimal 256 KB dari file (read-only).") {
        val path: String = PathGuard.validate(path)
        override val command: String get() = "head -c 262144 -- '$path'"
    }

    /** Skrip baca-saja yang disusun internal oleh aplikasi (bukan dari input bebas). */
    class ReadScript(override val command: String, label: String = "Baca info performa") : SafeAction(label, "Membaca nilai sysfs/properti (read-only).")

    class SizeOf(path: String) : SafeAction("Ukuran file", "Membaca ukuran file dalam byte (read-only).") {
        val path: String = PathGuard.validate(path)
        override val command: String get() = "wc -c < '$path'"
    }

    class ReadFileFull(path: String) : SafeAction("Baca file penuh", "Membaca isi penuh file teks untuk diedit (read-only).") {
        val path: String = PathGuard.validate(path)
        override val command: String get() = "cat -- '$path'"
    }

    class ListDir(path: String) : SafeAction("Daftar direktori", "Menampilkan isi direktori beserta izin (read-only).") {
        val path: String = PathGuard.validate(path, allowDevListing = true)
        override val command: String get() = "ls -la -- '$path/'"
    }

    class Stat(path: String) : SafeAction("Info file", "Menampilkan informasi file/direktori (read-only).") {
        val path: String = PathGuard.validate(path)
        override val command: String get() = "stat -- '$path'"
    }

    class GetProp(key: String) : SafeAction("Baca properti", "Membaca satu system property (read-only).") {
        val key: String = key.trim().also {
            require(Regex("^[A-Za-z0-9_.-]+$").matches(it)) { "Nama properti tidak valid" }
        }
        override val command: String get() = "getprop '$key'"
    }

    class Find(path: String, pattern: String) : SafeAction("Cari file", "Mencari nama file (read-only, kedalaman maks 5, 300 hasil).") {
        val path: String = PathGuard.validate(path)
        val pattern: String = pattern.trim().also {
            require(Regex("^[A-Za-z0-9_.*?-]{1,64}$").matches(it)) { "Pola hanya boleh huruf, angka, _ . - * ?" }
        }
        override val command: String get() = "find '$path' -maxdepth 5 -iname '$pattern' 2>/dev/null | head -n 300"
    }

    object Logcat : SafeAction("Logcat", "Mengambil 2000 baris log terakhir (read-only).") {
        override val command: String = "logcat -d -t 2000"
    }

    object Dmesg : SafeAction("Dmesg", "Mengambil 500 baris terakhir log kernel (read-only).") {
        override val command: String = "dmesg | tail -n 500"
    }

    object KernelModules : SafeAction("Modul kernel", "Membaca /proc/modules (read-only).") {
        override val command: String = "cat /proc/modules"
    }

    object Mounts : SafeAction("Mount point", "Membaca /proc/mounts (read-only).") {
        override val command: String = "cat /proc/mounts"
    }

    object PartitionsByName : SafeAction("Partisi by-name", "Daftar nama partisi blok (read-only, tidak membaca isi).") {
        override val command: String = "ls -l /dev/block/by-name 2>/dev/null || ls -l /dev/block/platform/*/by-name 2>/dev/null"
    }

    object ProcessList : SafeAction("Daftar proses", "Daftar proses (PID, user, RSS, nama) (read-only).") {
        override val command: String = "ps -A -o PID,USER,RSS,NAME | head -n 400"
    }

    object KernelConfig : SafeAction("Konfigurasi kernel", "Membaca /proc/config.gz bila tersedia (read-only).") {
        override val command: String = "zcat /proc/config.gz 2>/dev/null | head -n 4000"
    }
}

/** Penjaga perintah yang diketik pengguna sendiri (konsol shell). Bukan jaminan keamanan penuh. */
object CommandGuard {
    private val blocked: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\s+(-[a-zA-Z]+\s+)*(/|/\*|/system\S*|/vendor\S*|/product\S*|/data|/data/\*|/storage\S*|/sdcard\S*)(\s|$)""") to "Penghapusan direktori sistem/data",
        Regex("""\bmkfs(\.\w+)?\b""") to "Memformat filesystem",
        Regex("""\bmake_ext4fs\b""") to "Memformat filesystem",
        Regex("""\bdd\b.*\bof=/dev/""") to "Menulis langsung ke perangkat blok",
        Regex(""">\s*/dev/block/""") to "Menulis langsung ke perangkat blok",
        Regex("""\b(blkdiscard|flash_image|erase_image|sgdisk|parted|fdisk|sfdisk|wipefs)\b""") to "Operasi partisi/flash",
        Regex("""\bfastboot\b""") to "Perintah fastboot dijalankan dari komputer, bukan di sini",
        Regex("""\b(wipe|format)\s+data\b""") to "Menghapus data",
        Regex("""\bsetenforce\s+0\b""") to "Menonaktifkan SELinux",
        Regex("""\bresetprop\b""") to "Mengubah properti read-only"
    )

    /** @return alasan jika diblokir, null jika lolos. */
    fun check(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return "Perintah kosong"
        if (c.length > 2000) return "Perintah terlalu panjang"
        return blocked.firstOrNull { it.first.containsMatchIn(c) }?.second
    }
}
