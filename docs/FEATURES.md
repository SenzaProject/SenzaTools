# Fitur Senza Tools

Status: ✅ tersedia · ⚠️ sebagian/bergantung perangkat · ⏳ belum ada

## Bahasa
✅ Pilihan bahasa di Settings → Bahasa, berlaku langsung tanpa restart: Indonesia, English, Melayu, Español, Français, Deutsch, Português, Русский, Türkçe, 日本語, 한국어, 简体中文, हिन्दी, العربية, atau ikuti sistem.
⚠️ Inggris diterjemahkan hampir seluruh UI; bahasa lain baru menerjemahkan teks inti (menu, tombol, Settings) dan sisanya tampil dalam Inggris. Bahasa baru bisa ditambah dengan menaruh `assets/lang/<kode>.json` (lihat PATCH_BAHASA.md).

## Navigasi
Home · Device · Analyzer · Tools · Toolkit · Settings (floating glass nav bar).

## Home
✅ Ringkasan CPU/RAM/baterai/storage (gauge animasi), suhu, mode akses, diagnostik terbaru, pintasan.

## Device (Normal Access)
- ✅ Device info: nama, model, produsen, codename, Android/SDK, build ID, fingerprint, patch, kernel, arsitektur, ABI, bootloader, SoC, SELinux, Treble/VNDK/A-B/dynamic partitions (dari properti).
- ✅ CPU: model, core, komposisi core ARM, frekuensi, governor, status online. ⚠️ Penggunaan CPU dan suhu bergantung izin `/proc/stat` dan sysfs; bila tidak terbaca, ditampilkan sebagai estimasi (dilabeli) atau "Tidak tersedia".
- ✅ GPU: renderer, vendor, versi OpenGL ES, Vulkan. ⚠️ Utilisasi GPU hanya pada GPU yang mengeksposnya (mis. Adreno/kgsl).
- ✅ RAM, swap, storage, partisi yang terbaca, cache aplikasi.
- ✅ Baterai: level, suhu, tegangan, arus, estimasi daya, status kesehatan sistem, teknologi.
- ✅ **Kesehatan baterai nyata**: persen kesehatan dihitung dari `charge_full ÷ charge_full_design` (atau `energy_full ÷ energy_full_design`) di `/sys/class/power_supply`, plus SOH dari HAL (Android 14+), node SOH kernel, dan kapasitas hasil belajar sistem (`dumpsys batterystats`, root). Tidak ada perkiraan dari charge counter: bila perangkat tidak menyediakan data, hasilnya "Tidak tersedia". Kartu "Bukti mentah" menampilkan nilai node apa adanya beserta perintah Termux untuk mencocokkannya. ⚠️ Beberapa kernel mengisi `charge_full` secara statis (sama dengan desain); aplikasi memberi catatan bila itu terjadi.
- ✅ Layar: resolusi, densitas, refresh rate & mode, ukuran, kecerahan, HDR.
- ✅ **Kunci refresh rate 60 / 90 / 120 Hz** (root): menulis `min_refresh_rate` = `peak_refresh_rate` (+ `user_refresh_rate` bila ada), lalu penjaga menulis ulang tiap 10 detik bila firmware menimpanya. Refresh rate yang dilaporkan layar dibaca ulang dan dibandingkan, jadi bila firmware menolak, itu dilaporkan. Opsi "Kunci saat boot" (module systemless) dan "Lepas kunci" (mengembalikan pengaturan awal).
- ✅ Jaringan: tipe, IP, DNS, kecepatan link, sinyal, operator, tes diagnostik. SSID/BSSID **tidak** dikumpulkan.

## Analyzer
- ✅ Properti: parse build/system/vendor/product/odm.prop; duplikat, format salah, nilai terlalu panjang, konsistensi (SDK↔release, first_api_level, ABI↔zygote, fingerprint), ±80 penjelasan properti.
- ✅ Bandingkan build.prop: ditambah/dihapus/diubah/duplikat/konflik/tidak valid.
- ✅ APK: nama, paket, versi, SDK, ikon, ukuran, ABI, tanda tangan (sertifikat, SHA-256), izin (berbahaya), komponen (exported), native lib + **cek 16 KB**, DEX, struktur, kompatibilitas dengan perangkat. Hanya APK tunggal (bukan split/AAB).
- ✅ ROM: ZIP (streaming) atau folder terekstrak (SAF); deteksi payload.bin/super/new.dat/recovery; build.prop, init.rc, manifest/VINTF, library native; metadata OTA. ⚠️ Isi `payload.bin` tidak dapat dibaca langsung (harus diekstrak di PC).
- ✅ Kompatibilitas ROM sumber ↔ perangkat (SDK, ABI, VNDK, Treble, partisi, platform). Setiap temuan ditandai Terbukti / Kemungkinan / Belum dapat dipastikan.
- ✅ init.rc: service/trigger/import, service ganda tanpa override, izin longgar, opsi tak dikenal.
- ✅ XML/Manifest: well-formed, izin, komponen, `exported` yang hilang.
- ✅ SELinux: kelompok `avc: denied`, penjelasan, contoh rule + peringatan.
- ✅ Partisi: konfigurasi slot/dynamic/VAB, mount, penggunaan; daftar by-name (root).
- ⏳ VNDK/VAB analisis mendalam dari image (di luar cakupan versi ini).

## Tools
- ✅ Access Manager: mode aktif, deteksi KernelSU / KernelSU Next / Magisk / APatch / Shizuku, verifikasi root atas persetujuan, kemampuan & batasan per mode.
- ✅ Root File Explorer: jelajah, baca teks, cari, info/izin; edit file teks (Edit → Simpan → Terapkan) dengan backup otomatis.
- ✅ **Partisi erofs/squashfs** (hanya-baca permanen): hasil edit disimpan di module systemless, lalu langsung di-bind-mount ke path aslinya dan diverifikasi dengan `cmp`, jadi perubahan terlihat di partisi asli saat itu juga (bukan hanya di aplikasi). Untuk partisi yang tidak dikenali Magisk (mis. `/tr_product`) module memasang ulang bind-mount tiap boot lewat `post-fs-data.sh`. Daftar partisi menggabungkan path symlink (`/system/tr_product` → `/tr_product`) sehingga satu partisi tidak dihitung dua kali.
- ✅ Restart berantai (svc → reboot → sys.powerctl → SysRq) dipakai oleh "Terapkan perubahan" dan Menu daya; bila perangkat tidak restart dalam 15 detik, tombol "Restart sekarang" muncul.
- ✅ ADB info, info aplikasi, logcat viewer (penuh dengan root), shell console (ketik sendiri + konfirmasi + daftar blokir), info fastboot/bootloader.
- ⏳ Eksekusi lewat Shizuku (terdeteksi, belum dijalankan). ⏳ Kontrol fastboot dari perangkat (membutuhkan komputer/aplikasi pendamping).
- ✅ Performance Monitor: grafik CPU/RAM/suhu/baterai, proses, uptime, riwayat, ekspor CSV. Hanya berjalan saat dinyalakan.
- ✅ Log operasi.

## Toolkit
- ✅ Diagnostik lokal (tanpa internet): masalah, bukti, kepastian, rekomendasi; bagikan laporan.
- ✅ Properti, Menu daya, Ekspor laporan, Pintasan, Game, DPI, RAM, Sinyal, Charger.

## Pembaruan (OTA via GitHub Actions)
- ✅ Tiap build CI menanamkan nomor run Actions. Saat dibuka, aplikasi membandingkannya dengan run sukses terbaru di `SenzaProject/SenzaTools` (branch `main`). Bila ada yang lebih baru, hanya layar "Pembaruan wajib" yang tampil dan mengarah ke halaman run tersebut; tombol kembali menutup aplikasi.
- ✅ Hasil terakhir disimpan: memutus internet setelah update diketahui tidak membuka blokirnya. Bila pemeriksaan gagal dan belum pernah ada hasil (offline / rate limit GitHub), aplikasi tetap bisa dipakai.
- ✅ Build lokal (tanpa `GITHUB_RUN_NUMBER`) dilewati.
- ⚠️ Mengunduh artifact GitHub Actions memerlukan login GitHub di browser.
- ✅ Semua build ditandatangani keystore yang sama (`app/senza-release.jks`), sehingga APK baru bisa dipasang di atas yang lama.

## Rencana lanjutan
Shizuku exec, Room untuk riwayat jangka panjang, Hilt, modul Gradle terpisah (`core/`, `feature/`), pengujian instrumentasi di Android 12–16.

## Kernel Manager (Toolkit → Kernel)
- ✅ 50 pengaturan kernel diporting dari SmartPack: CPU boost, GPU (Adreno idler, devfreq boost, kgsl), RAM (swappiness, VM dirty, min_free, KSM, LMK), Thermal (MSM), I/O (scheduler, read-ahead…), Baterai (fast charge, BLX), TCP, scheduler.
- ✅ Deteksi kompatibilitas per node (Siap / Baca-saja / Dilindungi / Tidak ada + alasan) berdasarkan SoC, kernel, SELinux, dan keberadaan node.
- ✅ Whitelist + validasi + baca-ulang + rollback otomatis; nilai asli, Urungkan, snapshot bernama; "Terapkan saat boot" dengan boot guard.
- ⚠️ Bergantung kernel/ROM; node berisiko tinggi (proteksi thermal, LMK) butuh konfirmasi dan tidak ikut boot otomatis. Lihat [KERNEL.md](KERNEL.md).

