package com.senzaproject.senzaai.analyzer.rom

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.senzaproject.senzaai.analyzer.apk.ElfAnalyzer
import com.senzaproject.senzaai.analyzer.apk.NativeLib
import com.senzaproject.senzaai.analyzer.system.InitRcAnalyzer
import com.senzaproject.senzaai.analyzer.system.PropAnalyzer
import com.senzaproject.senzaai.analyzer.system.PropParseResult
import com.senzaproject.senzaai.analyzer.system.RcAnalysis
import com.senzaproject.senzaai.analyzer.system.XmlAnalysis
import com.senzaproject.senzaai.analyzer.system.XmlAnalyzer
import com.senzaproject.senzaai.utils.Saf
import com.senzaproject.senzaai.utils.formatBytes
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class RomReport(
    val sourceName: String,
    val kind: String,
    val entryCount: Int,
    val packaging: List<String>,
    val images: List<String>,
    val partitions: List<String>,
    val topLevel: List<Pair<String, Int>>,
    val metadata: Map<String, String>,
    val buildProps: List<Pair<String, PropParseResult>>,
    val mergedProps: Map<String, String>,
    val initRc: List<Pair<String, RcAnalysis>>,
    val xml: List<Pair<String, XmlAnalysis>>,
    val apkCount: Int,
    val nativeLibs: List<NativeLib>,
    val notes: List<String>,
    val stoppedEarly: Boolean
) {
    val libs64Bad: Int get() = nativeLibs.count { it.elf?.is64 == true && it.elf?.ok16k == false }
    val libs64Total: Int get() = nativeLibs.count { it.elf?.is64 == true }

    fun summary(): String = buildString {
        appendLine("ROM: $sourceName ($kind), $entryCount entri")
        appendLine("Pengemasan: ${packaging.joinToString("; ").ifBlank { "tidak terdeteksi" }}")
        appendLine("Image: ${images.take(30).joinToString().ifBlank { "-" }}")
        appendLine("Partisi/folder: ${partitions.joinToString().ifBlank { "-" }}")
        if (metadata.isNotEmpty()) appendLine("Metadata OTA: " + metadata.entries.take(12).joinToString { "${it.key}=${it.value}" })
        listOf("ro.build.version.sdk", "ro.build.version.release", "ro.build.fingerprint", "ro.product.device", "ro.vndk.version", "ro.product.cpu.abilist")
            .forEach { k -> mergedProps[k]?.let { appendLine("$k=$it") } }
        appendLine("Native lib dipindai: ${nativeLibs.size}; 64-bit tidak selaras 16 KB: $libs64Bad/$libs64Total")
        initRc.sumOf { it.second.issues.size }.let { appendLine("Temuan init.rc: $it") }
        notes.forEach { appendLine("- $it") }
    }
}

private val KNOWN_PARTS = setOf(
    "system", "system_ext", "product", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm",
    "boot", "init_boot", "vendor_boot", "recovery", "super", "vbmeta", "vbmeta_system", "vbmeta_vendor",
    "dtbo", "modem", "persist", "metadata", "userdata", "cache"
)

/** Pemindai ROM: bekerja sebagai pengumpul; sumber (ZIP stream / pohon SAF) memanggil [offer]. */
private class RomScan(val name: String, val kind: String) {
    var entries = 0
    val allNames = HashSet<String>()
    val images = mutableListOf<String>()
    val top = linkedMapOf<String, Int>()
    val props = mutableListOf<Pair<String, PropParseResult>>()
    val rc = mutableListOf<Pair<String, RcAnalysis>>()
    val xml = mutableListOf<Pair<String, XmlAnalysis>>()
    val libs = mutableListOf<NativeLib>()
    val metadata = linkedMapOf<String, String>()
    val notes = mutableListOf<String>()
    var apks = 0

    private fun read(open: () -> InputStream?, closeAfter: Boolean, max: Int): ByteArray? {
        val s = try {
            open()
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            Saf.readLimited(s, max)
        } catch (_: Throwable) {
            null
        } finally {
            if (closeAfter) try {
                s.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun offer(path: String, size: Long, closeAfter: Boolean, open: () -> InputStream?) {
        entries++
        val lower = path.lowercase()
        val base = lower.substringAfterLast('/')
        val first = path.substringBefore('/', "")
        if (first.isNotEmpty()) top[first] = (top[first] ?: 0) + 1 else top["(root)"] = (top["(root)"] ?: 0) + 1
        if (allNames.size < 4000) allNames.add(lower)

        if (!path.contains('/') && (base.endsWith(".img") || base == "payload.bin" || base.endsWith(".new.dat") || base.endsWith(".new.dat.br") || base.endsWith(".transfer.list"))) {
            images += "$path (${size.coerceAtLeast(0).formatBytes()})"
        }
        when {
            base in setOf("build.prop", "system.prop", "vendor.prop", "odm.prop", "product.prop", "default.prop") && props.size < 24 -> {
                val b = read(open, closeAfter, 2 * 1024 * 1024) ?: return
                props += path to PropAnalyzer.parse(b.toString(Charsets.UTF_8))
            }
            base.endsWith(".rc") && rc.size < 150 -> {
                val b = read(open, closeAfter, 1024 * 1024) ?: return
                rc += path to InitRcAnalyzer.analyze(b.toString(Charsets.UTF_8))
            }
            base.endsWith(".xml") && xml.size < 40 &&
                (base.startsWith("manifest") || base.startsWith("compatibility_matrix") || lower.contains("vintf/") || base == "androidmanifest.xml") -> {
                val b = read(open, closeAfter, 512 * 1024) ?: return
                xml += path to XmlAnalyzer.analyze(b.toString(Charsets.UTF_8))
            }
            base.endsWith(".so") && libs.size < 400 -> {
                val b = read(open, closeAfter, ElfAnalyzer.HEAD_BYTES) ?: return
                val elf = ElfAnalyzer.parse(b)
                libs += NativeLib(path, elf?.machine ?: "?", size, elf)
            }
            lower == "meta-inf/com/android/metadata" -> {
                val b = read(open, closeAfter, 64 * 1024) ?: return
                b.toString(Charsets.UTF_8).lineSequence().forEach { l ->
                    val i = l.indexOf('=')
                    if (i > 0) metadata[l.substring(0, i).trim()] = l.substring(i + 1).trim()
                }
            }
            base.endsWith(".apk") -> apks++
        }
    }

    fun build(stoppedEarly: Boolean): RomReport {
        val pack = mutableListOf<String>()
        val has = { n: String -> allNames.contains(n) }
        if (has("payload.bin")) pack += "OTA A/B (payload.bin)"
        if (allNames.any { it.startsWith("super") && it.endsWith(".img") }) pack += "Dynamic partitions (super.img)"
        if (allNames.any { it.endsWith(".new.dat") || it.endsWith(".new.dat.br") }) pack += "OTA berbasis blok (new.dat + transfer.list)"
        if (has("meta-inf/com/google/android/updater-script") || has("meta-inf/com/google/android/update-binary")) pack += "Paket recovery (updater-script/update-binary)"
        if (allNames.any { it.endsWith("flash_all.sh") || it.endsWith("flash_all.bat") }) pack += "Paket fastboot (flash_all)"
        if (allNames.any { it == "system/build.prop" || it == "system/system/build.prop" || it == "build.prop" }) pack += "Isi ROM terekstrak (system/build.prop terbaca)"
        val parts = (images.map { it.substringBefore(' ').removeSuffix(".img").removeSuffix(".new.dat").removeSuffix(".br").removeSuffix(".new.dat") } +
            top.keys.map { it.lowercase() }).filter { it in KNOWN_PARTS }.distinct()

        val merged = linkedMapOf<String, String>()
        props.sortedBy { if (it.first.lowercase().startsWith("system/")) 0 else 1 }.forEach { (_, r) ->
            r.props.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        }
        val n = notes.toMutableList()
        if (has("payload.bin") && props.isEmpty()) {
            n += "payload.bin hanya berisi image terkompresi; isi partisi (build.prop, init.rc, library) tidak dapat dibaca langsung. Ekstrak payload di PC (mis. payload-dumper-go), lalu pilih folder hasilnya untuk analisis mendalam."
        }
        if (metadata.isNotEmpty()) {
            fun m(k: String) = metadata[k]
            m("post-sdk-level")?.let { merged.putIfAbsent("ro.build.version.sdk", it) }
            m("post-build")?.let { merged.putIfAbsent("ro.build.fingerprint", it) }
            m("post-security-patch-level")?.let { merged.putIfAbsent("ro.build.version.security_patch", it) }
            m("pre-device")?.let { merged.putIfAbsent("ro.product.device", it) }
            n += "Sebagian properti diambil dari metadata OTA (post-build, post-sdk-level, dst.), bukan dari build.prop."
        }
        if (props.isEmpty() && !has("payload.bin")) n += "Tidak ditemukan build.prop pada ROM ini."
        if (stoppedEarly) n += "Pemindaian berhenti sebelum selesai (batas entri tercapai, file rusak, atau dibatalkan); hasil bersifat parsial."
        return RomReport(
            sourceName = name, kind = kind, entryCount = entries, packaging = pack, images = images, partitions = parts,
            topLevel = top.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value },
            metadata = metadata, buildProps = props, mergedProps = merged, initRc = rc, xml = xml,
            apkCount = apks, nativeLibs = libs, notes = n, stoppedEarly = stoppedEarly
        )
    }
}

class RomAnalyzer(private val ctx: Context) {

    /** Membaca ZIP secara streaming tanpa menyalin seluruh file ke penyimpanan. */
    fun analyzeZip(uri: Uri, name: String, isActive: () -> Boolean, onProgress: (String) -> Unit): RomReport {
        val scan = RomScan(name, "ZIP")
        var stopped = false
        val raw = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Tidak dapat membuka file")
        ZipInputStream(BufferedInputStream(raw, 128 * 1024)).use { zis ->
            try {
                while (true) {
                    if (!isActive()) {
                        stopped = true
                        break
                    }
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    scan.offer(e.name, e.size, false) { zis }
                    if (scan.entries % 200 == 0) onProgress("${scan.entries} entri dipindai…")
                    if (scan.entries >= 60_000) {
                        stopped = true
                        break
                    }
                }
            } catch (t: Throwable) {
                stopped = true
                scan.notes += "Pembacaan ZIP berhenti: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        return scan.build(stopped)
    }

    /** Memindai folder ROM terekstrak yang dipilih lewat Storage Access Framework. */
    fun analyzeTree(treeUri: Uri, name: String, isActive: () -> Boolean, onProgress: (String) -> Unit): RomReport {
        val scan = RomScan(name, "Folder")
        var stopped = false
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        data class Node(val id: String, val path: String, val depth: Int)

        val queue = ArrayDeque<Node>()
        queue.add(Node(rootId, "", 0))
        try {
            while (queue.isNotEmpty()) {
                if (!isActive()) {
                    stopped = true
                    break
                }
                val node = queue.removeFirst()
                val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.id)
                ctx.contentResolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val n = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        val p = if (node.path.isEmpty()) n else node.path + "/" + n
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (node.depth < 9) queue.add(Node(id, p, node.depth + 1))
                        } else {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            scan.offer(p, size, true) { ctx.contentResolver.openInputStream(docUri) }
                            if (scan.entries % 200 == 0) onProgress("${scan.entries} file dipindai…")
                        }
                    }
                }
                if (scan.entries >= 40_000) {
                    stopped = true
                    break
                }
            }
        } catch (t: Throwable) {
            stopped = true
            scan.notes += "Pemindaian folder berhenti: ${t.message ?: t.javaClass.simpleName}"
        }
        return scan.build(stopped)
    }
}
