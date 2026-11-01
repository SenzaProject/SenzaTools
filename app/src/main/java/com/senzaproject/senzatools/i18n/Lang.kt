package com.senzaproject.senzatools.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** Satu pilihan bahasa di Settings. */
data class AppLanguage(val code: String, val nativeName: String)

/**
 * Mesin bahasa ringan untuk Senza Tools.
 *
 * - Teks di kode ditulis dalam bahasa Indonesia (bahasa sumber, kode "id").
 * - Terjemahan ada di assets/lang/<kode>.json, bentuknya { "teks Indonesia": "terjemahan" }.
 * - Teks bervariabel memakai {} sebagai penanda, mis. "Disalin: {}" → "Copied: {}".
 * - Kunci "@name" berisi nama bahasa yang tampil di daftar.
 * - Teks yang belum diterjemahkan jatuh ke Inggris (Melayu jatuh ke Indonesia), lalu ke teks sumber.
 * - Menambah bahasa baru: cukup taruh assets/lang/xx.json (tanpa mengubah kode).
 *
 * Status bahasa adalah Compose state, jadi seluruh UI otomatis tergambar ulang saat bahasa diganti.
 */
object Lang {
    const val SYSTEM = "system"
    private const val SOURCE = "id"
    private const val PREFS = "senza_ui"
    private const val KEY = "lang"
    private const val DIR = "lang"

    private val builtIn = listOf(
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("en", "English"),
        AppLanguage("ms", "Bahasa Melayu"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("pt", "Português"),
        AppLanguage("ru", "Русский"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
        AppLanguage("zh", "简体中文"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("ar", "العربية")
    )

    /** Bahasa yang mirip Indonesia: bila terjemahan kosong, tampilkan teks sumber, bukan Inggris. */
    private val fallbackToSource = setOf("ms")

    private class Pat(val key: String, val value: String) {
        private val parts: List<String> = key.split("{}")
        val slots: Int = parts.size - 1
        val prefix: String = parts.first()
        val suffix: String = parts.last()
        val literal: Int = key.length - 2 * slots
        val minLen: Int = literal + slots
        /** Pola sangat pendek (mis. "Baris {}") hanya cocok untuk isian satu kata. */
        val strict: Boolean = literal <= 6
        val regex = Regex(parts.joinToString("(.+?)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)

        fun fill(m: MatchResult, exact: Map<String, String>): String {
            val sb = StringBuilder()
            var from = 0
            var g = 1
            while (true) {
                val j = value.indexOf("{}", from)
                if (j < 0) {
                    sb.append(value, from, value.length)
                    break
                }
                sb.append(value, from, j)
                val cap = m.groupValues[g++]
                sb.append(exact[cap] ?: cap)
                from = j + 2
            }
            return sb.toString()
        }
    }

    private class State(
        val pref: String,
        val code: String,
        val exact: Map<String, String>,
        val patterns: List<Pat>
    )

    private var state by mutableStateOf(State(SYSTEM, SOURCE, emptyMap(), emptyList()))

    /** Daftar bahasa yang tersedia (bawaan + file tambahan di assets/lang). */
    var languages: List<AppLanguage> = builtIn
        private set

    /** Pilihan pengguna: [SYSTEM] atau kode bahasa. */
    val selected: String get() = state.pref

    /** Kode bahasa yang benar-benar dipakai sekarang. */
    val active: String get() = state.code

    fun init(context: Context) {
        val c = context.applicationContext
        languages = builtIn + discoverExtras(c)
        val pref = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
        state = build(c, pref)
    }

    fun set(context: Context, pref: String) {
        val c = context.applicationContext
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pref).apply()
        state = build(c, pref)
    }

    /** Nama yang ditampilkan pada tombol bahasa di Settings. */
    fun displayName(): String {
        val st = state
        val name = languages.firstOrNull { it.code == st.code }?.nativeName ?: st.code
        return if (st.pref == SYSTEM) tr("Ikuti sistem") + " · " + name else name
    }

    /** Terjemahkan [s] (teks sumber bahasa Indonesia). Aman dipanggil dari mana saja. */
    fun tr(s: String): String {
        val st = state
        if (st.code == SOURCE || s.isEmpty()) return s
        st.exact[s]?.let { return it }
        if (s.length > 400) return s
        for (p in st.patterns) {
            if (s.length < p.minLen || !s.startsWith(p.prefix) || !s.endsWith(p.suffix)) continue
            val m = p.regex.matchEntire(s) ?: continue
            if (p.strict && (1..p.slots).any { i -> m.groupValues[i].any { ch -> ch.isWhitespace() } }) continue
            return p.fill(m, st.exact)
        }
        return s
    }

    // ------------------------------------------------------------------ internal

    private fun resolve(pref: String): String {
        if (pref != SYSTEM) return if (languages.any { it.code == pref }) pref else "en"
        val sys = when (val l = Locale.getDefault().language) {
            "in" -> "id"
            "iw" -> "he"
            else -> l
        }
        return if (languages.any { it.code == sys }) sys else "en"
    }

    private fun build(c: Context, pref: String): State {
        val code = resolve(pref)
        if (code == SOURCE) return State(pref, code, emptyMap(), emptyList())
        val merged = HashMap<String, String>()
        if (code != "en" && code !in fallbackToSource) merged.putAll(read(c, "en"))
        merged.putAll(read(c, code))
        val exact = HashMap<String, String>()
        val pats = ArrayList<Pat>()
        for ((k, v) in merged) {
            if (k.startsWith("@")) continue
            if (k.contains("{}")) {
                if (k.split("{}").size == v.split("{}").size) pats.add(Pat(k, v))
            } else {
                exact[k] = v
            }
        }
        pats.sortByDescending { it.literal }
        return State(pref, code, exact, pats)
    }

    private fun read(c: Context, code: String): Map<String, String> = try {
        c.assets.open("$DIR/$code.json").bufferedReader(Charsets.UTF_8).use { r ->
            val o = JSONObject(r.readText())
            val m = HashMap<String, String>(o.length() * 2)
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                m[k] = o.optString(k)
            }
            m
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun discoverExtras(c: Context): List<AppLanguage> = try {
        (c.assets.list(DIR) ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .filter { code -> builtIn.none { it.code == code } }
            .map { code -> AppLanguage(code, read(c, code)["@name"] ?: code.uppercase()) }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Pintasan: terjemahkan teks sumber sesuai bahasa yang dipilih. */
fun tr(s: String): String = Lang.tr(s)
