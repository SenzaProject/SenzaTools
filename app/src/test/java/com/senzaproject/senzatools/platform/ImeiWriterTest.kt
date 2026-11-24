package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.ImeiValidator
import com.senzaproject.senzatools.platform.root.ImeiWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeiWriterTest {
    // IMEI contoh publik dari spesifikasi Luhn (bukan IMEI perangkat nyata).
    private val good = "490154203237518"

    @Test fun luhnAcceptsValidAndRejectsTypo() {
        assertTrue(ImeiValidator.luhnOk(good))
        assertFalse(ImeiValidator.luhnOk("490154203237519"))
    }

    @Test fun checkRejectsBadInput() {
        assertNull(ImeiValidator.check(good))
        assertNotNull(ImeiValidator.check("1234"))
        assertNotNull(ImeiValidator.check("111111111111111"))
        assertNotNull(ImeiValidator.check("49015420323751a"))
    }

    @Test fun parseEgmrAndSections() {
        val out = listOf("@@DEV /dev/radio/pttycmd1", "@@A1", "+EGMR: \"$good\"", "OK", "@@A2", "ERROR")
        val sec = ImeiWriter.sections(out)
        assertEquals(good, ImeiWriter.parseEgmr(sec["A1"]!!))
        assertNull(ImeiWriter.parseEgmr(sec["A2"]!!))
        assertTrue("OK" in sec["A1"]!!)
    }

    @Test fun briefSummarizesModemReply() {
        assertEquals("tidak ada balasan", ImeiWriter.brief(null))
        assertEquals("tidak ada balasan", ImeiWriter.brief("\n  \n"))
        assertEquals("+EGMR: \"\" | OK", ImeiWriter.brief("+EGMR: \"\"\nOK\n"))
    }

    @Test fun writeVerifiedWhenReadBackMatches() {
        val sec = mapOf(
            "USE" to "/dev/radio/pttycmd1 rn\n",
            "B1" to "+EGMR: \"\"\nOK\n",
            "W1" to "OK\n",
            "A1" to "+EGMR: \"$good\"\nOK\n"
        )
        val r = ImeiWriter.analyzeWrite(sec, good, null)
        assertTrue(r.ok)
        assertFalse(r.uncertain)
    }

    @Test fun writeUncertainWhenModemSilent() {
        val sec = mapOf(
            "USE" to "/dev/radio/pttycmd1 rn\n"
        )
        val r = ImeiWriter.analyzeWrite(sec, good, null)
        assertFalse(r.ok)
        assertTrue(r.uncertain)
    }

    @Test fun writeFailsWhenModemSaysError() {
        val sec = mapOf(
            "USE" to "/dev/radio/atci1 r\n",
            "W1" to "ERROR\n",
            "A1" to "+EGMR: \"\"\nOK\n"
        )
        val r = ImeiWriter.analyzeWrite(sec, good, null)
        assertFalse(r.ok)
        assertFalse(r.uncertain)
    }

    @Test fun writeReportsMissingPort() {
        val r = ImeiWriter.analyzeWrite(mapOf("NODEV" to "\n"), good, null)
        assertFalse(r.ok)
        assertFalse(r.uncertain)
    }

    @Test fun briefIgnoresRadioDaemonChatter() {
        // +ECELL adalah balasan milik layanan radio (rild), bukan jawaban atas perintah kita.
        assertEquals(
            "tidak ada balasan (+1 baris lain, bukan jawaban)",
            ImeiWriter.brief("+ECELL: 2,7,\"0028A220\",\"3BC4\"\n")
        )
        assertEquals("OK (+1 baris lain, bukan jawaban)", ImeiWriter.brief("+ECELL: 2,7\nOK\n"))
    }

    @Test fun chatterAloneIsNotAnAnswer() {
        // Dulu baris +ECELL dianggap "jawaban" sehingga laporan salah menyebut modem menolak.
        val sec = mapOf(
            "USE" to "/dev/radio/pttycmd1 r\n",
            "W1" to "+ECELL: 2,7,\"0028A220\"\n",
            "A1" to "+ECELL: 2,7,\"0028A220\"\n"
        )
        val r = ImeiWriter.analyzeWrite(sec, good, null)
        assertFalse(r.ok)
        assertTrue(r.uncertain)
    }

    @Test fun writeFailsWhenNoPortAnswers() {
        val r = ImeiWriter.analyzeWrite(mapOf("NOPORT" to "\n", "H1" to "/dev/radio/pttycmd1\nmtkfusionrild(17425)\n"), good, null)
        assertFalse(r.ok)
        assertFalse(r.uncertain)
        assertTrue(r.lines.any { "mtkfusionrild(17425)" in it })
    }

    @Test fun readParsesImeiAndReportsSilentPort() {
        val okSec = mapOf("USE" to "/dev/radio/pttycmd1 rn\n", "A1" to "+EGMR: \"$good\"\nOK\n", "A2" to "+EGMR: \"\"\nOK\n")
        val ok = ImeiWriter.analyzeRead(okSec)
        assertTrue(ok.ok)
        assertTrue(ok.lines.any { good in it })
        assertFalse(ImeiWriter.analyzeRead(mapOf("NOPORT" to "\n")).ok)
    }

    @Test fun noPortReportShowsReadProbeAndHolder() {
        val sec = mapOf(
            "H1" to "/dev/radio/pttycmd1\nmtkfusionrild(25293)\n",
            "P1" to "/dev/radio/pttycmd1 rn frozen\n+ECELL: 1,7,\"0028A248\"\nOK\n",
            "NOPORT" to "\n"
        )
        val r = ImeiWriter.analyzeRead(sec)
        assertFalse(r.ok)
        assertTrue(r.lines.any { it.contains("Pemegang /dev/radio/pttycmd1: mtkfusionrild(25293)") })
        assertTrue(r.lines.any { it.startsWith("Tes baca /dev/radio/pttycmd1 (CR+LF, radio dijeda):") && it.contains("bukan jawaban") })
    }

    @Test fun unsolicitedOkNeverCountsAsVerified() {
        val sec = mapOf(
            "USE" to "/dev/radio/pttycmd1 rn\n",
            "W1" to "+ECELL: 1,7,\"0028A248\"\nOK\n",
            "A1" to "+ECELL: 1,7,\"0028A248\"\nOK\n"
        )
        val r = ImeiWriter.analyzeWrite(sec, good, null)
        assertFalse(r.ok)
    }
}
