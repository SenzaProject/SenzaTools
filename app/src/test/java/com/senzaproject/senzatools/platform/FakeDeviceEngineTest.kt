package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.FakeDeviceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FakeDeviceEngineTest {
    private val re = Regex("^[A-Za-z0-9 ._+-]{1,48}$")

    @Test fun hasTenDevicesWithUniqueIdsAndSafeValues() {
        val d = FakeDeviceEngine.DEVICES
        assertEquals(10, d.size)
        assertEquals(10, d.map { it.id }.toSet().size)
        assertTrue("minimal 8 merek berbeda", d.map { it.brand.lowercase() }.toSet().size >= 8)
        d.forEach {
            listOf(it.id, it.marketName, it.brand, it.manufacturer, it.model).forEach { v -> assertTrue("nilai tidak aman: $v", v.isNotBlank()) }
            listOf(it.marketName, it.brand, it.manufacturer, it.model).forEach { v -> assertTrue("nilai tidak aman: $v", re.matches(v)) }
        }
    }

    @Test fun socsCoverQualcommAndMediatekWithUniqueIds() {
        val s = FakeDeviceEngine.SOCS
        assertEquals(s.size, s.map { it.id }.toSet().size)
        assertTrue(s.any { it.vendor == FakeDeviceEngine.Vendor.QUALCOMM && it.code == "SM8975" })
        assertTrue(s.any { it.vendor == FakeDeviceEngine.Vendor.MEDIATEK && it.code == "MT6995" })
        s.forEach { assertTrue(re.matches(it.code)) }
    }

    @Test fun devicePropsCoverAllPartitionsAndNeverTouchDriverProps() {
        for (dev in FakeDeviceEngine.DEVICES) {
            val m = FakeDeviceEngine.propsFor(dev, null)
            assertEquals(28, m.size)
            assertEquals(dev.model, m["ro.product.model"])
            assertEquals(dev.model, m["ro.product.vendor.model"])
            assertEquals(dev.brand, m["ro.product.system.brand"])
            assertTrue(m.keys.all { it in FakeDeviceEngine.ALL_KEYS })
            assertTrue(m.keys.none { it.startsWith("ro.board") || it.startsWith("ro.hardware") || it.endsWith(".device") || it.endsWith(".name") })
        }
    }

    @Test fun socPropsUseVendorManufacturer() {
        val q = FakeDeviceEngine.propsFor(null, FakeDeviceEngine.SOCS.first { it.vendor == FakeDeviceEngine.Vendor.QUALCOMM })
        assertEquals("QTI", q["ro.soc.manufacturer"])
        val mt = FakeDeviceEngine.propsFor(null, FakeDeviceEngine.SOCS.first { it.vendor == FakeDeviceEngine.Vendor.MEDIATEK })
        assertEquals("Mediatek", mt["ro.soc.manufacturer"])
        assertEquals(2, mt.size)
        assertTrue(FakeDeviceEngine.propsFor(null, null).isEmpty())
    }

    @Test fun scriptTemplateKeepsAllPlaceholders() {
        val f = File("src/main/assets/senza_fakedevice.sh")
        assumeTrue(f.exists())
        val t = f.readText()
        listOf("#MODE#", "#PERSIST#", "#DEVICE_ID#", "#SOC_ID#", "#PROPS#", "#VALUES#").forEach {
            assertEquals("placeholder $it harus tepat satu", 1, t.split(it).size - 1)
        }
    }
}
