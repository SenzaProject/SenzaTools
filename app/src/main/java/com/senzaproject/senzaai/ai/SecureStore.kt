package com.senzaproject.senzaai.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Menyimpan rahasia (mis. API key) terenkripsi AES-GCM dengan kunci di Android Keystore. */
class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("senza_secure", Context.MODE_PRIVATE)
    private val alias = "senza_ai_secret_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
        } catch (_: Throwable) {
            prefs.edit().remove(name).apply()
        }
    }

    fun get(name: String): String? {
        val s = prefs.getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }
}
