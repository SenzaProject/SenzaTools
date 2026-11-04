package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.LogEngine
import com.senzaproject.senzatools.platform.root.LogLevel
import com.senzaproject.senzatools.platform.root.LogParse
import com.senzaproject.senzatools.platform.root.LogSource
import com.senzaproject.senzatools.platform.root.SelinuxControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogToolsTest {
    private fun line(i: Int, lvl: Char, msg: String = "pesan $i") =
        "11-04 10:%02d:%02d.%03d  %4d  %4d %c Tag%d: %s".format(i / 3600 % 60, i / 60 % 60, i % 60, 1000 + i % 7, 1000 + i % 11, lvl, i % 5, msg)

    private fun fixture(n: Int): List<String> {
        val lv = charArrayOf('V', 'D', 'I', 'W', 'E', 'I', 'D', 'F')
        return (0 until n).map { line(it, lv[it % lv.size]) }
    }

    private fun shellWith(raw: List<String>): FakeShell = FakeShell().also { it.rootCmds[LogParse.command(LogSource.LOGCAT)] = { FakeShell.Out(raw) } }

    @Test fun buildsFixedCommands() {
        assertEquals("logcat -d -v threadtime", LogParse.command(LogSource.LOGCAT))
        assertEquals("logcat -b crash -d -v threadtime", LogParse.command(LogSource.CRASH))
        assertEquals("dmesg", LogParse.command(LogSource.KERNEL))
    }

    @Test fun keywordFilterIsCaseInsensitiveAndLocal() {
        val l = listOf("E avc: denied foo", "I boot ok", "W FOO bar")
        assertEquals(listOf("E avc: denied foo", "W FOO bar"), LogParse.filter(l, "FoO"))
        assertEquals(l, LogParse.filter(l, "  "))
        assertEquals(emptyList<String>(), LogParse.filter(l, "; reboot"))
    }

    @Test fun parsesSelinuxMode() {
        assertEquals("Enforcing", SelinuxControl.parseMode("1"))
        assertEquals("Enforcing", SelinuxControl.parseMode("Enforcing\n"))
        assertEquals("Permissive", SelinuxControl.parseMode("0"))
        assertEquals("Permissive", SelinuxControl.parseMode(" permissive "))
        assertEquals("Disabled", SelinuxControl.parseMode("Disabled"))
        assertNull(SelinuxControl.parseMode("sh: getenforce: Permission denied"))
        assertNull(SelinuxControl.parseMode(null))
    }

    @Test fun markersAndBlankLinesAreDropped() {
        val raw = listOf("--------- beginning of main", line(1, 'I'), "", "--------- beginning of system", line(2, 'E'), "   ")
        val d = LogEngine(shellWith(raw)).read(LogSource.LOGCAT, LogLevel.ALL, 200)
        assertTrue(d.ok)
        assertEquals(listOf(line(1, 'I'), line(2, 'E')), d.lines)
    }

    @Test fun markerPositionNeverChangesTheResult() {
        val base = fixture(300)
        val a = listOf("--------- beginning of main") + base.take(100) + listOf("--------- beginning of system") + base.drop(100)
        val b = listOf("--------- beginning of system", "--------- beginning of main") + base + listOf("--------- beginning of crash")
        val da = LogEngine(shellWith(a)).read(LogSource.LOGCAT, LogLevel.ALL, 500)
        val db = LogEngine(shellWith(b)).read(LogSource.LOGCAT, LogLevel.ALL, 500)
        assertEquals(da.lines, db.lines)
        assertEquals(da.fingerprint, db.fingerprint)
    }

    @Test fun levelFilterIsExactAndCountIsAppliedAfterFilter() {
        val raw = fixture(1000)
        val w = LogEngine(shellWith(raw)).read(LogSource.LOGCAT, LogLevel.WARN, 200)
        assertTrue(w.ok)
        assertEquals(200, w.lines.size)                                   // tepat 200 baris W+ , bukan "200 baris lalu disaring"
        assertTrue(w.lines.all { (LogParse.levelRank(it) ?: -1) >= 3 })
        val expected = raw.filter { (LogParse.levelRank(it) ?: -1) >= 3 }.takeLast(200)
        assertEquals(expected, w.lines)
        val e = LogEngine(shellWith(raw)).read(LogSource.LOGCAT, LogLevel.ERROR, 200)
        assertTrue(e.lines.all { (LogParse.levelRank(it) ?: -1) >= 4 })
        assertEquals(raw.filter { (LogParse.levelRank(it) ?: -1) >= 4 }.takeLast(200), e.lines)
    }

    @Test fun lastNLinesOfAllLevels() {
        val raw = fixture(900)
        val d = LogEngine(shellWith(raw)).read(LogSource.LOGCAT, LogLevel.ALL, 500)
        assertEquals(raw.takeLast(500), d.lines)
        assertEquals(900L, d.total)
    }

    @Test fun sameBufferGivesIdenticalResultThousandTimes() {
        val sh = shellWith(listOf("--------- beginning of main") + fixture(2500))
        val engine = LogEngine(sh)
        val first = engine.read(LogSource.LOGCAT, LogLevel.WARN, 1000)
        repeat(1000) {
            val d = engine.read(LogSource.LOGCAT, LogLevel.WARN, 1000)
            assertEquals(first.lines, d.lines)
            assertEquals(first.fingerprint, d.fingerprint)
        }
    }

    @Test fun differentContentGivesDifferentFingerprint() {
        val a = LogEngine(shellWith(fixture(100))).read(LogSource.LOGCAT, LogLevel.ALL, 200)
        val b = LogEngine(shellWith(fixture(101))).read(LogSource.LOGCAT, LogLevel.ALL, 200)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun incompleteOrFailedCaptureIsNeverShownAsLog() {
        val cmd = LogParse.command(LogSource.KERNEL)
        val sh = FakeShell()
        sh.rootCmds[cmd] = { FakeShell.Out(emptyList(), exit = 1, stderr = "klogctl: Operation not permitted") }
        val denied = LogEngine(sh).read(LogSource.KERNEL, LogLevel.ALL, 200)
        assertFalse(denied.ok)
        assertTrue(denied.lines.isEmpty())
        assertTrue(denied.message.contains("Operation not permitted"))

        sh.rootCmds[cmd] = { FakeShell.Out(fixture(50), complete = false) }
        val cut = LogEngine(sh).read(LogSource.KERNEL, LogLevel.ALL, 200)
        assertFalse(cut.ok)
        assertTrue(cut.lines.isEmpty())

        sh.rootCmds[cmd] = { FakeShell.Out(fixture(50), complete = false, timedOut = true) }
        assertFalse(LogEngine(sh).read(LogSource.KERNEL, LogLevel.ALL, 200).ok)

        sh.rootCmds[cmd] = { FakeShell.Out(emptyList()) }
        assertFalse(LogEngine(sh).read(LogSource.KERNEL, LogLevel.ALL, 200).ok)
    }

    @Test fun needsVerifiedRootAndValidCount() {
        assertFalse(LogEngine(FakeShell(rootVerified = false)).read(LogSource.LOGCAT, LogLevel.ALL, 200).ok)
        assertFalse(LogEngine(shellWith(fixture(10))).read(LogSource.LOGCAT, LogLevel.ALL, 123).ok)
    }

    @Test fun joinTailCutsOnLineBoundary() {
        val l = listOf("aaaa", "bbbb", "cccc")
        assertEquals("bbbb\ncccc", LogParse.joinTail(l, 10))
        assertEquals("aaaa\nbbbb\ncccc", LogParse.joinTail(l, 100))
        assertEquals("", LogParse.joinTail(l, 3))
    }
}
