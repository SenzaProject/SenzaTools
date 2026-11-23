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

    @Test fun maskHidesMiddleDigits() {
        assertEquals("4901•••••••••18", ImeiWriter.mask(good))
        assertEquals("OK", ImeiWriter.mask("OK"))
    }

    @Test fun snippetEmptyAndFilled() {
        assertTrue(ImeiWriter.snippet("  \n").contains("kosong"))
        assertFalse(ImeiWriter.snippet("+EGMR: \"$good\"\nOK").contains(good))
    }

    @Test fun parseEgmrAcceptsSlotPrefix() {
        assertEquals(good, ImeiWriter.parseEgmr("+EGMR: 7,\"$good\"\nOK"))
    }

    @Test fun probeSectionsKeepSeparateNames() {
        val sec = ImeiWriter.sections(listOf("@@ENF Enforcing", "@@PROBE1 /dev/radio/pttycmd1", "", "@@PROBE2 /dev/radio/atci1", "OK", "@@NORESP"))
        assertEquals("Enforcing", sec["ENF"]!!.trim())
        assertTrue("OK" in sec["PROBE2"]!!)
        assertTrue("NORESP" in sec)
    }
}
