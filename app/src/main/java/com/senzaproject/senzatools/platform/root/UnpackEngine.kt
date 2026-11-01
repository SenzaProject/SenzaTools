package com.senzaproject.senzatools.platform.root

import android.content.Context
import com.senzaproject.senzatools.data.OperationLog
import com.senzaproject.senzatools.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Mesin fitur "Unpack & Repack": membongkar dan merakit ulang image ROM (payload.bin, super.img, ext4/erofs/f2fs,
 * .dat/.br, .zst) langsung di HP. Tool biner (dna, lpmake, mkfs.erofs, magiskboot, zstd, dll) dibundel di
 * assets/unpackrepack/tools dan dijalankan lewat root.
 *
 * Nama variabel lingkungan (DNA_PRO, DNA_DRO, DNA_DIR, DNA_TMP) dipertahankan karena dibaca oleh biner `dna`.
 */
class UnpackEngine(
    private val ctx: Context,
    private val access: AccessManager,
    private val log: OperationLog
) {
    companion object {
        /** Naikkan angka ini bila isi assets/unpackrepack/tools berubah, supaya tool disalin ulang. */
        private const val TOOLS_REV = 1
        const val BASE_DIR = "/sdcard/Senza/UnpackRepack"
        const val WORK_DIR = "/data/senza_unpackrepack"
        const val PROJECT_PREFIX = "DNA_"
        private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,48}")

        /** Kutip aman untuk shell (tanda kutip tunggal). */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun cleanProjectName(raw: String): String? {
            val n = raw.trim().replace(' ', '_')
            return if (SAFE_NAME.matches(n)) n else null
        }
    }

    private val toolsDir = File(ctx.filesDir, "unpackrepack/bin")
    private val tmpDir = File(ctx.cacheDir, "unpackrepack_tmp")
    private val current = AtomicReference<Process?>(null)

    val rootReady: Boolean get() = access.isRootVerified

    /** Menyalin tool dari assets ke penyimpanan internal aplikasi (sekali, atau saat TOOLS_REV naik). */
    suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val marker = File(toolsDir, ".rev")
            if (marker.exists() && marker.readText().trim() == TOOLS_REV.toString()) {
                return@withContext Result.success(Unit)
            }
            toolsDir.mkdirs()
            tmpDir.mkdirs()
            val names = ctx.assets.list("unpackrepack/tools") ?: emptyArray()
            for (n in names) {
                val dest = File(toolsDir, n)
                ctx.assets.open("unpackrepack/tools/$n").use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                dest.setReadable(true, false)
                dest.setExecutable(true, false)
            }
            marker.writeText(TOOLS_REV.toString())
            log.add("UNPACK", "menyalin ${names.size} tool ke ${toolsDir.path}", "ok")
            Result.success(Unit)
        } catch (e: Throwable) {
            log.add("UNPACK", "gagal menyalin tool", e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /** Prelude shell: PATH, direktori kerja, dan variabel yang dibutuhkan biner dna. */
    private fun prelude(project: String?): String {
        val sb = StringBuilder()
        sb.append("export PATH=${q(toolsDir.path)}:\$PATH\n")
        sb.append("chmod -R 755 ${q(toolsDir.path)} 2>/dev/null\n")
        sb.append("export TMPDIR=${q(tmpDir.path)}; mkdir -p \"\$TMPDIR\"\n")
        sb.append("export START_DIR=${q(ctx.filesDir.path)}\n")
        sb.append("export DNA_DIR=${q(BASE_DIR)}; mkdir -p \"\$DNA_DIR\"\n")
        // Folder dibuat oleh root; buka aksesnya agar file manager/Termux bisa melihat dan mengisi.
        sb.append("chmod 777 \"\$(dirname \"\$DNA_DIR\")\" \"\$DNA_DIR\" 2>/dev/null\n")
        sb.append("export DNA_TMP=${q(WORK_DIR)}; mkdir -p \"\$DNA_TMP\"\n")
        if (project != null) {
            sb.append("export DNA_PRO=\"\$DNA_DIR/$project\"; mkdir -p \"\$DNA_PRO\"\n")
            sb.append("export DNA_DRO=\"\$DNA_TMP/$project\"; mkdir -p \"\$DNA_DRO\"\n")
            sb.append("cd \"\$DNA_PRO\"\n")
        }
        return sb.toString()
    }

    /**
     * Menjalankan skrip lewat `su`, mengalirkan output baris demi baris ke [onLine].
     * @return kode keluar (-1 bila gagal dijalankan atau dibatalkan).
     */
    suspend fun run(project: String?, script: String, label: String, quiet: Boolean = false, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            if (!access.isRootVerified) {
                onLine("! Akses root belum diverifikasi. Buka Tools > Akses lalu minta izin root.")
                return@withContext -1
            }
            if (project != null && !SAFE_NAME.matches(project)) {
                onLine("! Nama proyek tidak valid: $project")
                return@withContext -1
            }
            val r = prepare()
            if (r.isFailure) {
                onLine("! Gagal menyiapkan tool: ${r.exceptionOrNull()?.message}")
                return@withContext -1
            }
            var code = -1
            try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                current.set(p)
                p.outputStream.bufferedWriter().use { w ->
                    w.write(prelude(project))
                    w.write(script)
                    w.write("\nexit \$?\n")
                }
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine(it) } }
                code = p.waitFor()
            } catch (e: Throwable) {
                onLine("! ${e.message ?: e.javaClass.simpleName}")
            } finally {
                current.set(null)
            }
            if (!quiet) log.add("UNPACK", "$label (exit=$code)", if (code == 0) "ok" else "gagal")
            code
        }

    /** Menghentikan proses yang sedang berjalan. */
    fun cancel() {
        current.getAndSet(null)?.destroyForcibly()
    }

    /** Menjalankan skrip singkat dan mengembalikan keluarannya (untuk daftar pilihan). */
    suspend fun capture(project: String?, script: String): List<String> {
        val out = mutableListOf<String>()
        run(project, script, "daftar", quiet = true) { out.add(it) }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ Proyek

    suspend fun listProjects(): List<String> =
        capture(null, "ls -d \"\$DNA_DIR\"/$PROJECT_PREFIX* 2>/dev/null | while read p; do basename \"\$p\"; done")

    fun newProjectScript(name: String): String = """
        T=${q(name)}
        if [ -d "${'$'}DNA_DIR/$PROJECT_PREFIX${'$'}T" ]; then
          echo "> Proyek sudah ada, nama diubah otomatis"
          T="${'$'}T-${'$'}(date +%Y%m%d%H%M%S)"
        fi
        echo "> Membuat: $PROJECT_PREFIX${'$'}T"
        mkdir -p "${'$'}DNA_DIR/$PROJECT_PREFIX${'$'}T"
        chmod 777 "${'$'}DNA_DIR/$PROJECT_PREFIX${'$'}T"
        echo "> Selesai: $PROJECT_PREFIX${'$'}T"
    """.trimIndent()

    fun deleteProjectScript(names: List<String>): String = names.joinToString("\n") { n ->
        val safe = if (SAFE_NAME.matches(n)) n else ""
        if (safe.isEmpty()) "echo '! Nama tidak valid, dilewati'"
        else "echo '> Menghapus: $safe'; rm -rf \"\$DNA_DIR/$safe\" \"\$DNA_TMP/$safe\"; echo '> Terhapus'"
    }

    // ------------------------------------------------------------------ Daftar file (setara findfile.sh)

    /** Pasangan nilai|label. */
    suspend fun listFiles(project: String?, kind: String): List<Pair<String, String>> {
        val script = when (kind) {
            "zip" -> "ls \"\$DNA_DIR\" | grep -v '/\$' | grep '\\.zip\$'"
            "br" -> "ls \"\$DNA_PRO\" | grep -v '/\$' | grep '\\.br\$'"
            "dat" -> "ls \"\$DNA_PRO\" | grep -v '/\$' | grep '\\.new\\.dat\$'"
            "img" -> """
                for i in ${'$'}(ls "${'$'}DNA_PRO" | grep -v '/${'$'}' | grep '\.img${'$'}'); do
                  if [ -f "${'$'}DNA_PRO/${'$'}i" ]; then
                    info=${'$'}(dna gettype "${'$'}DNA_PRO/${'$'}i")
                    echo "${'$'}i|${'$'}i (${'$'}info)"
                  fi
                done
            """.trimIndent()
            "dir" -> "ls -F \"\$DNA_DRO\" | sed 's/\\/\$//g' | grep -v config"
            "zst_img" -> "ls \"\$DNA_PRO\" | grep -v '/\$' | grep -E '\\.(zst|zstd|img)\$'"
            "split_sparse" -> """
                find "${'$'}DNA_PRO" -maxdepth 1 -type f -exec basename {} \; |
                  sed -n 's/\(.*\)\.[0-9]\{1,\}${'$'}/\1/p; t; d' | sort | uniq -c |
                  while read -r count prefix; do
                    printf "%s|%s (%d bagian)\n" "${'$'}prefix" "${'$'}prefix" "${'$'}count"
                  done
            """.trimIndent()
            else -> "ls \"\$DNA_PRO\" | grep -v '/\$' | grep '\\.$kind\$'"
        }
        return capture(project, script).filter { !it.startsWith("!") }.map {
            val i = it.indexOf('|')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else it to it
        }
    }

    suspend fun listPayloadPartitions(project: String): List<String> =
        capture(project, "[ -f \"\$DNA_PRO/payload.bin\" ] && dna dumper --list \"\$DNA_PRO/payload.bin\"")

    suspend fun listSuperPartitions(project: String): List<String> =
        capture(project, "[ -f \"\$DNA_PRO/super.img\" ] && dna lpunpack --list \"\$DNA_PRO/super.img\"")

    suspend fun fileExists(project: String, name: String): Boolean =
        capture(project, "[ -f \"\$DNA_PRO/$name\" ] && echo yes").any { it == "yes" }

    // ------------------------------------------------------------------ Unzip & Unpack

    private fun flag(b: Boolean) = if (b) "1" else "0"
    private fun join(v: List<String>) = v.joinToString(",")

    fun unzipRomScript(zip: String, deleteSource: Boolean) =
        "dna unzip --delete ${flag(deleteSource)} \"\$DNA_DIR\"/${q(zip)} \"\$DNA_DIR\""

    fun unpackPayloadScript(parts: List<String>, deleteSource: Boolean, auto: Boolean) =
        "dna dumper --partition ${q(join(parts))} --delete ${flag(deleteSource)} --auto ${flag(auto)} \"\$DNA_PRO/payload.bin\""

    fun unpackExtractScript(files: List<String>, deleteSource: Boolean) =
        "dna extract ${q(join(files))} --delete ${flag(deleteSource)}"

    fun unpackSuperScript(parts: List<String>, deleteSource: Boolean, auto: Boolean) =
        "dna lpunpack --partition ${q(join(parts))} --delete ${flag(deleteSource)} --auto ${flag(auto)} \"\$DNA_PRO/super.img\""

    // ------------------------------------------------------------------ Repack

    data class RepackOptions(
        val dirs: List<String>,
        val target: Int,          // 0 ext4, 1 erofs, 2 f2fs
        val readWrite: Int,       // 1 rw, 0 ro
        val autoSize: Int,        // 1 otomatis, 0 ukuran asli
        val sparse: Int,          // 0 raw, 1 sparse
        val outFormat: Int,       // 0 img, 1 dat, 2 br
        val brLevel: Int,
        val erofsLevel: Int,
        val erofsMode: String,    // lz4hc | lz4 | lzma
        val shrinkExt4: Boolean,
        val removeAvb: Boolean
    )

    fun repackScript(o: RepackOptions) =
        "dna repack ${q(join(o.dirs))} --read ${o.readWrite} --auto ${o.autoSize} --format ${o.sparse} " +
            "--type ${o.outFormat} --br ${o.brLevel} --erofs ${o.erofsLevel} --mode ${o.erofsMode} " +
            "--compress ${flag(o.shrinkExt4)} --delavb ${flag(o.removeAvb)} --convert ${o.target}"

    fun repackSuperScript(type: String, sparse: Int, sizeGb: String, group: String, imgs: List<String>) =
        "dna lpmake --type ${q(type)} --format $sparse --super_size ${q(sizeGb)} --super_group ${q(group)} ${q(join(imgs))}"

    // ------------------------------------------------------------------ Konversi

    fun imgToSimgScript(imgs: List<String>, deleteSource: Boolean): String = """
        mkdir -p "${'$'}DNA_PRO/out"
        for i in ${imgs.joinToString(" ") { q(it) }}; do
          info=${'$'}(dna gettype "${'$'}DNA_PRO/${'$'}i")
          if [ "${'$'}info" = "ext" ] || [ "${'$'}info" = "erofs" ]; then
            echo "> Mengonversi: ${'$'}i"
            img2simg "${'$'}DNA_PRO/${'$'}i" "${'$'}DNA_PRO/out/${'$'}i"
          elif [ "${'$'}info" = "sparse" ]; then
            echo "> Mengonversi: ${'$'}i"
            simg2img "${'$'}DNA_PRO/${'$'}i" "${'$'}DNA_PRO/out/${'$'}i"
          else
            echo "> Tidak didukung: ${'$'}i"
          fi
          ${if (deleteSource) "echo \"> Menghapus: \$i\"; rm -rf \"\$DNA_PRO/\$i\"" else ":"}
          if [ -f "${'$'}DNA_PRO/out/${'$'}i" ]; then
            echo "> Selesai, file ada di: ${'$'}DNA_PRO/out"
          else
            echo "! Konversi gagal: ${'$'}i"
          fi
        done
    """.trimIndent()

    fun imgToDatBrScript(imgs: List<String>, deleteSource: Boolean, toBr: Boolean, brLevel: Int) =
        "dna convert ${q(join(imgs))} --delete ${flag(deleteSource)} --type ${if (toBr) 1 else 0} --br $brLevel"

    fun zstScript(files: List<String>, deleteSource: Boolean, level: Int): String = """
        mkdir -p "${'$'}DNA_PRO/out"
        for i in ${files.joinToString(" ") { q(it) }}; do
          if [ ! -f "${'$'}DNA_PRO/${'$'}i" ]; then echo "! File tidak ada: ${'$'}i"; continue; fi
          case "${'$'}i" in
            *.zst|*.zstd)
              echo "> Mengekstrak: ${'$'}i"
              line=${'$'}{i%%.*}
              zstd -d -k -f "${'$'}DNA_PRO/${'$'}i" -o "${'$'}DNA_PRO/out/${'$'}line.img"
              echo "> Selesai: ${'$'}DNA_PRO/out/${'$'}line.img" ;;
            *.img)
              echo "> Mengompres: ${'$'}i"
              zstd -$level -T4 -f "${'$'}DNA_PRO/${'$'}i" -o "${'$'}DNA_PRO/out/${'$'}i.zst"
              echo "> Selesai: ${'$'}DNA_PRO/out/${'$'}i.zst" ;;
          esac
          ${if (deleteSource) "echo \"> Menghapus: \$i\"; rm -rf \"\$DNA_PRO/\$i\"" else ":"}
        done
    """.trimIndent()

    fun mergeSparseScript(prefixes: List<String>, deleteSource: Boolean): String = """
        mkdir -p "${'$'}DNA_PRO/out"
        cd "${'$'}DNA_PRO"
        for prefix in ${prefixes.joinToString(" ") { q(it) }}; do
          files=${'$'}(ls | grep -E '\.[0-9]+${'$'}' | grep -F "${'$'}prefix." | sort -t. -k2 -n | tr '\n' ' ')
          echo "> Menggabungkan ke: ${'$'}prefix"
          simg2img ${'$'}files "${'$'}DNA_PRO/out/${'$'}prefix"
          if [ -f "${'$'}DNA_PRO/out/${'$'}prefix" ]; then
            echo "> Selesai: ${'$'}DNA_PRO/out/${'$'}prefix"
            ${if (deleteSource) "rm -f \$files" else ":"}
          else
            echo "! Gagal menggabungkan: ${'$'}prefix"
          fi
        done
    """.trimIndent()

    // ------------------------------------------------------------------ Partisi perangkat (ekstrak / flash)

    /** Pasangan path|label untuk partisi di /dev/block (tanpa uuid/mapper/sd*). */
    suspend fun listBlocks(): List<Pair<String, String>> = capture(null, """
        find /dev/block -mindepth 1 -type l | while read o; do
          [ -d "${'$'}o" ] && continue
          basename "${'$'}o"
        done | sort -u | while read n; do
          b=${'$'}(find /dev/block -name "${'$'}n" | head -n 1)
          case "${'$'}b" in *uuid/*|*mapper/com.*|*/sd*) continue ;; esac
          sz=${'$'}(blockdev --getsize64 "${'$'}b" 2>/dev/null)
          if [ -n "${'$'}sz" ]; then
            echo "${'$'}b|${'$'}n  (${'$'}((sz/1048576)) MB)"
          else
            echo "${'$'}b|${'$'}n"
          fi
        done
    """.trimIndent()).map {
        val i = it.indexOf('|')
        if (i > 0) it.substring(0, i) to it.substring(i + 1) else it to it
    }

    fun extractBlocksScript(blocks: List<String>): String = """
        out="${'$'}DNA_DIR/image"
        mkdir -p "${'$'}out"
        for i in ${blocks.joinToString(" ") { q(it) }}; do
          e=${'$'}{i##*/}
          if [ ! -L "${'$'}i" ]; then
            echo "! Partisi ${'$'}e tidak ditemukan"
          else
            echo "> Mengekstrak partisi ${'$'}e"
            dd if="${'$'}i" of="${'$'}out/${'$'}e.img"
            echo "> Tersimpan: ${'$'}out/${'$'}e.img"
          fi
        done
    """.trimIndent()

    fun flashBlockScript(block: String, imgFile: String, reboot: String?): String = """
        e=${'$'}(basename ${q(block)})
        echo "> Partisi: ${'$'}e"
        echo "> File:" ${q(imgFile)}
        if [ ! -L ${q(block)} ]; then
          echo "! Partisi ${'$'}e tidak ditemukan"
        elif [ ! -f ${q(imgFile)} ]; then
          echo "! File flash tidak ditemukan"
        else
          echo "> Menulis ke partisi ${'$'}e"
          dd if=${q(imgFile)} of=${q(block)} && sync && echo "> Selesai"
          ${if (reboot != null) "echo '> Restart dalam 4 detik'; sleep 4; reboot $reboot" else ":"}
        fi
    """.trimIndent()
}
