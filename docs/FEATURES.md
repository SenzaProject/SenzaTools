# Fitur Senza AI

Status: ✅ tersedia · ⚠️ sebagian/bergantung perangkat · ⏳ belum ada

## Navigasi
Home · Device · Analyzer · Tools · AI · Settings (floating glass nav bar).

## Home
✅ Ringkasan CPU/RAM/baterai/storage (gauge animasi), suhu, mode akses, diagnostik terbaru, pintasan.

## Device (Normal Access)
- ✅ Device info: nama, model, produsen, codename, Android/SDK, build ID, fingerprint, patch, kernel, arsitektur, ABI, bootloader, SoC, SELinux, Treble/VNDK/A-B/dynamic partitions (dari properti).
- ✅ CPU: model, core, komposisi core ARM, frekuensi, governor, status online. ⚠️ Penggunaan CPU dan suhu bergantung izin `/proc/stat` dan sysfs; bila tidak terbaca, ditampilkan sebagai estimasi (dilabeli) atau "Tidak tersedia".
- ✅ GPU: renderer, vendor, versi OpenGL ES, Vulkan. ⚠️ Utilisasi GPU hanya pada GPU yang mengeksposnya (mis. Adreno/kgsl).
- ✅ RAM, swap, storage, partisi yang terbaca, cache aplikasi.
- ✅ Baterai: level, suhu, tegangan, arus, estimasi daya, kesehatan, teknologi. ⚠️ Cycle count (Android 14+), kapasitas.
- ✅ Layar: resolusi, densitas, refresh rate & mode, ukuran, kecerahan, HDR.
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
- ✅ Root File Explorer: jelajah, baca teks, cari, info/izin. **Read-only.**
- ✅ ADB info, info aplikasi, logcat viewer (penuh dengan root), shell console (ketik sendiri + konfirmasi + daftar blokir), info fastboot/bootloader.
- ⏳ Eksekusi lewat Shizuku (terdeteksi, belum dijalankan). ⏳ Kontrol fastboot dari perangkat (membutuhkan komputer/aplikasi pendamping).
- ✅ Performance Monitor: grafik CPU/RAM/suhu/baterai, proses, uptime, riwayat, ekspor CSV. Hanya berjalan saat dinyalakan.
- ✅ Log operasi.

## AI
- ✅ Senza Smart Diagnostics (lokal): masalah, bukti, kepastian, rekomendasi; bagikan laporan.
- ✅ Chat AI (OpenAI-compatible atau Anthropic, kunci milik pengguna) dengan lampiran opsional: ringkasan perangkat, diagnostik, properti, APK, ROM, log.
- ✅ Prompt mewajibkan pemisahan Terbukti/Kemungkinan/Belum pasti; respons divalidasi dan perintah berisiko diberi peringatan. AI **tidak** pernah menjalankan perintah.

## Rencana lanjutan
Shizuku exec, Room untuk riwayat jangka panjang, Hilt, modul Gradle terpisah (`core/`, `feature/`), pengujian instrumentasi di Android 12–16.
