package com.senzaproject.senzatools.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.FakeDeviceEngine
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch

/**
 * Fake Device: samarkan model perangkat dan SoC lewat resetprop (butuh root + Magisk/KernelSU/APatch)
 * supaya game membuka opsi grafis yang biasanya terkunci per perangkat.
 */
@Composable
fun FakeDeviceTab(s: ServiceLocator) {
    val eng = s.fakeDevice
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val st by s.access.state.collectAsState()
    val rootOk = st.let { s.access.isRootVerified }

    var deviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var socId by rememberSaveable { mutableStateOf<String?>(null) }
    var persist by rememberSaveable { mutableStateOf(true) }
    var deep by rememberSaveable { mutableStateOf(false) }
    var game by rememberSaveable { mutableStateOf(false) }
    var fpToo by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<FakeDeviceEngine.Status?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmSoft by remember { mutableStateOf(false) }
    var needReboot by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var tInfo by remember { mutableStateOf<FakeDeviceEngine.TranssionInfo?>(null) }
    var tActive by remember { mutableStateOf(false) }
    var tMsg by remember { mutableStateOf<String?>(null) }
    var gamePkg by rememberSaveable { mutableStateOf("") }
    var resetFull by rememberSaveable { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetMsg by remember { mutableStateOf<String?>(null) }
    var resetOk by remember { mutableStateOf(true) }

    LaunchedEffect(rootOk, tick) {
        if (rootOk) {
            val cur = eng.status()
            status = cur
            val ti = eng.transsionInfo()
            tInfo = ti
            tActive = eng.transsionActive(ti)
            if (tick == 0) {
                if (deviceId == null) deviceId = cur.deviceId
                if (socId == null) socId = cur.socId
                deep = cur.deep
                game = cur.game
                fpToo = cur.fingerprint
            }
        }
    }

    val activeDevice = FakeDeviceEngine.device(status?.deviceId)
    val activeSoc = FakeDeviceEngine.soc(status?.socId)

    GlassCard(strong = true) {
        Heading("Fake Device")
        Body("Samarkan model perangkat dan SoC agar game membuka opsi grafis (kualitas, FPS) yang biasanya hanya untuk HP tertentu. Memakai resetprop, butuh root dan Magisk/KernelSU/APatch.", dim = true)
        if (!rootOk) Caption("Fitur ini butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.", color = g.warn)
        KeyValue("Dilihat aplikasi (Build.MODEL)", "${Build.MANUFACTURER} ${Build.MODEL}")
        KeyValue("SoC dilihat aplikasi", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        if (rootOk) {
            val st0 = status
            when {
                st0 == null -> Caption("Memeriksa status…")
                st0.resetprop == null -> StatusPill("resetprop tidak ditemukan", g.bad)
                activeDevice != null || activeSoc != null -> StatusPill(
                    "Aktif: " + listOfNotNull(activeDevice?.marketName, activeSoc?.name).joinToString(" + ") + (if (st0.deep) " (Mode dalam)" else "") + (if (st0.game) " (Mode game" + (if (st0.fingerprint) " + fingerprint" else "") + ")" else ""),
                    g.warn
                )
                else -> StatusPill("Tidak ada penyamaran aktif", g.good)
            }
        }
    }

    GlassCard {
        Heading("Pilih device")
        Caption("Model asli perangkat kelas atas dari berbagai merek.")
        ChoiceRow("Device asli (tidak diubah)", null, deviceId == null) { deviceId = null }
        FakeDeviceEngine.DEVICES.forEach { d ->
            ChoiceRow(d.marketName, "${d.brand} · ${d.model}" + (d.codename?.let { " · $it" } ?: ""), deviceId == d.id) { deviceId = d.id }
        }
    }

    GlassCard {
        Heading("Pilih SoC")
        Caption("Mengubah nama SoC yang dilaporkan sistem (ro.soc.model). Kecepatan CPU/GPU asli tidak berubah.")
        ChoiceRow("SoC asli (tidak diubah)", null, socId == null) { socId = null }
        Caption("Snapdragon")
        FakeDeviceEngine.SOCS.filter { it.vendor == FakeDeviceEngine.Vendor.QUALCOMM }.forEach { c ->
            ChoiceRow(c.name, "${c.code} · ${c.note}", socId == c.id) { socId = c.id }
        }
        Caption("MediaTek")
        FakeDeviceEngine.SOCS.filter { it.vendor == FakeDeviceEngine.Vendor.MEDIATEK }.forEach { c ->
            ChoiceRow(c.name, "${c.code} · ${c.note}", socId == c.id) { socId = c.id }
        }
    }

    val ti = tInfo
    if (rootOk && ti != null && ti.isTranssion) {
        GlassCard {
            Heading("Transsion (Infinix / Tecno / itel)")
            Body("Di HP Transsion nama chip di Pengaturan dan DevCheck dibaca dari berkas sistem. Android 14–15: product/etc/build.prop. Android 16+: cpu_info di tr_product. Tombol di bawah menulis SoC yang dipilih ke berkas itu.", dim = true)
            KeyValue("Android (API)", ti.sdk.toString())
            KeyValue("Berkas", if (ti.targets.isEmpty()) "tidak ditemukan" else ti.targets.joinToString("\n"))
            when {
                ti.kind == null -> StatusPill("Android ini belum didukung", g.bad)
                tActive -> StatusPill("Berkas Transsion sedang diganti", g.warn)
                else -> StatusPill("Berkas asli", g.good)
            }
            Caption("Memakai bind-mount lewat Editor File Root: tanpa Magisk, berkas asli tidak ditimpa, dan tetap aktif setelah restart. Restart setelah menerapkan.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Terapkan ke berkas Transsion", {
                    scope.launch {
                        busy = true; tMsg = null
                        val soc = FakeDeviceEngine.soc(socId)
                        tMsg = if (soc == null) "Pilih SoC dulu." else {
                            val r = eng.applyTranssion(soc)
                            if (r.ok) needReboot = true
                            r.message
                        }
                        tick++
                        busy = false
                    }
                }, Modifier.weight(1f), primary = true, enabled = !busy && socId != null && ti.kind != null && ti.targets.isNotEmpty())
                GlassButton("Pulihkan berkas", {
                    scope.launch {
                        busy = true
                        val r = eng.restoreTranssion()
                        tMsg = r.message
                        if (r.ok) needReboot = true
                        tick++
                        busy = false
                    }
                }, Modifier.weight(1f), enabled = !busy && tActive)
            }
            if (ti.sdk >= 36) {
                Heading("Profil game Android 16+ (Infinix GT 50 Pro)")
                Caption("Mengikuti module yang sudah jalan di GT 50 Pro (144 fps): ro.product name/device/brand/manufacturer/model di main, odm, vendor, vendor_dlkm, bootimage ke X6891, cpu_info jadi Dimensity 8500 Ultimate, dan package_cache dibersihkan. Fingerprint tidak diubah. Selalu tetap setelah restart. Pakai Kembalikan ke asli untuk membatalkan.")
                GlassButton("Terapkan profil GT 50 Pro", {
                    scope.launch {
                        busy = true; tMsg = null; failures = emptyList()
                        val r = eng.applyTranssionProfile()
                        tMsg = r.message
                        failures = r.failures
                        if (r.ok) needReboot = true
                        tick++
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), primary = true, enabled = rootOk && !busy && ti.kind == FakeDeviceEngine.TranssionKind.CPU_INFO && ti.targets.isNotEmpty())
            }
            tMsg?.let { Body(it) }
        }
    }

    GlassCard {
        Heading("Terapkan")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Body("Tetap setelah restart")
                Caption("Memasang module systemless (/data/adb/modules/senza_fakedevice). Bila mati, penyamaran hilang saat restart.")
            }
            Switch(persist, { persist = it })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Body("Mode dalam (agar terbaca di DevCheck)")
                Caption("Juga mengubah ro.board.platform dan ro.hardware agar chip terbaca di DevCheck dan aplikasi sejenis. Hanya sampai restart, terapkan lagi setelah boot. Ada risiko kecil driver/kamera gagal dimuat; restart memulihkan.")
            }
            Switch(deep, { deep = it })
        }
        val selSoc = FakeDeviceEngine.soc(socId)
        if (deep && selSoc != null && selSoc.platform == null) {
            Caption("Kode platform ${selSoc.name} belum diketahui, Mode dalam dilewati. Untuk DevCheck pilih Snapdragon 8 Elite / 8 Elite Gen 5 atau Dimensity 9600 Pro / 9500 / 9400+ / 9300+.", color = g.warn)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Body("Mode game (ubah kode device)")
                Caption("Juga mengubah ro.product.device dan ro.product.name, karena banyak game memilih profil grafis dari Build.DEVICE, bukan Build.MODEL. Hanya untuk device yang tertera kodenya di daftar. Ikut Tetap setelah restart. Bila boot bermasalah: masuk safe mode Magisk atau hapus /data/adb/modules/senza_fakedevice.")
            }
            Switch(game, { game = it; if (!it) fpToo = false })
        }
        val selDev = FakeDeviceEngine.device(deviceId)
        if (game && (selDev == null || selDev.codename == null)) {
            Caption(if (selDev == null) "Mode game butuh device yang dipilih." else "Kode asli ${selDev.marketName} belum diketahui, Mode game dilewati (tidak dikarang). Pilih OnePlus 13 atau Pixel 10 Pro XL.", color = g.warn)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Body("Ubah fingerprint juga")
                Caption("Mengganti merek/nama/device di depan fingerprint asli HP-mu (versi dan build id tetap). Android mengira ada pembaruan: boot berikutnya dan setelah Kembalikan ke asli bisa beberapa menit karena aplikasi dioptimalkan ulang. Play Integrity hampir pasti gagal. Nyalakan hanya bila Mode game saja belum cukup.", color = if (fpToo) g.warn else null)
            }
            Switch(fpToo, { fpToo = it }, enabled = game)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Terapkan", {
                scope.launch {
                    busy = true; msg = null; failures = emptyList()
                    val r = eng.apply(FakeDeviceEngine.device(deviceId), FakeDeviceEngine.soc(socId), persist, deep, game, fpToo && game)
                    msg = r.message
                    failures = r.failures
                    if (r.ok) needReboot = true
                    tick++
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy && (deviceId != null || socId != null))
            GlassButton("Kembalikan ke asli", { confirmRestore = true }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        msg?.let { Body(it) }
        failures.take(6).forEach { Caption(it, color = g.bad) }
        if (needReboot && !busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Soft restart", { confirmSoft = true }, Modifier.weight(1f), primary = true, enabled = rootOk)
                GlassButton("Restart penuh", { confirmReboot = true }, Modifier.weight(1f), danger = true, enabled = rootOk)
            }
        }
    }

    GlassCard {
        Heading("Reset konfigurasi game (tanpa hapus data)")
        Caption("Game menilai perangkat sekali lalu menyimpan hasilnya (tingkat grafis, FPS) di datanya sendiri, jadi fake device baru terbaca setelah itu dihapus. Fitur ini hanya membuang penilaian itu, bukan resource unduhan (OBB dan Android/data/<paket>/files), jadi tidak perlu unduh ulang. Urutan: Terapkan, Restart, lalu Reset game ini, lalu buka game.")
        GlassTextField(gamePkg, { gamePkg = it.trim() }, "Nama paket, mis. com.mobile.legends", mono = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Body("Hapus juga pengaturan dan config grafis")
                Caption("Aktif (disarankan): hapus cache + shared_prefs + folder Saved/Config. Mati: hanya cache. Aktif bisa membuat game minta login ulang dan mengembalikan pengaturan grafis ke awal.")
            }
            Switch(resetFull, { resetFull = it })
        }
        GlassButton("Reset game ini", { confirmReset = true }, Modifier.fillMaxWidth(), primary = true, enabled = rootOk && !busy && gamePkg.isNotBlank())
        resetMsg?.let { Body(it, color = if (resetOk) null else g.bad) }
    }

    ExpandableCard("Catatan penting", false) {
        Body("• Game membaca model saat perangkat menyala, jadi perubahan baru terlihat penuh setelah restart. Setelah restart, buka game dan cek pengaturan grafis.", dim = true)
        Body("• Yang berubah hanya nama yang dilaporkan sistem. Performa CPU/GPU asli tetap. Grafis maksimal di HP lemah bisa lag, panas, atau FC.", dim = true)
        Body("• Tanpa Mode dalam, ro.board.platform dan ro.hardware tidak disentuh (dipakai sistem memuat driver). Dengan Mode dalam, keduanya diubah hanya sampai restart dan tidak pernah dipakai saat boot.", dim = true)
        Body("• DevCheck membaca nama chip dari platform/hardware, tetapi detail inti CPU (mis. 6× Cortex-A55 + 2× Cortex-A76), GPU (Mali), dan /proc/cpuinfo dibaca dari kernel dan tidak bisa dipalsukan lewat properti.", dim = true)
        Body("• DevCheck memakai database chip sendiri; chip yang sangat baru bisa tampil Unknown. Hasil terbaik: Snapdragon 8 Elite / 8 Elite Gen 5 atau Dimensity 9600 Pro / 9500 / 9400+ / 9300+. Tutup paksa DevCheck sebelum membuka lagi.", dim = true)
        Body("• Aplikasi bank dan pengecekan integritas (Play Integrity) bisa menolak perangkat yang modelnya diubah. Kembalikan ke asli bila perlu.", dim = true)
        Body("• Kalau game tetap membaca perangkat lama setelah restart, itu karena game menyimpan penilaian perangkat di datanya. Pakai Reset konfigurasi game di atas, bukan hapus data. Game yang membaca GL_RENDERER atau /proc/cpuinfo tetap melihat perangkat asli dan tidak terbantu fitur ini.", dim = true)
        Body("• Urutan bila game tetap membaca perangkat lama: aktifkan Mode game (dan fingerprint hanya bila perlu), Terapkan, Restart, lalu Reset konfigurasi game. Fingerprint tidak dikarang dari nol; hanya tiga bagian depan fingerprint aslimu yang diganti.", dim = true)
        Body("• Aturan akun game berbeda-beda; memakai penyamaran perangkat tanggung jawab sendiri.", dim = true)
    }

    if (confirmRestore) {
        ConfirmDialog(
            title = "Kembalikan ke asli?",
            message = "Semua properti dikembalikan ke nilai awal dan module boot dihapus. Restart perangkat agar aplikasi membaca nilai asli.",
            confirmText = "Kembalikan",
            onConfirm = {
                confirmRestore = false
                scope.launch {
                    busy = true; failures = emptyList()
                    val r = eng.restore()
                    val t = tInfo
                    if (t != null && tActive) {
                        val tr = eng.restoreTranssion()
                        tMsg = tr.message
                    }
                    msg = r.message
                    failures = r.failures
                    if (r.ok) { deviceId = null; socId = null; needReboot = true }
                    tick++
                    busy = false
                }
            },
            onDismiss = { confirmRestore = false }
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset konfigurasi game?",
            message = "Game $gamePkg akan ditutup paksa lalu cache" + (if (resetFull) ", pengaturan, dan config grafisnya" else "-nya") + " dihapus. OBB dan data unduhan tidak disentuh. Akun bisa minta login ulang.",
            confirmText = "Reset",
            danger = true,
            onConfirm = {
                confirmReset = false
                scope.launch {
                    busy = true; resetMsg = null
                    val r = eng.resetGameConfig(
                        gamePkg,
                        if (resetFull) FakeDeviceEngine.ResetLevel.CONFIG else FakeDeviceEngine.ResetLevel.CACHE
                    )
                    resetOk = r.ok
                    resetMsg = r.message
                    busy = false
                }
            },
            onDismiss = { confirmReset = false }
        )
    }

    if (confirmSoft) {
        ConfirmDialog(
            title = "Soft restart?",
            message = "Sistem dimulai ulang tanpa reboot penuh (zygote). Layar kembali ke lock screen dan semua aplikasi tertutup. Simpan pekerjaan dulu.",
            confirmText = "Soft restart",
            danger = true,
            onConfirm = {
                confirmSoft = false
                scope.launch {
                    busy = true
                    if (!eng.softRestart()) msg = "Soft restart gagal. Gunakan Restart penuh."
                    busy = false
                }
            },
            onDismiss = { confirmSoft = false }
        )
    }

    if (confirmReboot) {
        ConfirmDialog(
            title = "Restart perangkat?",
            message = "Simpan pekerjaan di aplikasi lain dulu. Perangkat akan restart sekarang.",
            confirmText = "Restart",
            danger = true,
            onConfirm = {
                confirmReboot = false
                scope.launch {
                    busy = true
                    val rr = s.access.rebootDevice()
                    if (!rr.ok) msg = "Perintah restart gagal: ${rr.output.take(120)}"
                    busy = false
                }
            },
            onDismiss = { confirmReboot = false }
        )
    }
}

@Composable
private fun ChoiceRow(title: String, sub: String?, selected: Boolean, onSelect: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RadioButton(
            selected, onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = g.accent, unselectedColor = g.textDim)
        )
        Column(Modifier.weight(1f)) {
            Body(title)
            if (sub != null) Caption(sub)
        }
    }
}
