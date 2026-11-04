package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.analyzer.system.SelinuxAnalyzer
import com.senzaproject.senzatools.platform.root.SelinuxEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SelinuxTest {

    private fun avc(id: Int, perms: String = "read", s: String = "untrusted_app", t: String = "proc_net", c: String = "file",
                    comm: String = "app$id", perm: Int = 0, withId: Boolean = true, pfx: String = "[ 12.3] type=1400 ") =
        pfx + (if (withId) "audit(1700000000.%03d:$id): ".format(id % 1000) else "") +
            "avc:  denied  { $perms } for pid=${id + 100} comm=\"$comm\" name=\"x\" dev=\"proc\" ino=1 " +
            "scontext=u:r:$s:s0:c1,c2 tcontext=u:object_r:$t:s0 tclass=$c permissive=$perm"

    // ------------------------------------------------------------------ analyzer

    @Test fun permsOrderIsNormalizedAndDuplicatesById() {
        val log = listOf(avc(1, "write read"), avc(2, "read write"), avc(2, "read write")).joinToString("\n")
        val r = SelinuxAnalyzer.parse(log)
        assertEquals(1, r.size)
        assertEquals("read write", r[0].perms)
        assertEquals(2, r[0].count)           // id 2 muncul dua kali tapi satu kejadian
    }

    @Test fun resultDoesNotDependOnLineOrderOrWindow() {
        val rnd = Random(42)
        val events = (1..300).map { i ->
            avc(i, perms = listOf("read", "write", "open", "getattr")[i % 4], s = listOf("untrusted_app", "system_server", "mediaserver")[i % 3],
                t = listOf("proc_net", "sysfs", "vold", "kernel")[i % 4], comm = listOf("a", "b", "c")[i % 3], perm = i % 2)
        }
        val ref = SelinuxAnalyzer.parse(events.joinToString("\n"))
        repeat(1000) {
            val shuffled = events.shuffled(rnd)
            assertEquals(ref, SelinuxAnalyzer.parse(shuffled.joinToString("\n")))
        }
    }

    @Test fun equalCountsAreOrderedByKeyNotByFirstSeen() {
        val a = avc(1, t = "aaa")
        val b = avc(2, t = "bbb")
        val r1 = SelinuxAnalyzer.parse("$a\n$b").map { it.tType }
        val r2 = SelinuxAnalyzer.parse("$b\n$a").map { it.tType }
        assertEquals(r1, r2)
        assertEquals(listOf("aaa", "bbb"), r1)
    }

    @Test fun duplicatedLogConcatenationDoesNotDoubleCount() {
        val log = (1..50).joinToString("\n") { avc(it, t = "t${it % 5}") }
        assertEquals(SelinuxAnalyzer.parse(log), SelinuxAnalyzer.parse(log + "\n" + log))
    }

    @Test fun sourcesAreMergedWithoutDoubleCounting() {
        val ev = (1..40).map { avc(it, t = "t${it % 4}") }
        val dmesg = ev.joinToString("\n")
        val logcat = ev.joinToString("\n") { it.replace("[ 12.3] type=1400 ", "11-04 10:00:00.000  500  500 W auditd  : type=1400 ") }
        val merged = SelinuxAnalyzer.parseSources(listOf(dmesg, logcat))
        assertEquals(SelinuxAnalyzer.parse(dmesg), merged)
        assertEquals(merged, SelinuxAnalyzer.parseSources(listOf(logcat, dmesg)))
        assertEquals(40, merged.sumOf { it.count })
    }

    @Test fun eventsWithoutIdUseTheLargestSourceNotTheSum() {
        val ev = (1..10).map { avc(it, withId = false, comm = "x") }
        val full = ev.joinToString("\n")
        val partial = ev.take(6).joinToString("\n")
        assertEquals(10, SelinuxAnalyzer.parseSources(listOf(full, partial)).sumOf { it.count })
        assertEquals(10, SelinuxAnalyzer.parseSources(listOf(partial, full)).sumOf { it.count })
    }

    @Test fun commIsMostFrequentThenAlphabetical() {
        val log = listOf(avc(1, comm = "zeta"), avc(2, comm = "alpha"), avc(3, comm = "zeta"), avc(4, comm = "alpha")).joinToString("\n")
        assertEquals("alpha", SelinuxAnalyzer.parse(log)[0].comm)
        val log2 = listOf(avc(1, comm = "zeta"), avc(2, comm = "alpha"), avc(3, comm = "zeta")).joinToString("\n")
        assertEquals("zeta", SelinuxAnalyzer.parse(log2)[0].comm)
    }

    @Test fun permissiveOnlyWhenEveryKnownEventIsPermissive() {
        assertEquals(true, SelinuxAnalyzer.parse(listOf(avc(1, perm = 1), avc(2, perm = 1)).joinToString("\n"))[0].permissive)
        assertEquals(false, SelinuxAnalyzer.parse(listOf(avc(1, perm = 1), avc(2, perm = 0)).joinToString("\n"))[0].permissive)
        assertNull(SelinuxAnalyzer.parse(avc(1).replace(" permissive=0", ""))[0].permissive)
    }

    @Test fun ignoresGrantedAndIncompleteLines() {
        val log = "avc: granted { read } for scontext=u:r:a:s0 tcontext=u:object_r:b:s0 tclass=file\n" +
            "avc: denied { read } for comm=\"x\" scontext=u:r:a:s0\n" + avc(1)
        assertEquals(1, SelinuxAnalyzer.parse(log).size)
    }

    // ------------------------------------------------------------------ mode

    private fun out(vararg l: String) = FakeShell.Out(l.toList())

    @Test fun modeIsTheSameWhetherOrNotRootIsVerifiedYet() {
        val sh = FakeShell(rootVerified = false)
        sh.files["/sys/fs/selinux/enforce"] = "1"
        sh.appCmds["getenforce"] = { out("Enforcing") }
        sh.rootCmds["getenforce"] = { out("Enforcing") }
        val e = SelinuxEngine(sh)
        val before = e.status()
        sh.rootVerified = true
        val after = e.status()
        assertEquals("Enforcing", before.mode)
        assertEquals(before.mode, after.mode)
        assertFalse(after.conflict)
    }

    @Test fun rootReadingWinsAndConflictIsReported() {
        val sh = FakeShell()
        sh.files["/sys/fs/selinux/enforce"] = "1"
        sh.appCmds["getenforce"] = { out("Enforcing") }
        sh.rootCmds["getenforce"] = { out("Permissive") }
        val st = SelinuxEngine(sh).status()
        assertEquals("Permissive", st.mode)
        assertEquals("root", st.source)
        assertTrue(st.conflict)
    }

    @Test fun errorTextIsNeverParsedAsAMode() {
        val sh = FakeShell(rootVerified = false)
        sh.appCmds["getenforce"] = { FakeShell.Out(listOf("Enforcing"), exit = 1, stderr = "boom") }   // gagal -> diabaikan
        assertNull(SelinuxEngine(sh).status().mode)
        sh.appCmds["getenforce"] = { FakeShell.Out(listOf("sh: getenforce: Permission denied")) }
        assertNull(SelinuxEngine(sh).status().mode)
    }

    @Test fun transientFailureIsRetriedAndGivesSameAnswer() {
        val sh = FakeShell(rootVerified = false)
        var n = 0
        sh.appCmds["getenforce"] = { if (n++ == 0) FakeShell.Out(emptyList(), 1, "timeout", complete = false) else out("Permissive") }
        assertEquals("Permissive", SelinuxEngine(sh).status().mode)
    }

    @Test fun statusIsStableThousandTimes() {
        val sh = FakeShell()
        sh.files["/sys/fs/selinux/enforce"] = "0"
        sh.appCmds["getenforce"] = { out("Permissive") }
        sh.rootCmds["getenforce"] = { out("Permissive") }
        val e = SelinuxEngine(sh)
        repeat(1000) { assertEquals("Permissive", e.status().mode) }
    }

    @Test fun setPermissiveVerifiesAndReportsForcedEnforcing() {
        var mode = "Enforcing"
        val sh = FakeShell()
        sh.files["/sys/fs/selinux/enforce"] = "1"
        sh.rootCmds["getenforce"] = { out(mode) }
        sh.appCmds["getenforce"] = { out(mode) }
        sh.rootCmds["setenforce 0"] = { mode = "Permissive"; FakeShell.Out(emptyList()) }
        sh.rootCmds["setenforce 1"] = { mode = "Enforcing"; FakeShell.Out(emptyList()) }
        val e = SelinuxEngine(sh, sleep = {})
        assertTrue(e.setPermissive(true).ok)
        assertTrue(e.setPermissive(false).ok)
        sh.rootCmds["setenforce 0"] = { FakeShell.Out(emptyList(), 1, "forced") }   // kernel menolak
        val r = e.setPermissive(true)
        assertFalse(r.ok)
        assertTrue(r.message.contains("Enforcing"))
    }

    // ------------------------------------------------------------------ pemindaian denial

    private fun shellForScan(dmesg: FakeShell.Out, logcat: FakeShell.Out): FakeShell {
        val sh = FakeShell()
        sh.rootCmds["dmesg"] = { dmesg }
        sh.rootCmds["logcat -b all -d -v threadtime"] = { logcat }
        return sh
    }

    @Test fun scanIsIdenticalThousandTimesAndMergesSources() {
        val ev = (1..120).map { avc(it, t = "t${it % 7}", s = "s${it % 3}") }
        val noise = (1..500).map { "11-04 10:00:00.000  1  1 I Noise: line $it" }
        val dm = FakeShell.Out(noise.map { "[ $it.0] noise" } + ev)
        val lc = FakeShell.Out(noise + ev.map { it.replace("[ 12.3] type=1400 ", "11-04 10:00:00.000  500  500 W auditd  : type=1400 ") })
        val e = SelinuxEngine(shellForScan(dm, lc))
        val first = e.scanDenials()
        assertTrue(first.complete)
        assertEquals(120, first.totalEvents)
        repeat(1000) {
            val s = e.scanDenials()
            assertEquals(first.denials, s.denials)
            assertEquals(first.fingerprint, s.fingerprint)
        }
    }

    @Test fun oneSourceFailingIsReportedNotHidden() {
        val ev = (1..5).map { avc(it) }
        val sh = shellForScan(FakeShell.Out(emptyList(), 1, "klogctl: Operation not permitted"), FakeShell.Out(ev))
        val s = SelinuxEngine(sh).scanDenials()
        assertFalse(s.complete)                      // jangan menyimpulkan "tidak ada denial"
        assertEquals(5, s.totalEvents)
        assertTrue(s.sources.first { it.name == "dmesg" }.note.startsWith("gagal"))
    }

    @Test fun logcatFallsBackToDefaultBuffersWhenAllIsUnsupported() {
        val ev = (1..3).map { avc(it) }
        val sh = FakeShell()
        sh.rootCmds["dmesg"] = { FakeShell.Out(emptyList()) }
        sh.rootCmds["logcat -b all -d -v threadtime"] = { FakeShell.Out(emptyList(), 1, "Unable to open log device") }
        sh.rootCmds["logcat -d -v threadtime"] = { FakeShell.Out(ev) }
        val s = SelinuxEngine(sh).scanDenials()
        assertTrue(s.complete)
        assertEquals(3, s.totalEvents)
    }

    @Test fun needsRoot() {
        assertEquals(0, SelinuxEngine(FakeShell(rootVerified = false)).scanDenials().denials.size)
        assertFalse(SelinuxEngine(FakeShell(rootVerified = false)).scanDenials().complete)
    }
}
