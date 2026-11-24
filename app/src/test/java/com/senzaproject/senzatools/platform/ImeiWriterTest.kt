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
            "BLIND" to "/dev/radio/pttycmd1 rn\n",
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
}
