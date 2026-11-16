package com.senzaproject.senzatools.platform.flashtool

/**
 * Parser file scatter MediaTek (format YAML-ringan "MTK_PLATFORM_CFG" + daftar "partition_index").
 * Hanya MEMBACA teks — tidak menyentuh device. Dipakai tombol "Pilih Scatter" dan "Tinjau".
 *
 * Format yang dikenali (V1.1.x):
 *   - general: MTK_PLATFORM_CFG
 *     info:
 *       - config_version: V1.1.2
 *         platform: MT6789
 *         storage: EMMC
 *   - partition_index: SYS0
 *     partition_name: preloader
 *     file_name: preloader.bin
 *     is_download: true
 *     linear_start_addr: 0x0
 *     partition_size: 0x40000
 *     region: EMMC_BOOT1
 *
 * Baris yang diawali '#' dilewati. Kunci yang tidak dikenal diabaikan.
 */
object ScatterFile {

    data class Partition(
        val index: String,
        val name: String,
        val fileName: String,
        val isDownload: Boolean,
        val linearStart: Long?,
        val size: Long?,
        val region: String
    ) {
        /** Partisi yang punya file image (bukan "NONE") dan ditandai bisa di-download. */
        val flashable: Boolean get() = isDownload && fileName.isNotBlank() && !fileName.equals("NONE", ignoreCase = true)
    }

    data class Parsed(
        val platform: String,
        val project: String,
        val storage: String,
        val configVersion: String,
        val partitions: List<Partition>
    )

    sealed class Result {
        data class Ok(val scatter: Parsed) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(text: String): Result {
        if (text.isBlank()) return Result.Error("File scatter kosong.")
        var platform = ""
        var project = ""
        var storage = ""
        var configVersion = ""
        val parts = mutableListOf<Partition>()

        var cur: MutableMap<String, String>? = null
        fun flush() {
            val m = cur ?: return
            val name = m["partition_name"].orEmpty()
            if (name.isNotBlank()) {
                parts.add(
                    Partition(
                        index = m["partition_index"].orEmpty(),
                        name = name,
                        fileName = m["file_name"].orEmpty(),
                        isDownload = m["is_download"].orEmpty().equals("true", ignoreCase = true),
                        linearStart = parseNum(m["linear_start_addr"]),
                        size = parseNum(m["partition_size"]),
                        region = m["region"].orEmpty()
                    )
                )
            }
            cur = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = line.removePrefix("-").trim()
            val idx = body.indexOf(':')
            if (idx <= 0) continue
            val key = body.substring(0, idx).trim()
            val value = body.substring(idx + 1).trim().trim('"', '\'')

            if (key == "partition_index") {
                flush()
                cur = mutableMapOf("partition_index" to value)
                continue
            }
            val c = cur
            if (c != null) {
                c[key] = value
                continue
            }
            when (key) {
                "platform" -> platform = value
                "project" -> project = value
                "storage" -> storage = value
                "config_version" -> configVersion = value
            }
        }
        flush()

        if (parts.isEmpty()) {
            return Result.Error("Tidak ada partisi yang terbaca. Pastikan ini file scatter MediaTek (*_scatter.txt).")
        }
        return Result.Ok(Parsed(platform, project, storage, configVersion, parts))
    }

    private fun parseNum(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return try {
            if (t.startsWith("0x", ignoreCase = true)) java.lang.Long.parseUnsignedLong(t.substring(2), 16)
            else t.toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun formatSize(bytes: Long?): String {
        if (bytes == null) return "-"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.2f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            bytes >= kb -> "%.0f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }
}
