package com.senzaproject.senzatools.platform.flashtool

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Pencarian file DA/preloader di folder firmware milik pengguna sendiri, untuk device yang
 * HW code-nya belum ada di FirmwareCatalog bawaan APK.
 *
 * Cara kerja: telusuri folder (rekursif, dibatasi kedalaman supaya tidak muter tanpa akhir di
 * folder raksasa), coba parse tiap file ".bin" sebagai file DA lewat DaFile.parse() — kalau
 * berhasil dan ada entri yang HW code-nya cocok dengan device yang sedang aktif, itu kandidat DA.
 * File yang namanya mengandung "preloader" (tanpa peduli besar-kecil huruf) ditandai sebagai
 * kandidat preloader berdasarkan nama saja (tidak diparse — karena beda device punya layout
 * preloader yang beda-beda, satu-satunya cara aman mengecek valid-tidaknya ya lewat PreloaderFile
 * saat benar-benar dipakai kirim EMI).
 *
 * CATATAN JUJUR: ini pencarian heuristik, bukan database resmi. Hasil "kandidat preloader" HANYA
 * berdasar nama file — validitas sebenarnya baru diketahui saat PreloaderFile.extractEmi() dicoba.
 */
object FolderFirmwareSearch {

    data class DaCandidate(val name: String, val uri: Uri, val hwCode: Int, val daId: String)
    data class PreloaderCandidate(val name: String, val uri: Uri)

    data class SearchResult(
        val daCandidates: List<DaCandidate>,
        val preloaderCandidates: List<PreloaderCandidate>,
        val filesScanned: Int,
        val tooDeep: Boolean
    )

    private const val MAX_DEPTH = 4
    private const val MAX_FILES = 500

    fun search(ctx: Context, treeUri: Uri, targetHwCode: Int): SearchResult {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: return SearchResult(emptyList(), emptyList(), 0, false)
        val daCandidates = mutableListOf<DaCandidate>()
        val preloaderCandidates = mutableListOf<PreloaderCandidate>()
        var scanned = 0
        var tooDeep = false

        fun walk(dir: DocumentFile, depth: Int) {
            if (scanned >= MAX_FILES) return
            if (depth > MAX_DEPTH) { tooDeep = true; return }
            for (child in dir.listFiles()) {
                if (scanned >= MAX_FILES) return
                if (child.isDirectory) {
                    walk(child, depth + 1)
                    continue
                }
                val name = child.name ?: continue
                if (!name.endsWith(".bin", ignoreCase = true)) continue
                scanned++

                if (name.contains("preloader", ignoreCase = true)) {
                    preloaderCandidates.add(PreloaderCandidate(name, child.uri))
                    continue // file preloader tidak perlu/tidak bisa diparse sebagai DaFile
                }

                val bytes = try {
                    ctx.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                } catch (_: Throwable) { null } ?: continue

                when (val parsed = DaFile.parse(bytes)) {
                    is DaFile.ParseResult.Ok -> {
                        val match = parsed.entries.firstOrNull { it.hwCode == targetHwCode }
                        if (match != null) {
                            daCandidates.add(DaCandidate(name, child.uri, match.hwCode, parsed.daId))
                        }
                    }
                    is DaFile.ParseResult.Error -> { /* bukan file DA yang valid, lewati diam-diam */ }
                }
            }
        }
        walk(root, 0)
        return SearchResult(daCandidates, preloaderCandidates, scanned, tooDeep)
    }
}
