package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.DnsTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTunerTest {
    @Test fun hasFiveProvidersStartingWithAdGuard() {
        val p = DnsTuner.PROVIDERS
        assertEquals(5, p.size)
        assertEquals("dns.adguard.com", p.first().host)
        assertEquals(5, p.map { it.id }.toSet().size)
        assertEquals(5, p.map { it.host }.toSet().size)
    }

    @Test fun allProviderHostsAreValid() {
        DnsTuner.PROVIDERS.forEach { assertTrue("host tidak valid: ${it.host}", DnsTuner.isValidHost(it.host)) }
    }

    @Test fun rejectsUnsafeHosts() {
        listOf("", "dns", "a b.com", "dns.google; reboot", "dns.google\nid", "-bad.example.com", "UPPER.example.com", "dns.google'", "dns.google/../x").forEach {
            assertFalse("harus ditolak: $it", DnsTuner.isValidHost(it))
        }
    }

    @Test fun modeLabels() {
        assertEquals("Otomatis", DnsTuner.modeLabel("opportunistic"))
        assertEquals("Nonaktif", DnsTuner.modeLabel("off"))
        assertEquals("Hostname khusus", DnsTuner.modeLabel("hostname"))
    }
}
