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
        assertEquals(3, mt.size)
        assertEquals(mt["ro.soc.model"], mt["ro.hardware.chipname"])
        assertTrue(FakeDeviceEngine.propsFor(null, null).isEmpty())
    }

    @Test fun scriptTemplateKeepsAllPlaceholders() {
        val f = File("src/main/assets/senza_fakedevice.sh")
        assumeTrue(f.exists())
        val t = f.readText()
        listOf("#MODE#", "#PERSIST#", "#DEVICE_ID#", "#SOC_ID#", "#DEEP#", "#PROPS#", "#VALUES#").forEach {
            assertEquals("placeholder $it harus tepat satu", 1, t.split(it).size - 1)
        }
    }

    @Test fun deepModeAddsPlatformPropsButNeverPersistsThem() {
        val sd = FakeDeviceEngine.SOCS.first { it.id == "sd8e" }
        val deep = FakeDeviceEngine.propsFor(null, sd, true)
        assertEquals("sun", deep["ro.board.platform"])
        assertEquals("qcom", deep["ro.hardware"])
        val safe = FakeDeviceEngine.persistable(deep)
        assertTrue(safe.keys.none { it in FakeDeviceEngine.DEEP_KEYS })
        assertEquals("SM8750", safe["ro.soc.model"])
        val mt = FakeDeviceEngine.propsFor(null, FakeDeviceEngine.SOCS.first { it.id == "d9500" }, true)
        assertEquals("mt6993", mt["ro.board.platform"])
        assertEquals("MT6993", mt["ro.mediatek.platform"])
        // SoC tanpa kode platform tidak pernah menyentuh properti platform
        val unknown = FakeDeviceEngine.propsFor(null, FakeDeviceEngine.SOCS.first { it.id == "sd8e6" }, true)
        assertTrue(unknown.keys.none { it in FakeDeviceEngine.DEEP_KEYS })
        // tanpa Mode dalam, properti platform tidak disentuh
        assertTrue(FakeDeviceEngine.propsFor(null, sd, false).keys.none { it in FakeDeviceEngine.DEEP_KEYS })
    }

    @Test fun mergePropsReplacesAllOccurrencesAndAppendsMissing() {
        val src = "# komentar\nro.a=1\nro.soc.model=MT6789\nro.soc.model=MT6789\nro.z=9"
        val out = FakeDeviceEngine.mergeProps(src, linkedMapOf("ro.soc.model" to "SM8750", "ro.chipname" to "SM8750"))
        assertEquals("# komentar\nro.a=1\nro.soc.model=SM8750\nro.soc.model=SM8750\nro.z=9\nro.chipname=SM8750\n", out)
        assertEquals("ro.x=1\n", FakeDeviceEngine.mergeProps("", mapOf("ro.x" to "1")))
        assertEquals("a=1\r\nro.x=2\r\n", FakeDeviceEngine.mergeProps("a=1\r\n", mapOf("ro.x" to "2")))
    }

    @Test fun transsionPropsFollowDocumentedKeys() {
        val sd = FakeDeviceEngine.SOCS.first { it.id == "d9500" }
        val m = FakeDeviceEngine.transsionProps(sd)
        assertEquals(
            listOf("ro.hardware.chipname", "ro.chipname", "ro.soc.manufacturer", "ro.soc.model", "ro.vendor.soc.model.external_name", "ro.vendor.soc.model.part_name"),
            m.keys.toList()
        )
        assertEquals("Mediatek", m["ro.soc.manufacturer"])
        assertEquals("MT6993", m["ro.vendor.soc.model.part_name"])
    }

    @Test fun cpuInfoWritesChipNameKeepingOriginalStyle() {
        val mt = FakeDeviceEngine.SOCS.first { it.id == "d9600p" }
        val qc = FakeDeviceEngine.SOCS.first { it.id == "sd8e" }
        assertEquals("Dimensity 9600 Pro", FakeDeviceEngine.formatCpuInfo("Helio G100", mt))
        assertEquals("Dimensity 9600 Pro\n", FakeDeviceEngine.formatCpuInfo("Helio G100\n", mt))
        assertEquals("MediaTek Dimensity 9600 Pro", FakeDeviceEngine.formatCpuInfo("MediaTek Helio G100", mt))
        assertEquals("Qualcomm Snapdragon 8 Elite", FakeDeviceEngine.formatCpuInfo("MediaTek Helio G100", qc))
    }

    @Test fun packageValidationAcceptsRealPackagesAndRejectsInjection() {
        assertTrue(FakeDeviceEngine.isValidPackage("com.mobile.legends"))
        assertTrue(FakeDeviceEngine.isValidPackage("com.tencent.ig"))
        listOf("", "legends", "com.x; rm -rf /", "com.x'y", "com.x y", "com..x", "1com.x", "com.x\nid").forEach {
            assertTrue("harus ditolak: $it", !FakeDeviceEngine.isValidPackage(it))
        }
    }

    @Test fun protectedPackagesCannotBeReset() {
        assertTrue(FakeDeviceEngine.isProtectedPackage("com.android.systemui", "com.senzaproject.senzatools"))
        assertTrue(FakeDeviceEngine.isProtectedPackage("com.topjohnwu.magisk", "com.senzaproject.senzatools"))
        assertTrue(FakeDeviceEngine.isProtectedPackage("com.senzaproject.senzatools", "com.senzaproject.senzatools"))
        assertTrue(!FakeDeviceEngine.isProtectedPackage("com.mobile.legends", "com.senzaproject.senzatools"))
    }

    @Test fun resetScriptNeverTouchesDownloadedResources() {
        for (lvl in FakeDeviceEngine.ResetLevel.values()) {
            val s = FakeDeviceEngine.resetScript("com.mobile.legends", lvl)
            assertTrue(s.contains("am force-stop"))
            assertTrue(s.contains("wipe \"\$D/cache\""))
            // OBB dan Android/data/<paket>/files hanya boleh dihitung ukurannya, tidak pernah dihapus.
            assertTrue(!s.contains("wipe \"\$O"))
            assertTrue(!s.contains("wipe \"\$E/files"))
            assertTrue(!s.contains("rm -rf \"\$D\"") && !s.contains("rm -rf \"\$E\""))
        }
        assertTrue(!FakeDeviceEngine.resetScript("a.b", FakeDeviceEngine.ResetLevel.CACHE).contains("shared_prefs"))
        assertTrue(FakeDeviceEngine.resetScript("a.b", FakeDeviceEngine.ResetLevel.CONFIG).contains("shared_prefs"))
    }

    @Test fun gameModeOnlyForDevicesWithKnownCodename() {
        val op = FakeDeviceEngine.device("op13")!!
        val m = FakeDeviceEngine.propsFor(op, null, game = true)
        assertEquals("OP5D55L1", m["ro.product.device"])
        assertEquals("OP5D55L1", m["ro.product.vendor.device"])
        assertEquals("CPH2655", m["ro.product.name"])
        assertEquals(28 + 14, m.size)
        assertTrue(m.keys.all { it in FakeDeviceEngine.ALL_KEYS })
        // Tanpa codename: tidak ada yang dikarang.
        val noCode = FakeDeviceEngine.DEVICES.first { it.codename == null }
        assertEquals(28, FakeDeviceEngine.propsFor(noCode, null, game = true).size)
        // Bawaan (game=false) tidak pernah menyentuh device/name.
        assertTrue(FakeDeviceEngine.propsFor(op, null).keys.none { it.endsWith(".device") || it.endsWith(".name") })
        // Mode game tidak pernah menyentuh platform/hardware.
        assertTrue(m.keys.none { it in FakeDeviceEngine.DEEP_KEYS })
        FakeDeviceEngine.DEVICES.mapNotNull { it.codename }.forEach { assertTrue(re.matches(it)) }
    }

    @Test fun fingerprintIsNeverPartOfStaticProps() {
        assertTrue(FakeDeviceEngine.FP_KEYS.all { it in FakeDeviceEngine.ALL_KEYS })
        val op = FakeDeviceEngine.device("op13")!!
        assertTrue(FakeDeviceEngine.propsFor(op, null, game = true).keys.none { it in FakeDeviceEngine.FP_KEYS })
    }
}
