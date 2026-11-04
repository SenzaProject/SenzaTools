package com.senzaproject.senzatools.analyzer.system

import android.util.Xml
import com.senzaproject.senzatools.domain.model.Severity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

// ===================================================================== init.rc

data class RcService(
    val name: String,
    val path: String,
    val args: List<String>,
    val line: Int,
    val options: Map<String, List<String>>,
    val flags: Set<String>
)

data class RcTrigger(val name: String, val line: Int, val commands: List<String>)

data class RcAnalysis(
    val services: List<RcService>,
    val triggers: List<RcTrigger>,
    val imports: List<String>,
    val issues: List<Triple<Severity, Int?, String>>
)

object InitRcAnalyzer {
    private val FLAG_OPTS = setOf("oneshot", "disabled", "critical", "restart_period", "console", "enter_namespace")
    private val KNOWN_OPTS = setOf(
        "class", "user", "group", "seclabel", "capabilities", "ioprio", "priority", "socket", "file",
        "onrestart", "oneshot", "disabled", "critical", "setenv", "writepid", "namespace", "oom_score_adjust",
        "shutdown", "console", "keycodes", "interface", "override", "reboot_on_failure", "restart_period",
        "timeout_period", "updatable", "sigstop", "task_profiles", "memcg.limit_in_bytes", "memcg.limit_percent",
        "memcg.limit_property", "memcg.soft_limit_in_bytes", "memcg.swappiness", "stdio_to_kmsg", "enter_namespace",
        "user_defined_property", "apex_available", "gentle_kill", "freeze"
    )

    fun analyze(text: String): RcAnalysis {
        val services = mutableListOf<RcService>()
        val triggers = mutableListOf<RcTrigger>()
        val imports = mutableListOf<String>()
        val issues = mutableListOf<Triple<Severity, Int?, String>>()

        var curSvcName: String? = null
        var curSvcPath = ""
        var curSvcArgs = emptyList<String>()
        var curSvcLine = 0
        var curOpts = linkedMapOf<String, List<String>>()
        var curFlags = linkedSetOf<String>()
        var curTrig: String? = null
        var curTrigLine = 0
        var curCmds = mutableListOf<String>()

        fun closeService() {
            val n = curSvcName ?: return
            services += RcService(n, curSvcPath, curSvcArgs, curSvcLine, curOpts, curFlags)
            curSvcName = null
            curOpts = linkedMapOf()
            curFlags = linkedSetOf()
        }

        fun closeTrigger() {
            val t = curTrig ?: return
            triggers += RcTrigger(t, curTrigLine, curCmds)
            curTrig = null
            curCmds = mutableListOf()
        }

        text.lineSequence().forEachIndexed { idx, raw ->
            val no = idx + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val tok = line.split(Regex("\\s+"))
            when (tok[0]) {
                "import" -> {
                    closeService(); closeTrigger()
                    imports += tok.drop(1).joinToString(" ")
                }
                "service" -> {
                    closeService(); closeTrigger()
                    if (tok.size < 3) {
                        issues += Triple(Severity.ERROR, no, "Definisi service tidak lengkap (butuh: service <nama> <path> [args])")
                    } else {
                        curSvcName = tok[1]; curSvcPath = tok[2]; curSvcArgs = tok.drop(3); curSvcLine = no
                        if (!curSvcPath.startsWith("/")) issues += Triple(Severity.WARN, no, "Path service ${tok[1]} tidak absolut: ${tok[2]}")
                    }
                }
                "on" -> {
                    closeService(); closeTrigger()
                    if (tok.size < 2) issues += Triple(Severity.ERROR, no, "Trigger 'on' tanpa kondisi")
                    else {
                        curTrig = tok.drop(1).joinToString(" "); curTrigLine = no
                    }
                }
                else -> {
                    if (curSvcName != null) {
                        val opt = tok[0]
                        if (opt in FLAG_OPTS && tok.size == 1) curFlags.add(opt)
                        if (opt !in KNOWN_OPTS) issues += Triple(Severity.WARN, no, "Opsi service tidak dikenal: $opt")
                        curOpts[opt] = tok.drop(1)
                        if (opt in setOf("oneshot", "disabled", "critical", "console")) curFlags.add(opt)
                    } else if (curTrig != null) {
                        curCmds.add(line)
                        if (tok[0] == "chmod" && tok.size >= 2 && tok[1] in setOf("0777", "777", "0666", "666")) {
                            issues += Triple(Severity.WARN, no, "Izin terlalu longgar (${tok[1]}) pada: ${tok.drop(2).joinToString(" ")}")
                        }
                    } else {
                        issues += Triple(Severity.WARN, no, "Baris di luar section (service/on): \"${line.take(60)}\"")
                    }
                }
            }
        }
        closeService(); closeTrigger()

        services.groupBy { it.name }.filter { it.value.size > 1 }.forEach { (n, l) ->
            if (l.none { it.options.containsKey("override") }) {
                issues += Triple(Severity.ERROR, l.last().line, "Service '$n' didefinisikan ${l.size}× (baris ${l.joinToString { it.line.toString() }}) tanpa 'override'")
            }
        }
        services.forEach { s ->
            if (!s.options.containsKey("seclabel") && !s.options.containsKey("user")) {
                issues += Triple(Severity.INFO, s.line, "Service '${s.name}' tanpa 'user'/'seclabel' (berjalan sebagai root dengan label default)")
            }
            if (s.options["user"]?.firstOrNull() == "root" && s.options["seclabel"] == null) {
                issues += Triple(Severity.INFO, s.line, "Service '${s.name}' berjalan sebagai root tanpa seclabel eksplisit; pastikan domain SELinux sesuai")
            }
            if (s.flags.contains("critical") && s.flags.contains("oneshot")) {
                issues += Triple(Severity.WARN, s.line, "Service '${s.name}' bertanda critical dan oneshot sekaligus; kombinasi tidak lazim")
            }
        }
        return RcAnalysis(services, triggers, imports, issues)
    }
}

// ===================================================================== XML / Manifest

data class XmlAnalysis(
    val wellFormed: Boolean,
    val error: String?,
    val rootTag: String?,
    val elementCount: Int,
    val maxDepth: Int,
    val topTags: List<Pair<String, Int>>,
    val permissions: List<String>,
    val components: List<String>,
    val issues: List<Pair<Severity, String>>
)

object XmlAnalyzer {
    private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    fun analyze(text: String): XmlAnalysis {
        val counts = linkedMapOf<String, Int>()
        val perms = mutableListOf<String>()
        val comps = mutableListOf<String>()
        val issues = mutableListOf<Pair<Severity, String>>()
        var root: String? = null
        var depth = 0
        var maxDepth = 0
        var total = 0
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(text.removePrefix("﻿")))
            var curComp: String? = null
            var curCompDepth = -1
            var hasFilter = false
            var hasExported = false
            var targetSdk: Int? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        maxDepth = maxOf(maxDepth, depth)
                        total++
                        val n = p.name
                        if (root == null) root = n
                        counts[n] = (counts[n] ?: 0) + 1
                        when (n) {
                            "uses-permission", "uses-permission-sdk-23" -> p.getAttributeValue(null, "android:name")?.let { perms += it }
                            "uses-sdk" -> targetSdk = p.getAttributeValue(null, "android:targetSdkVersion")?.toIntOrNull()
                        }
                        if (n in COMPONENTS && curComp == null) {
                            curComp = p.getAttributeValue(null, "android:name") ?: "(tanpa nama)"
                            curCompDepth = depth
                            hasFilter = false
                            hasExported = p.getAttributeValue(null, "android:exported") != null
                            comps += "$n: $curComp"
                        } else if (n == "intent-filter" && curComp != null) {
                            hasFilter = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (curComp != null && depth == curCompDepth) {
                            if (hasFilter && !hasExported) {
                                issues += Severity.WARN to "Komponen $curComp punya intent-filter tanpa android:exported (wajib untuk targetSdk 31+)"
                            }
                            curComp = null
                            curCompDepth = -1
                        }
                        depth--
                    }
                }
                ev = p.next()
            }
            if (root == "manifest") {
                if (targetSdk == null) issues += Severity.INFO to "uses-sdk/targetSdkVersion tidak ditemukan di manifest ini (mungkin diatur Gradle)"
                if (perms.size != perms.distinct().size) issues += Severity.WARN to "Ada uses-permission duplikat"
            }
            return XmlAnalysis(true, null, root, total, maxDepth, counts.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }, perms, comps, issues)
        } catch (e: XmlPullParserException) {
            return XmlAnalysis(false, "Baris ${e.lineNumber}, kolom ${e.columnNumber}: ${e.message?.take(160)}", root, total, maxDepth, emptyList(), perms, comps, issues)
        } catch (e: Throwable) {
            return XmlAnalysis(false, e.message, root, total, maxDepth, emptyList(), perms, comps, issues)
        }
    }
}
