package com.senzaproject.senzatools.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class KernelRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(id: String) = KernelCatalog.byId.getValue(id)
    private val sh = listOf("/bin/sh", "/system/bin/sh").map(::File).firstOrNull { it.exists() }

    private fun runSh(script: String, timeoutSec: Long = 20): Pair<Int, String> {
        val f = tmp.newFile()
        val p = ProcessBuilder(sh!!.path, "-c", script).redirectErrorStream(true).redirectOutput(f).start()
        assertTrue("shell timeout", p.waitFor(timeoutSec, TimeUnit.SECONDS))
        return p.exitValue() to f.readText()
    }

    // ------------------------------------------------------------------ Katalog

    @Test fun catalogIdsUniqueAndPathsSafe() {
        assertEquals(KernelCatalog.all.size, KernelCatalog.byId.size)
        val ok = Regex("^/(sys|proc/sys)/[A-Za-z0-9_./@:+,=-]+$")
        KernelCatalog.all.forEach { n ->
            assertTrue("${n.id} tanpa path", n.paths.isNotEmpty())
            n.paths.forEach { p ->
                assertTrue("path tidak aman: $p", ok.matches(p))
                assertFalse("path mengandung ..: $p", p.split('/').contains(".."))
                assertFalse("node berbahaya: $p", listOf("/dev/", "fsync_enabled", "selinux", "/proc/kcore").any { p.contains(it) })
            }
        }
    }

    @Test fun everyNodeAcceptsItsOwnBoundsAndRejectsOutside() {
        KernelCatalog.all.forEach { n ->
            when (val t = n.type) {
                is KType.Toggle -> { assertNull(KernelRules.validateValue(n, t.on)); assertNull(KernelRules.validateValue(n, t.off)); assertNotNull(KernelRules.validateValue(n, "2")) }
                is KType.Num -> {
                    assertNull("${n.id} min", KernelRules.validateValue(n, t.min.toString()))
                    assertNull("${n.id} max", KernelRules.validateValue(n, t.max.toString()))
                    assertNotNull("${n.id} min-1", KernelRules.validateValue(n, (t.min - 1).toString()))
                    assertNotNull("${n.id} max+1", KernelRules.validateValue(n, (t.max + 1).toString()))
                }
                is KType.Choice, is KType.Csv -> Unit
            }
        }
    }

    @Test fun thermalAndLmkAreHighRiskAndNeverBootApplied() {
        KernelCatalog.forGroup(KGroup.THERMAL).filter { it.id in setOf("th_msm_enabled", "th_msm_core_control", "th_msm_limit_temp") }
            .forEach { assertEquals(Risk.HIGH, it.risk); assertFalse(it.bootSafe) }
        assertEquals(Risk.HIGH, node("lmk_minfree").risk)
        assertTrue(node("th_msm_limit_temp").type.let { it is KType.Num && it.max <= 85 })
    }

    // ------------------------------------------------------------------ Validasi & injeksi

    @Test fun rejectsShellInjectionInValues() {
        val n = node("vm_swappiness")
        listOf("60; rm -rf /", "\$(id)", "`id`", "60 && reboot", "60'", "60\"", "6 0", "", "60\n1", "../x", "60|1").forEach {
            assertNotNull("harus ditolak: [$it]", KernelRules.validateValue(n, it))
        }
        assertNull(KernelRules.validateValue(n, "60"))
    }

    @Test fun pathMustBelongToNode() {
        val n = node("vm_swappiness")
        assertEquals("/proc/sys/vm/swappiness", KernelRules.checkPath(n, "/proc/sys/vm/swappiness"))
        listOf("/proc/sys/vm/min_free_kbytes", "/dev/block/sda", "/sys/../etc/passwd", "/proc/sys/vm/swappiness/../x").forEach {
            val r = runCatching { KernelRules.checkPath(n, it) }
            assertTrue("harus ditolak: $it", r.isFailure)
        }
        assertTrue(runCatching { KernelRules.writeScript(n, "/proc/sys/vm/other", "1") }.isFailure)
    }

    @Test fun choiceAndCsvValidation() {
        val sched = node("io_scheduler")
        assertNull(KernelRules.validateValue(sched, "kyber", listOf("mq-deadline", "kyber", "none")))
        assertNotNull(KernelRules.validateValue(sched, "bfq", listOf("mq-deadline", "kyber", "none")))
        assertNotNull(KernelRules.validateValue(sched, "kyber", emptyList()))
        val lmk = node("lmk_minfree")
        assertNull(KernelRules.validateValue(lmk, "18432,23040,27648,32256,55296,80640"))
        assertNotNull(KernelRules.validateValue(lmk, "1,2,3"))
        assertNotNull(KernelRules.validateValue(lmk, "80640,55296,32256,27648,23040,18432"))
        assertNotNull(KernelRules.validateValue(lmk, "1,2,3,4,5,999999999"))
    }

    @Test fun crossCheckRules() {
        assertNotNull(KernelRules.crossCheck(mapOf("vm_dirty_ratio" to "10", "vm_dirty_background_ratio" to "20")))
        assertNull(KernelRules.crossCheck(mapOf("vm_dirty_ratio" to "20", "vm_dirty_background_ratio" to "10")))
        assertNotNull(KernelRules.crossCheck(mapOf("th_msm_limit_temp" to "80", "th_msm_core_limit_temp" to "70")))
        assertNull(KernelRules.crossCheck(emptyMap()))
    }

    @Test fun orderChangesWritesBackgroundRatioFirst() {
        val o = KernelRules.orderChanges(listOf(Change("vm_dirty_ratio", "30"), Change("vm_swappiness", "10"), Change("vm_dirty_background_ratio", "5")))
        assertEquals("vm_dirty_background_ratio", o.first().nodeId)
    }

    // ------------------------------------------------------------------ Normalisasi & parsing

    @Test fun choiceParsing() {
        val (opts, act) = KernelRules.parseChoice("mq-deadline [kyber] none ")
        assertEquals(listOf("mq-deadline", "kyber", "none"), opts); assertEquals("kyber", act)
        assertEquals("cubic", KernelRules.normalize(node("net_tcp_congestion"), "cubic\n"))
    }

    @Test fun toggleNormalizationAcrossBoolStyles() {
        val ya = node("cpu_wq_power_efficient") // Y/N
        assertTrue(KernelRules.sameValue(ya, "Y", "Y")); assertTrue(KernelRules.sameValue(ya, "Y", "1"))
        assertTrue(KernelRules.sameValue(ya, "N", "0")); assertFalse(KernelRules.sameValue(ya, "Y", "N"))
        val one = node("ksm_run")
        assertTrue(KernelRules.sameValue(one, "1", "1")); assertFalse(KernelRules.sameValue(one, "1", "0"))
    }

    @Test fun parseReadClassifiesNodes() {
        val dev = DeviceProfile(35, KernelVersion.parse("5.10.168-android12"), SocVendor.MEDIATEK, "mt6893", "Enforcing", 8000)
        val out = """
            N|/proc/sys/vm/swappiness|1|1|60
            N|/proc/sys/vm/vfs_cache_pressure|1|0|100
            N|/proc/sys/vm/min_free_kbytes|0|0|
            N|/sys/block/sda/queue/scheduler|1|1|mq-deadline [kyber] none 
            N|/proc/sys/net/ipv4/tcp_congestion_control|1|1|cubic
            A|/proc/sys/net/ipv4/tcp_available_congestion_control|reno cubic bbr
            N|/sys/module/workqueue/parameters/power_efficient|1|1|N
        """.trimIndent()
        val st = KernelRules.parseRead(KernelCatalog.all, out, dev).associateBy { it.node.id }
        assertTrue(st.getValue("vm_swappiness").support is Support.Ready); assertEquals("60", st.getValue("vm_swappiness").value)
        assertTrue(st.getValue("vm_vfs_cache_pressure").support is Support.ReadOnly)
        assertTrue(st.getValue("vm_min_free").support is Support.Protected)
        assertEquals(listOf("mq-deadline", "kyber", "none"), st.getValue("io_scheduler").options); assertEquals("kyber", st.getValue("io_scheduler").value)
        assertEquals(listOf("reno", "cubic", "bbr"), st.getValue("net_tcp_congestion").options)
        assertEquals("N", st.getValue("cpu_wq_power_efficient").value)
        val miss = st.getValue("gpu_adreno_idler").support as Support.Missing
        assertTrue(miss.reason, miss.reason.contains("Qualcomm") && miss.reason.contains("MediaTek"))
        assertEquals(KernelCatalog.all.size, st.size)
    }

    // ------------------------------------------------------------------ Skrip shell nyata

    @Test fun scriptsHaveValidShellSyntaxAndReadWorksOnHost() {
        assumeTrue(sh != null)
        val read = KernelRules.readScript(KernelCatalog.all)
        val (code, out) = runSh(read)
        assertEquals(out, 0, code)
        // Di host Linux /proc/sys/vm/swappiness hampir pasti ada dan terbaca.
        if (File("/proc/sys/vm/swappiness").canRead()) {
            val st = KernelRules.parseRead(KernelCatalog.all, out, null).first { it.node.id == "vm_swappiness" }
            assertTrue(st.support !is Support.Missing)
            assertNotNull(st.value?.toLongOrNull())
        }
        val f = tmp.newFile("boot.sh"); f.writeText(KernelRules.bootScript())
        assertEquals(0, runSh("sh -n '${f.path}'").first)
    }

    @Test fun writeScriptWritesVerifiesAndReports() {
        assumeTrue(sh != null)
        val n = node("vm_swappiness")
        val target = tmp.newFile("swappiness"); target.writeText("60\n")
        val script = KernelRules.writeScript(n, "/proc/sys/vm/swappiness", "20").replace("/proc/sys/vm/swappiness", target.path)
        val (code, out) = runSh(script)
        assertEquals(0, code)
        val r = KernelRules.parseWrite(out) as KernelRules.ParsedWrite.Res
        assertEquals("60", r.old); assertEquals("20", r.new)
        assertEquals("20", target.readText().trim())
        assertTrue(KernelRules.sameValue(n, "20", r.new))
        // berkas tidak bisa ditulis → ERR
        val missing = KernelRules.writeScript(n, "/proc/sys/vm/swappiness", "20").replace("/proc/sys/vm/swappiness", tmp.root.path + "/nope")
        assertTrue(KernelRules.parseWrite(runSh(missing).second) is KernelRules.ParsedWrite.Err)
    }

    @Test fun bootConfDropsHighRiskAndInvalid() {
        val entries = listOf(
            node("vm_swappiness") to "30", node("th_msm_enabled") to "N", node("vm_vfs_cache_pressure") to "9999",
            node("io_scheduler") to "kyber", node("lmk_minfree") to "1,2,3,4,5,6"
        )
        val conf = KernelRules.bootConf(entries, { it.paths.first() }, { if (it.id == "io_scheduler") listOf("none", "kyber") else emptyList() })
        assertEquals(listOf("/proc/sys/vm/swappiness|30", "/sys/block/sda/queue/scheduler|kyber"), conf.lines().filter { it.isNotBlank() })
    }

    /** Menjalankan skrip boot sungguhan di direktori sementara: terapkan → boot kedua dilewati (boot guard). */
    @Test fun bootGuardAppliesThenDisablesAfterUnstableBoot() {
        assumeTrue(sh != null)
        val dir = tmp.newFolder("senza_kernel")
        val a = tmp.newFile("sys_a"); a.writeText("1\n")
        val script = File(dir, "boot.sh")
        script.writeText(KernelRules.bootScript().replace(KernelRules.BOOT_DIR, dir.path).replace("sleep 120", "sleep 3"))
        // path sah (/sys/...) tidak bisa diarahkan ke tmp; uji dengan nilai yang tidak dapat ditulis + berkas tmp via /proc/sys jalur palsu dilewati.
        File(dir, "values.conf").writeText("/sys/does/not/exist|1\n/etc/passwd|1\n/sys/../etc/x|1\n/sys/ok;rm|1\n/sys/kernel/x|1;reboot\n")
        File(dir, "enabled").writeText("")
        assertEquals(0, runSh("sh '${script.path}'").first)
        assertTrue("pending dibuat", File(dir, "pending").exists())
        assertTrue(File(dir, "last_status").readText().contains("diterapkan 0"))
        assertTrue("etc/passwd tidak tersentuh", File("/etc/passwd").length() > 0)
        // boot berikutnya: pending masih ada → dilewati dan dinonaktifkan
        assertEquals(0, runSh("sh '${script.path}'").first)
        assertFalse(File(dir, "enabled").exists())
        assertTrue(File(dir, "last_status").readText().contains("dilewati"))
        // stabil: setelah penanda pending hilang, penerapan berjalan lagi bila diaktifkan ulang
        File(dir, "enabled").writeText("")
        Thread.sleep(3500)
        assertFalse("pending dihapus oleh timer stabil", File(dir, "pending").exists())
    }

    // ------------------------------------------------------------------ Deteksi perangkat

    @Test fun socDetection() {
        assertEquals(SocVendor.QUALCOMM, KernelRules.detectSoc("QTI", "kalama", "qcom"))
        assertEquals(SocVendor.QUALCOMM, KernelRules.detectSoc("", "sm8550", ""))
        assertEquals(SocVendor.QUALCOMM, KernelRules.detectSoc("", "msmnile", "qcom"))
        assertEquals(SocVendor.MEDIATEK, KernelRules.detectSoc("Mediatek", "mt6983", "mt6983"))
        assertEquals(SocVendor.MEDIATEK, KernelRules.detectSoc("", "mt6893", ""))
        assertEquals(SocVendor.EXYNOS, KernelRules.detectSoc("", "exynos2100", "exynos2100"))
        assertEquals(SocVendor.EXYNOS, KernelRules.detectSoc("", "universal9810", ""))
        assertEquals(SocVendor.TENSOR, KernelRules.detectSoc("Google", "gs201", "gs201"))
        assertEquals(SocVendor.TENSOR, KernelRules.detectSoc("", "zuma", ""))
        assertEquals(SocVendor.UNISOC, KernelRules.detectSoc("", "ums9230", ""))
        assertEquals(SocVendor.UNKNOWN, KernelRules.detectSoc("", "", ""))
    }

    @Test fun kernelVersionParsing() {
        assertEquals("5.10.168", KernelVersion.parse("5.10.168-android12-9-g1")?.toString())
        assertEquals("4.19.0", KernelVersion.parse("Linux version 4.19 (build@host)")?.toString())
        assertNull(KernelVersion.parse("tidak ada"))
    }

    @Test fun thermalParsing() {
        val z = KernelRules.parseThermal("cpu-0|45000\nbattery|31.5\nbad|abc\nweird|999999\ngpu|52100\n")
        assertEquals(listOf("gpu", "cpu-0", "battery"), z.map { it.first })
        assertEquals(52.1, z.first().second, 0.001)
    }

    @Test fun summarizeAndWarnings() {
        val dev = DeviceProfile(35, KernelVersion.parse("5.15.0"), SocVendor.UNKNOWN, "", "Enforcing", null)
        val w = KernelRules.deviceWarnings(dev, rootOk = false)
        assertEquals(4, w.size)
        assertTrue(KernelRules.deviceWarnings(dev.copy(soc = SocVendor.QUALCOMM, selinux = "Permissive", kernel = KernelVersion.parse("4.19.1")), true).isEmpty())
    }
}
