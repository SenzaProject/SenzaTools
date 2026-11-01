# Kernel Manager (Toolkit → Kernel)

Pengatur parameter kernel berbasis root: CPU (boost), GPU, RAM (VM, KSM, LMK), Thermal, I/O, Baterai, dan lainnya.
Daftar node diporting dari [SmartPack-Kernel-Manager](https://github.com/SmartPack/SmartPack-Kernel-Manager) (GPLv3);
lihat [NOTICE-SmartPack.md](NOTICE-SmartPack.md).

Frekuensi/governor CPU-GPU, profil, dan penjaga frekuensi tetap ada di **Tools → Performa** (tidak diduplikasi).

## Tahapan dan lokasinya di kode

| Tahap | Isi | Berkas |
|---|---|---|
| 1 Analisis | SmartPack = Java + XML view (±46 rb baris), Kernel Adiutor-style. Tidak bisa disalin ke Kotlin/Compose; yang bernilai adalah daftar node + logika deteksinya. | — |
| 2 Ekstraksi | 50 node dari modul `cpu/boost`, `gpu`, `vm`, `ksm`, `lmk`, `thermal`, `io`, `battery`, `misc`, `cpu/Misc`, jadi katalog data-driven. | `kernel/KernelCatalog.kt`, `KernelModel.kt` |
| 3 Integrasi Senza | Package `com.senzaproject.senzatools.kernel`, UI Compose liquid-glass (`KernelTab`), i18n lewat `tr()`, tanpa dependensi baru dan tanpa izin manifest baru. Tab **Kernel** di Toolkit. | `ui/screens/KernelTab.kt`, `di/ServiceLocator.kt` |
| 4 Root Engine | Semua tulis lewat `AccessManager.runRootEdit` (tercatat di Log operasi) dengan whitelist, validasi, baca-ulang, rollback. | `kernel/KernelEngine.kt`, `KernelRules.kt` |
| 5 Kompatibilitas | Deteksi SoC (Qualcomm/MediaTek/Exynos/Tensor/UNISOC), versi kernel, SELinux, RAM; tiap node: **Siap / Baca-saja / Dilindungi / Tidak ada** beserta alasannya. | `KernelRules.parseRead`, `detectSoc` |
| 6 Stabilitas | Nilai asli (stock), Urungkan, snapshot bernama, boot guard, 19 unit test termasuk eksekusi skrip shell sungguhan. | `KernelStore.kt`, `app/src/test/.../KernelRulesTest.kt` |

## Model keamanan

1. **Whitelist**: hanya path yang tercantum pada node katalog yang bisa ditulis. Tidak ada input path bebas.
2. **Validasi atomik**: tipe (toggle/rentang/pilihan/CSV), karakter nilai (`[A-Za-z0-9_.,:+-]`), aturan lintas-node
   (mis. dirty background ≤ dirty ratio, batas suhu core ≥ throttle). Satu saja gagal → tidak ada yang ditulis.
3. **Baca-ulang + rollback**: setiap tulis dibaca lagi; bila kernel menyimpan nilai lain atau menolak, nilai lama dipulihkan otomatis.
4. **Cadangan**: nilai asli dicatat sebelum perubahan pertama (reset otomatis bila ROM/kernel berganti); nilai sebelum tiap penerapan disimpan untuk Urungkan.
5. **Tingkat risiko**: Aman / Hati-hati / **Berisiko**. Berisiko (proteksi thermal, LMK minfree) butuh konfirmasi tegas dan **tidak pernah** ikut diterapkan saat boot. Batas suhu dibatasi (throttle ≤ 85 °C, core ≤ 90 °C).
6. **Boot guard** (`/data/adb/senza_kernel`): penanda `pending` dibuat sebelum menerapkan dan dihapus setelah 120 detik. Bila boot berikutnya masih menemukan `pending`, penerapan otomatis dilewati dan dinonaktifkan. Skrip memvalidasi ulang path dan nilai di sisi shell. Perubahan sysfs hilang sendiri saat restart.
7. **Sengaja tidak diporting**: undervolt CPU, fsync control, SELinux, hotplug driver lawas, KCAL/gamma, sound/LED/wakelock/gesture — risiko kehilangan data/bootloop atau terlalu bervariasi antar-perangkat. Bisa ditambah bertahap lewat katalog.

## Menambah node
Tambahkan satu `KNode(...)` di `KernelCatalog.all`. UI, deteksi, validasi, snapshot, dan boot otomatis mengikuti. Jalankan `./gradlew testDebugUnitTest`.

## Batasan jujur
- Dukungan node sepenuhnya bergantung kernel/ROM. Banyak ponsel modern tidak menyediakan node lawas (msm_thermal, LMK in-kernel, cpu_boost); itu tampil sebagai "Tidak tersedia" dengan alasan, bukan error.
- SELinux Enforcing dapat mengunci node walau root; dilaporkan sebagai "Dilindungi"/"Baca-saja".
- Logika UI (Compose) dan mesin root belum diuji di perangkat nyata oleh pembuatnya; unit test hanya mencakup aturan murni dan skrip shell. Uji dulu pada nilai aman (mis. swappiness) sebelum mengubah node Hati-hati/Berisiko.
- Teks katalog (judul/deskripsi) saat ini hanya berbahasa Indonesia; chrome UI sudah punya terjemahan Inggris.
