package com.senzaproject.senzatools.platform

import com.senzaproject.senzatools.platform.root.LogTools
import com.senzaproject.senzatools.platform.root.SelinuxControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogToolsTest {
    @Test fun buildsFixedCommands() {
        assertEquals("logcat -d -v threadtime -t 500", LogTools.command(LogTools.Source.LOGCAT, LogTools.Level.ALL, 500))
        assertEquals("logcat -d -v threadtime -t 1000 '*:E'", LogTools.command(LogTools.Source.LOGCAT, LogTools.Level.ERROR, 1000))
        assertEquals("logcat -d -v threadtime -t 200 '*:W'", LogTools.command(LogTools.Source.LOGCAT, LogTools.Level.WARN, 200))
        assertEquals("logcat -b crash -d -v threadtime -t 200", LogTools.command(LogTools.Source.CRASH, LogTools.Level.ERROR, 200))
        assertEquals("dmesg 2>&1 | tail -n 2000", LogTools.command(LogTools.Source.KERNEL, LogTools.Level.ALL, 2000))
    }

    @Test fun keywordFilterIsCaseInsensitiveAndLocal() {
        val l = listOf("E avc: denied foo", "I boot ok", "W FOO bar")
        assertEquals(listOf("E avc: denied foo", "W FOO bar"), LogTools.filter(l, "FoO"))
        assertEquals(l, LogTools.filter(l, "  "))
        assertEquals(emptyList<String>(), LogTools.filter(l, "; reboot"))
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
}
