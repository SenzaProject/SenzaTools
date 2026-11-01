# Keamanan & Privasi

## Prinsip
- Least privilege: izin manifest hanya `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`. Tidak ada `QUERY_ALL_PACKAGES` maupun akses semua file.
- File dipilih pengguna lewat Storage Access Framework; tidak ada pemindaian penyimpanan diam-diam.
- Read-only secara default. Tidak ada operasi root tersembunyi; verifikasi root hanya setelah dialog persetujuan.
- `SafeAction` memvalidasi path (`PathGuard`: absolut, karakter aman, tanpa `..`, blokir `/proc/kcore`, `/dev/*`, dll.) dan hanya memuat perintah baca.
- Shell console: hanya perintah ketikan pengguna + dialog konfirmasi + `CommandGuard` (blokir format/tulis blok/hapus sistem/`setenforce 0`/`resetprop`/dll.). Ini lapisan pengaman dasar, **bukan** sandbox.
- Semua operasi root/shell tercatat di Log operasi (tanpa isi file).

## Data
- HTTPS wajib (`network_security_config` melarang cleartext).
- Lalu lintas jaringan keluar hanya: pemeriksaan pembaruan ke `api.github.com` (tanpa data pribadi, tanpa token) dan tes diagnostik jaringan yang dijalankan pengguna. Tautan pembaruan hanya dibuka bila berawalan `https://github.com/`.
- SSID/BSSID/kredensial jaringan tidak dikumpulkan. `allowBackup=false`.
- Tidak ada telemetri, iklan, atau akun.

## Operasi berbahaya
Fitur tulis (Editor File Root, kunci refresh rate, profil performa) hanya berjalan atas perintah pengguna dengan konfirmasi, backup otomatis, verifikasi hasil, dan log. Aplikasi tidak menulis langsung ke blok partisi dan tidak memformat atau menghapus data.

## Kernel Manager
Fitur tulis kernel hanya lewat `KernelEngine`: whitelist path dari katalog, validasi nilai dan aturan lintas-node sebelum menulis, baca-ulang + rollback, cadangan stock/undo, dan boot guard. Node berisiko tinggi tidak ikut penerapan saat boot. Detail di [KERNEL.md](KERNEL.md).

## Tanda tangan APK
Keystore `app/senza-release.jks` ikut di repo supaya semua build CI memakai tanda tangan yang sama (syarat agar APK baru bisa dipasang di atas yang lama). Karena repo-nya terbuka, siapa pun bisa menandatangani APK dengan kunci itu. Untuk distribusi publik, buat keystore pribadi dan berikan lewat environment `SENZA_KEYSTORE`, `SENZA_STORE_PASSWORD`, `SENZA_KEY_ALIAS`, `SENZA_KEY_PASSWORD` (mis. GitHub Secrets).

## Keterbatasan yang jujur
- Analisis APK bersifat indikatif, bukan jaminan keamanan.
- Daftar blokir perintah dapat dilewati oleh pengguna yang sengaja; ia mencegah kecelakaan, bukan serangan.
- Perangkat dengan root yang dikompromikan berada di luar model ancaman ini.
