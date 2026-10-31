package com.senzaproject.senzaai.analyzer.apk

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ElfInfo(
    val is64: Boolean,
    val machine: String,
    val minLoadAlign: Long?,
    val truncated: Boolean
) {
    /** Kompatibel 16 KB bila semua segmen LOAD diselaraskan minimal 16384 (hanya relevan untuk ELF 64-bit). */
    val ok16k: Boolean? get() = when {
        minLoadAlign == null -> null
        else -> minLoadAlign >= 16384L
    }
}

data class NativeLib(val path: String, val abi: String, val size: Long, val elf: ElfInfo?)

data class DexInfo(val name: String, val size: Long, val version: String?, val strings: Long?, val types: Long?, val methods: Long?, val classes: Long?)

object ElfAnalyzer {
    const val HEAD_BYTES = 128 * 1024

    fun readHead(input: InputStream, max: Int = HEAD_BYTES): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n < 0) break
            off += n
        }
        return buf.copyOf(off)
    }

    fun parse(head: ByteArray): ElfInfo? {
        if (head.size < 64) return null
        if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return null
        val is64 = head[4].toInt() == 2
        val order = if (head[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(head).order(order)
        val machine = when (bb.getShort(0x12).toInt() and 0xffff) {
            183 -> "AArch64"
            62 -> "x86-64"
            40 -> "ARM"
            3 -> "x86"
            243 -> "RISC-V"
            else -> "lainnya"
        }
        val phoff: Long
        val phentsize: Int
        val phnum: Int
        if (is64) {
            phoff = bb.getLong(0x20)
            phentsize = bb.getShort(0x36).toInt() and 0xffff
            phnum = bb.getShort(0x38).toInt() and 0xffff
        } else {
            phoff = bb.getInt(0x1C).toLong() and 0xffffffffL
            phentsize = bb.getShort(0x2A).toInt() and 0xffff
            phnum = bb.getShort(0x2C).toInt() and 0xffff
        }
        var minAlign: Long? = null
        var truncated = false
        for (i in 0 until phnum) {
            val off = phoff + i.toLong() * phentsize
            if (off + phentsize > head.size) {
                truncated = true
                break
            }
            val o = off.toInt()
            val type = bb.getInt(o)
            if (type == 1) { // PT_LOAD
                val align = if (is64) bb.getLong(o + 48) else bb.getInt(o + 28).toLong() and 0xffffffffL
                minAlign = if (minAlign == null) align else minOf(minAlign, align)
            }
        }
        return ElfInfo(is64, machine, minAlign, truncated && minAlign == null)
    }

    fun parseDex(name: String, size: Long, head: ByteArray): DexInfo {
        if (head.size < 112 || head[0] != 'd'.code.toByte() || head[1] != 'e'.code.toByte() || head[2] != 'x'.code.toByte()) {
            return DexInfo(name, size, null, null, null, null, null)
        }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val ver = String(head, 4, 3, Charsets.US_ASCII)
        fun u(o: Int) = bb.getInt(o).toLong() and 0xffffffffL
        return DexInfo(name, size, ver, u(56), u(64), u(88), u(96))
    }
}
