package com.senzaproject.senzatools.platform.romflash

import java.nio.charset.StandardCharsets

/**
 * Struct `bootloader_message` — format standar AOSP (bootable/recovery/bootloader_message),
 * dipakai semua Android sejak lama untuk komunikasi bootloader <-> recovery lewat partisi "misc".
 * Ini format PUBLIK yang stabil lintas vendor (bukan dugaan device-specific):
 *
 *   struct bootloader_message {
 *       char command[32];
 *       char status[32];
 *       char recovery[768];
 *       char stage[32];
 *       char reserved[1184];
 *   };  // total 2048 byte
 *
 * Untuk memicu wipe data otomatis lewat recovery: command="boot-recovery",
 * recovery="recovery\n--wipe_data\n" (tiap argumen baris sendiri, diawali baris "recovery").
 * Recovery akan baca ini saat boot, jalankan wipe, lalu reboot balik ke system secara otomatis
 * (perilaku standar recovery.cpp AOSP) — ini bagian paling bisa dipercaya di seluruh fitur Flash ROM.
 */
object BootloaderMessage {

    const val SIZE = 2048

    private fun field(text: String, len: Int): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size < len) { "Teks \"$text\" melebihi kapasitas field ($len byte)" }
        return bytes.copyOf(len) // sisa otomatis 0x00 (null-terminated + padding)
    }

    /** Bangun BCB untuk memicu wipe data (factory reset) via recovery. */
    fun wipeDataMessage(): ByteArray {
        val command = field("boot-recovery", 32)
        val status = field("", 32)
        val recovery = field("recovery\n--wipe_data\n", 768)
        val stage = field("", 32)
        val reserved = ByteArray(1184) // nol semua
        return command + status + recovery + stage + reserved
    }
}
