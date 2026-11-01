package com.senzaproject.senzatools.kernel

import com.senzaproject.senzatools.kernel.KGroup.*
import com.senzaproject.senzatools.kernel.Risk.*
import com.senzaproject.senzatools.kernel.SocVendor.*

/**
 * Katalog node kernel yang didukung. Path & nama parameter berasal dari SmartPack-Kernel-Manager
 * (utils/kernel/{cpu,gpu,vm,ksm,lmk,thermal,io,battery,misc}); rentang nilai dipilih konservatif oleh Senza.
 *
 * Sengaja TIDAK diporting: undervolt CPU, fsync control, selinux, hotplug driver lawas, dan node lain yang
 * bisa menyebabkan kehilangan data atau bootloop (lihat docs/KERNEL.md).
 */
object KernelCatalog {
    private const val VM = "/proc/sys/vm"
    private const val CPUBOOST = "/sys/module/cpu_boost/parameters"
    private const val ADRENO = "/sys/module/adreno_idler/parameters"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val MSMT = "/sys/module/msm_thermal"
    private const val BLK = "/sys/block"

    private val blockDevs = listOf("sda", "mmcblk0", "vda")
    private fun blk(file: String) = blockDevs.map { "$BLK/$it/queue/$file" }

    val all: List<KNode> = listOf(
        // ---------------------------------------------------------------- CPU
        KNode("cpu_boost_input_ms", CPU, "Input boost (durasi)", "Lama CPU dinaikkan setelah sentuhan layar.",
            listOf("$CPUBOOST/input_boost_ms"), KType.Num(0, 2000, 10, "ms"), vendors = setOf(QUALCOMM)),
        KNode("cpu_boost_ms", CPU, "CPU boost (durasi)", "Lama boost saat migrasi task antar-cluster.",
            listOf("$CPUBOOST/boost_ms"), KType.Num(0, 2000, 10, "ms"), vendors = setOf(QUALCOMM)),
        KNode("cpu_sched_boost_input", CPU, "Sched boost saat input", "Boost scheduler ketika ada input sentuh.",
            listOf("$CPUBOOST/sched_boost_on_input"), KType.Toggle("Y", "N"), vendors = setOf(QUALCOMM)),
        KNode("cpu_touchboost", CPU, "Touch boost", "Boost performa saat layar disentuh (msm_performance).",
            listOf("/sys/module/msm_performance/parameters/touchboost"), KType.Toggle(), vendors = setOf(QUALCOMM)),
        KNode("cpu_wq_power_efficient", CPU, "Workqueue hemat daya", "Workqueue memilih CPU yang sedang aktif agar hemat daya.",
            listOf("/sys/module/workqueue/parameters/power_efficient"), KType.Toggle("Y", "N")),
        KNode("cpu_mc_power_saving", CPU, "Penghematan daya multi-core", "0 = mati, 1–2 = konsolidasi task ke lebih sedikit core.",
            listOf("/sys/devices/system/cpu/sched_mc_power_savings"), KType.Num(0, 2)),

        // ---------------------------------------------------------------- GPU
        KNode("gpu_adreno_idler", GPU, "Adreno Idler", "Menurunkan frekuensi GPU Adreno saat beban rendah.",
            listOf("$ADRENO/adreno_idler_active"), KType.Toggle("Y", "N"), vendors = setOf(QUALCOMM)),
        KNode("gpu_adreno_idleworkload", GPU, "Adreno Idler: beban idle", "Ambang beban dianggap idle.",
            listOf("$ADRENO/adreno_idler_idleworkload"), KType.Num(0, 20000, 100), vendors = setOf(QUALCOMM)),
        KNode("gpu_adreno_idlewait", GPU, "Adreno Idler: waktu tunggu", "Jeda sebelum GPU diturunkan.",
            listOf("$ADRENO/adreno_idler_idlewait"), KType.Num(0, 1000, 5, "ms"), vendors = setOf(QUALCOMM)),
        KNode("gpu_adreno_downdiff", GPU, "Adreno Idler: down-differential", "Selisih beban untuk turun frekuensi.",
            listOf("$ADRENO/adreno_idler_downdifferential"), KType.Num(0, 100), vendors = setOf(QUALCOMM)),
        KNode("gpu_simple_algo", GPU, "Simple GPU algorithm", "Algoritma governor GPU sederhana (kernel kustom).",
            listOf("/sys/module/simple_gpu_algorithm/parameters/simple_gpu_activate"), KType.Toggle("Y", "N"), vendors = setOf(QUALCOMM)),
        KNode("gpu_devfreq_boost_ms", GPU, "Devfreq boost (input)", "Durasi boost devfreq setelah input.",
            listOf("/sys/module/devfreq_boost/parameters/input_boost_duration"), KType.Num(0, 5000, 50, "ms")),
        KNode("gpu_default_pwrlevel", GPU, "Default power level", "Level daya GPU awal (0 = tercepat). Bergantung perangkat.",
            listOf("$KGSL/default_pwrlevel"), KType.Num(0, 15), MEDIUM, setOf(QUALCOMM)),
        KNode("gpu_idle_timer", GPU, "Idle timer GPU", "Waktu GPU menunggu sebelum masuk idle.",
            listOf("$KGSL/idle_timer"), KType.Num(10, 1000, 10, "ms"), MEDIUM, setOf(QUALCOMM)),
        KNode("gpu_force_clk_on", GPU, "Paksa clock GPU", "Menahan clock GPU tetap hidup; boros daya dan panas.",
            listOf("$KGSL/force_clk_on"), KType.Toggle(), MEDIUM, setOf(QUALCOMM)),

        // ---------------------------------------------------------------- RAM
        KNode("vm_swappiness", RAM, "Swappiness", "Kecenderungan memindahkan halaman ke swap/zram. Rendah = utamakan RAM.",
            listOf("$VM/swappiness"), KType.Num(0, 200, 5)),
        KNode("vm_vfs_cache_pressure", RAM, "VFS cache pressure", "Seberapa agresif cache direktori/inode dibuang.",
            listOf("$VM/vfs_cache_pressure"), KType.Num(1, 500, 5)),
        KNode("vm_dirty_ratio", RAM, "Dirty ratio", "Persen RAM berisi data belum ditulis sebelum proses dipaksa menulis.",
            listOf("$VM/dirty_ratio"), KType.Num(1, 90), MEDIUM, note = "Harus ≥ dirty background ratio."),
        KNode("vm_dirty_background_ratio", RAM, "Dirty background ratio", "Persen RAM sebelum penulisan latar dimulai.",
            listOf("$VM/dirty_background_ratio"), KType.Num(1, 90), MEDIUM, note = "Harus ≤ dirty ratio."),
        KNode("vm_dirty_expire", RAM, "Dirty expire", "Umur data kotor sebelum ditulis ke disk.",
            listOf("$VM/dirty_expire_centisecs"), KType.Num(0, 60000, 100, "cs"), MEDIUM),
        KNode("vm_dirty_writeback", RAM, "Dirty writeback", "Interval thread penulis data kotor.",
            listOf("$VM/dirty_writeback_centisecs"), KType.Num(0, 60000, 100, "cs"), MEDIUM),
        KNode("vm_min_free", RAM, "Min free kbytes", "Cadangan RAM bebas minimum. Terlalu besar memicu kill app; terlalu kecil memicu lag.",
            listOf("$VM/min_free_kbytes"), KType.Num(1024, 262144, 1024, "KB"), MEDIUM),
        KNode("vm_watermark_scale", RAM, "Watermark scale factor", "Jarak watermark reclaim (per 10.000 RAM).",
            listOf("$VM/watermark_scale_factor"), KType.Num(1, 1000, 5), MEDIUM),
        KNode("vm_page_cluster", RAM, "Page cluster", "Jumlah halaman dibaca per swap-in (2^n).",
            listOf("$VM/page-cluster"), KType.Num(0, 6)),
        KNode("vm_oom_kill_alloc", RAM, "OOM kill pengalokasi", "Saat OOM, bunuh proses yang meminta memori.",
            listOf("$VM/oom_kill_allocating_task"), KType.Toggle(), MEDIUM),
        KNode("vm_overcommit", RAM, "Overcommit memory", "0 = heuristik (disarankan), 1 = selalu izinkan, 2 = ketat.",
            listOf("$VM/overcommit_memory"), KType.Choice(fallback = listOf("0", "1", "2")), MEDIUM),
        KNode("ksm_run", RAM, "KSM (penggabungan halaman)", "Menggabungkan halaman identik. Hemat RAM, boros CPU.",
            listOf("/sys/kernel/mm/ksm/run", "/sys/kernel/mm/uksm/run"), KType.Toggle(), MEDIUM),
        KNode("ksm_pages", RAM, "KSM: halaman per scan", "Jumlah halaman dipindai tiap siklus.",
            listOf("/sys/kernel/mm/ksm/pages_to_scan"), KType.Num(1, 10000, 10)),
        KNode("ksm_sleep", RAM, "KSM: jeda scan", "Jeda antar siklus scan KSM.",
            listOf("/sys/kernel/mm/ksm/sleep_millisecs"), KType.Num(10, 60000, 10, "ms")),
        KNode("lmk_minfree", RAM, "LMK minfree", "Ambang memori bebas (halaman) untuk membunuh app per tingkat. Android modern memakai lmkd sehingga sering tak berpengaruh.",
            listOf("/sys/module/lowmemorykiller/parameters/minfree"), KType.Csv(6, 0, 262144), HIGH,
            note = "Nilai terlalu tinggi membunuh app terus-menerus."),

        // ---------------------------------------------------------------- Thermal
        KNode("th_msm_enabled", THERMAL, "MSM thermal", "Mematikan ini menghilangkan proteksi panas kernel — HP bisa terlalu panas.",
            listOf("$MSMT/parameters/enabled"), KType.Toggle("Y", "N"), HIGH, setOf(QUALCOMM)),
        KNode("th_msm_core_control", THERMAL, "MSM core control", "Mematikan core saat panas.",
            listOf("$MSMT/core_control/enabled"), KType.Toggle(), HIGH, setOf(QUALCOMM)),
        KNode("th_msm_vdd", THERMAL, "MSM VDD restriction", "Pembatasan tegangan saat dingin.",
            listOf("$MSMT/vdd_restriction/enabled"), KType.Toggle(), HIGH, setOf(QUALCOMM)),
        KNode("th_msm_limit_temp", THERMAL, "Batas suhu throttle", "Suhu mulai menurunkan frekuensi (°C). Dibatasi maks 85 °C.",
            listOf("$MSMT/parameters/limit_temp_degC", "$MSMT/parameters/temperature_threshold"), KType.Num(40, 85, 1, "°C"), HIGH, setOf(QUALCOMM)),
        KNode("th_msm_core_limit_temp", THERMAL, "Batas suhu core", "Suhu mulai mematikan core (°C). Dibatasi maks 90 °C.",
            listOf("$MSMT/parameters/core_limit_temp_degC", "$MSMT/parameters/core_temp_limit_degC"), KType.Num(50, 90, 1, "°C"), HIGH, setOf(QUALCOMM)),
        KNode("th_msm_hysteresis", THERMAL, "Histeresis suhu", "Selisih suhu untuk melepas throttle.",
            listOf("$MSMT/parameters/temp_hysteresis_degC", "$MSMT/parameters/core_temp_hysteresis_degC"), KType.Num(0, 20, 1, "°C"), MEDIUM, setOf(QUALCOMM)),
        KNode("th_msm_poll", THERMAL, "Interval polling", "Seberapa sering suhu diperiksa.",
            listOf("$MSMT/parameters/poll_ms"), KType.Num(50, 5000, 50, "ms"), MEDIUM, setOf(QUALCOMM)),

        // ---------------------------------------------------------------- I/O
        KNode("io_scheduler", IO, "I/O scheduler", "Algoritma antrean baca/tulis penyimpanan internal.",
            blk("scheduler"), KType.Choice()),
        KNode("io_read_ahead", IO, "Read-ahead", "Data yang dibaca lebih dulu. Besar = baca sekuensial lebih cepat.",
            blk("read_ahead_kb"), KType.Num(0, 8192, 128, "KB")),
        KNode("io_nr_requests", IO, "Jumlah request antrean", "Panjang antrean I/O.",
            blk("nr_requests"), KType.Num(4, 2048, 4), MEDIUM),
        KNode("io_add_random", IO, "Kontribusi entropi I/O", "Mematikan sedikit mengurangi beban CPU.",
            blk("add_random"), KType.Toggle()),
        KNode("io_iostats", IO, "Statistik I/O", "Mematikan mengurangi overhead kecil.",
            blk("iostats"), KType.Toggle()),
        KNode("io_rq_affinity", IO, "RQ affinity", "0 = bebas, 1 = grup CPU, 2 = CPU pengirim.",
            blk("rq_affinity"), KType.Num(0, 2)),

        // ---------------------------------------------------------------- Baterai
        KNode("bat_fast_charge", BATTERY, "Fast charge (USB)", "Memaksa fast charge lewat USB (kernel kustom). Pastikan kabel dan charger mampu.",
            listOf("/sys/kernel/fast_charge/force_fast_charge"), KType.Toggle(), MEDIUM),
        KNode("bat_blx_limit", BATTERY, "Batas pengisian (BLX)", "Berhenti mengisi di persen ini untuk menjaga usia baterai.",
            listOf("/sys/devices/virtual/misc/batterylifeextender/charging_limit"), KType.Num(20, 100, 1, "%")),

        // ---------------------------------------------------------------- Lainnya
        KNode("net_tcp_congestion", MISC, "TCP congestion control", "Algoritma kontrol kemacetan TCP.",
            listOf("/proc/sys/net/ipv4/tcp_congestion_control"),
            KType.Choice(availablePath = "/proc/sys/net/ipv4/tcp_available_congestion_control")),
        KNode("rnd_write_wakeup", MISC, "Entropy write wakeup", "Ambang entropi pool untuk membangunkan penulis.",
            listOf("/proc/sys/kernel/random/write_wakeup_threshold"), KType.Num(64, 4096, 64)),
        KNode("sched_gentle_fair", MISC, "Gentle fair sleepers", "Memperlunak preferensi task yang baru bangun.",
            listOf("/sys/kernel/sched/gentle_fair_sleepers"), KType.Toggle()),
        KNode("sched_arch_power", MISC, "Arch power", "Scheduler memperhitungkan kapasitas daya core.",
            listOf("/sys/kernel/sched/arch_power"), KType.Toggle()),
        KNode("dyn_fsync", MISC, "Dynamic fsync", "Menunda fsync saat layar menyala. Bisa kehilangan data bila perangkat mati mendadak.",
            listOf("/sys/kernel/dyn_fsync/Dyn_fsync_active"), KType.Toggle(), MEDIUM)
    )

    val byId: Map<String, KNode> = all.associateBy { it.id }

    /** Semua path yang boleh ditulis. Dipakai penjaga mesin root. */
    val allowedPaths: Set<String> = all.flatMap { it.paths }.toSet()

    fun forGroup(g: KGroup): List<KNode> = all.filter { it.group == g }
}
