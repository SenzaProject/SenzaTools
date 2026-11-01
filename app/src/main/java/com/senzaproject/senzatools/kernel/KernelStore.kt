package com.senzaproject.senzatools.kernel

import android.content.Context
import org.json.JSONObject

/**
 * Penyimpanan lokal Kernel Manager (SharedPreferences, privat aplikasi, tidak ikut backup):
 *  - stock   : nilai ASLI tiap path sebelum pertama kali diubah aplikasi (kunci = fingerprint + kernel; reset saat ROM/kernel berganti)
 *  - undo    : nilai sebelum penerapan terakhir
 *  - snapshots: profil bernama buatan pengguna (maks [MAX_SNAPSHOTS])
 *  - touched : id node yang pernah diubah aplikasi (sumber konfigurasi boot)
 */
class KernelStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("senza_kernel", Context.MODE_PRIVATE)

    companion object { const val MAX_SNAPSHOTS = 10 }

    private fun map(key: String): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
            o.keys().forEach { k -> out[k] = o.getString(k) }
        }
        return out
    }

    private fun put(key: String, m: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(m as Map<*, *>).toString()).apply()
    }

    /** Dipanggil saat pemindaian; bila identitas ROM/kernel berubah, nilai stock lama dibuang. */
    fun ensureStockKey(key: String) {
        if (prefs.getString("stock_key", null) != key) {
            prefs.edit().putString("stock_key", key).putString("stock", "{}").apply()
        }
    }

    fun stock(): Map<String, String> = map("stock")

    fun recordStock(path: String, value: String) {
        val m = map("stock")
        if (path !in m) { m[path] = value; put("stock", m) }
    }

    fun undo(): Map<String, String> = map("undo")
    fun setUndo(m: Map<String, String>) = put("undo", m)

    fun snapshots(): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, Map<String, String>>()
        runCatching {
            val o = JSONObject(prefs.getString("snapshots", "{}") ?: "{}")
            o.keys().forEach { name ->
                val inner = o.getJSONObject(name)
                out[name] = inner.keys().asSequence().associateWith { inner.getString(it) }
            }
        }
        return out
    }

    /** @return false bila nama kosong/terlalu panjang atau batas tercapai (nama yang sama menimpa). */
    fun saveSnapshot(name: String, values: Map<String, String>): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 40) return false
        val all = snapshots().toMutableMap()
        if (n !in all && all.size >= MAX_SNAPSHOTS) return false
        all[n] = values
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, JSONObject(v as Map<*, *>)) }
        prefs.edit().putString("snapshots", o.toString()).apply()
        return true
    }

    fun deleteSnapshot(name: String) {
        val all = snapshots().toMutableMap()
        if (all.remove(name) == null) return
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, JSONObject(v as Map<*, *>)) }
        prefs.edit().putString("snapshots", o.toString()).apply()
    }

    fun touched(): Set<String> = prefs.getStringSet("touched", emptySet()).orEmpty()
    fun addTouched(ids: Collection<String>) {
        prefs.edit().putStringSet("touched", touched() + ids).apply()
    }
}
