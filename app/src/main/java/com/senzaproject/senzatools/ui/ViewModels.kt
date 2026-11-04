package com.senzaproject.senzatools.ui

import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.senzaproject.senzatools.analyzer.apk.ApkAnalyzer
import com.senzaproject.senzatools.analyzer.apk.ApkReport
import com.senzaproject.senzatools.analyzer.rom.RomAnalyzer
import com.senzaproject.senzatools.analyzer.rom.RomReport
import com.senzaproject.senzatools.analyzer.system.AvcDenial
import com.senzaproject.senzatools.analyzer.system.CompatChecker
import com.senzaproject.senzatools.analyzer.system.CompatFinding
import com.senzaproject.senzatools.analyzer.system.InitRcAnalyzer
import com.senzaproject.senzatools.analyzer.system.PropAnalyzer
import com.senzaproject.senzatools.analyzer.system.PropDiff
import com.senzaproject.senzatools.analyzer.system.PropParseResult
import com.senzaproject.senzatools.analyzer.system.RcAnalysis
import com.senzaproject.senzatools.analyzer.system.SelinuxAnalyzer
import com.senzaproject.senzatools.analyzer.system.XmlAnalysis
import com.senzaproject.senzatools.analyzer.system.XmlAnalyzer
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.domain.model.Severity
import com.senzaproject.senzatools.utils.Saf
import com.senzaproject.senzatools.utils.Sys
import com.senzaproject.senzatools.utils.f1
import com.senzaproject.senzatools.utils.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VmFactory(private val s: ServiceLocator) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(AnalyzerViewModel::class.java) -> AnalyzerViewModel(s) as T
        else -> throw IllegalArgumentException("ViewModel tidak dikenal: ${modelClass.name}")
    }
}

data class NamedProp(val name: String, val result: PropParseResult)
data class NamedRc(val name: String, val result: RcAnalysis)
data class NamedXml(val name: String, val result: XmlAnalysis)
data class AvcState(val source: String, val denials: List<AvcDenial>)

class AnalyzerViewModel(private val s: ServiceLocator) : ViewModel() {
    private val app = s.app

    // ---------------- properti
    var prop by mutableStateOf<NamedProp?>(null); private set
    var propBusy by mutableStateOf(false); private set
    var propError by mutableStateOf<String?>(null); private set

    // ---------------- perbandingan
    var cmpA by mutableStateOf<NamedProp?>(null); private set
    var cmpB by mutableStateOf<NamedProp?>(null); private set
    var cmpDiff by mutableStateOf<PropDiff?>(null); private set
    var cmpError by mutableStateOf<String?>(null); private set

    // ---------------- kompatibilitas
    var compatName by mutableStateOf<String?>(null); private set
    var compat by mutableStateOf<List<CompatFinding>?>(null); private set
    var compatError by mutableStateOf<String?>(null); private set

    // ---------------- APK
    var apk by mutableStateOf<ApkReport?>(null); private set
    var apkBusy by mutableStateOf(false); private set
    var apkError by mutableStateOf<String?>(null); private set

    // ---------------- ROM
    var rom by mutableStateOf<RomReport?>(null); private set
    var romBusy by mutableStateOf(false); private set
    var romProgress by mutableStateOf(""); private set
    var romError by mutableStateOf<String?>(null); private set
    var romCompat by mutableStateOf<List<CompatFinding>?>(null); private set
    private var romJob: Job? = null

    // ---------------- init.rc / XML / SELinux
    var rc by mutableStateOf<NamedRc?>(null); private set
    var xml by mutableStateOf<NamedXml?>(null); private set
    var avc by mutableStateOf<AvcState?>(null); private set
    var miscError by mutableStateOf<String?>(null); private set

    private fun readNamed(uri: Uri): Pair<String, String> = Saf.displayName(app, uri) to Saf.readText(app, uri)

    private fun propSummary(name: String, r: PropParseResult): String = buildString {
        appendLine("File: $name — ${r.props.size} properti, ${r.duplicates.size} duplikat, ${r.invalid.size} baris tidak valid")
        listOf("ro.build.version.sdk", "ro.build.version.release", "ro.build.fingerprint", "ro.product.device", "ro.vndk.version")
            .forEach { k -> r.props[k]?.let { appendLine("$k=$it") } }
        r.issues.take(20).forEach { appendLine("- [${it.severity}] ${it.message}") }
    }

    fun loadProp(uri: Uri) {
        viewModelScope.launch {
            propBusy = true; propError = null
            try {
                val np = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    NamedProp(n, PropAnalyzer.parse(t))
                }
                prop = np
                s.session.lastPropSummary = propSummary(np.name, np.result)
            } catch (t: Throwable) {
                propError = t.message ?: "Gagal membaca file"
            }
            propBusy = false
        }
    }

    fun loadDeviceProp() {
        viewModelScope.launch {
            propBusy = true; propError = null
            try {
                val np = withContext(Dispatchers.IO) { deviceProp() }
                prop = np
                s.session.lastPropSummary = propSummary(np.name, np.result)
            } catch (t: Throwable) {
                propError = t.message ?: "Gagal membaca properti"
            }
            propBusy = false
        }
    }

    private fun deviceProp(): NamedProp {
        val props = Sys.refreshProps()
        val text = props.entries.joinToString("\n") { "${it.key}=${it.value}" }
        return NamedProp("getprop (perangkat ini)", PropAnalyzer.parse(text))
    }

    // ---- bandingkan
    private fun recompute() {
        val a = cmpA
        val b = cmpB
        cmpDiff = if (a != null && b != null) PropAnalyzer.compare(a.result, b.result) else null
    }

    fun loadCompare(slotA: Boolean, uri: Uri) {
        viewModelScope.launch {
            cmpError = null
            try {
                val np = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    NamedProp(n, PropAnalyzer.parse(t))
                }
                if (slotA) cmpA = np else cmpB = np
                recompute()
            } catch (t: Throwable) {
                cmpError = t.message ?: "Gagal membaca file"
            }
        }
    }

    fun useDeviceForCompare(slotA: Boolean) {
        viewModelScope.launch {
            cmpError = null
            try {
                val np = withContext(Dispatchers.IO) { deviceProp() }
                if (slotA) cmpA = np else cmpB = np
                recompute()
            } catch (t: Throwable) {
                cmpError = t.message ?: "Gagal membaca properti"
            }
        }
    }

    // ---- kompatibilitas: sumber build.prop vs perangkat ini
    fun loadCompatSource(uri: Uri) {
        viewModelScope.launch {
            compatError = null
            try {
                val (name, list) = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    val parsed = PropAnalyzer.parse(t)
                    n to CompatChecker.check(parsed.props, Sys.refreshProps())
                }
                compatName = name
                compat = list
            } catch (t: Throwable) {
                compatError = t.message ?: "Gagal membaca file"
            }
        }
    }

    // ---- APK
    fun loadApk(uri: Uri) {
        viewModelScope.launch {
            apkBusy = true; apkError = null
            try {
                val rep = withContext(Dispatchers.IO) {
                    val name = Saf.displayName(app, uri)
                    val size = Saf.size(app, uri)
                    require(size < 2_000_000_000L) { "File terlalu besar (${size.formatBytes()}). Batas 2 GB." }
                    val f = Saf.copyToCache(app, uri, name)
                    try {
                        ApkAnalyzer(app).analyze(f, name)
                    } finally {
                        f.delete()
                    }
                }
                apk = rep
                s.session.lastApkSummary = rep.summary()
            } catch (t: Throwable) {
                apkError = t.message ?: "Gagal menganalisis APK"
            }
            apkBusy = false
        }
    }

    // ---- ROM
    fun scanRom(uri: Uri, tree: Boolean) {
        romJob?.cancel()
        romJob = viewModelScope.launch {
            romBusy = true; romError = null; romProgress = "Memulai…"; romCompat = null
            try {
                val rep = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    val active = { job?.isActive ?: true }
                    val an = RomAnalyzer(app)
                    if (tree) an.analyzeTree(uri, uri.lastPathSegment?.substringAfterLast(':') ?: "folder", active) { romProgress = it }
                    else an.analyzeZip(uri, Saf.displayName(app, uri), active) { romProgress = it }
                }
                rom = rep
                s.session.lastRomSummary = rep.summary()
                romCompat = if (rep.mergedProps.isNotEmpty()) withContext(Dispatchers.IO) { CompatChecker.check(rep.mergedProps, Sys.props()) } else null
            } catch (t: kotlinx.coroutines.CancellationException) {
                romError = "Pemindaian dibatalkan."
                throw t
            } catch (t: Throwable) {
                romError = t.message ?: "Gagal memindai ROM"
            } finally {
                romBusy = false
            }
        }
    }

    fun cancelRom() {
        romJob?.cancel()
        romBusy = false
    }

    // ---- init.rc, XML, SELinux
    fun loadRc(uri: Uri) {
        viewModelScope.launch {
            miscError = null
            try {
                rc = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    NamedRc(n, InitRcAnalyzer.analyze(t))
                }
            } catch (t: Throwable) {
                miscError = t.message
            }
        }
    }

    fun loadXml(uri: Uri) {
        viewModelScope.launch {
            miscError = null
            try {
                xml = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    NamedXml(n, XmlAnalyzer.analyze(t))
                }
            } catch (t: Throwable) {
                miscError = t.message
            }
        }
    }

    fun loadAvc(uri: Uri) {
        viewModelScope.launch {
            miscError = null
            try {
                val st = withContext(Dispatchers.IO) {
                    val (n, t) = readNamed(uri)
                    s.session.lastLog = t.takeLast(60_000)
                    AvcState(n, SelinuxAnalyzer.parse(t))
                }
                avc = st
            } catch (t: Throwable) {
                miscError = t.message
            }
        }
    }

    fun reportAvcError(message: String) {
        miscError = message
    }

    fun analyzeAvcText(source: String, text: String) {
        miscError = null
        s.session.lastLog = text.takeLast(60_000)
        avc = AvcState(source, SelinuxAnalyzer.parse(text))
    }
}

fun severityRank(s: Severity): Int = when (s) {
    Severity.ERROR -> 0
    Severity.WARN -> 1
    Severity.INFO -> 2
}
