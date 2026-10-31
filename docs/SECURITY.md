# Keamanan & Privasi

## Prinsip
- Least privilege: izin manifest hanya `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`. Tidak ada `QUERY_ALL_PACKAGES` maupun akses semua file.
- File dipilih pengguna lewat Storage Access Framework; tidak ada pemindaian penyimpanan diam-diam.
- Read-only secara default. Tidak ada operasi root tersembunyi; verifikasi root hanya setelah dialog persetujuan.
- `SafeAction` memvalidasi path (`PathGuard`: absolut, karakter aman, tanpa `..`, blokir `/proc/kcore`, `/dev/*`, dll.) dan hanya memuat perintah baca.
- Shell console: hanya perintah ketikan pengguna + dialog konfirmasi + `CommandGuard` (blokir format/tulis blok/hapus sistem/`setenforce 0`/`resetprop`/dll.). Ini lapisan pengaman dasar, **bukan** sandbox.
- **AI tidak pernah mengeksekusi perintah.** Respons yang memuat perintah berisiko diberi peringatan oleh `ResponseValidator`.
- Semua operasi root/shell/AI tercatat di Log operasi (tanpa isi file maupun jawaban AI).

## Data
- API key disimpan terenkripsi (AES-GCM, Android Keystore), tidak ditampilkan ulang dan tidak dicatat.
- HTTPS wajib (`network_security_config` melarang cleartext; klien menolak base URL non-https).
- Lampiran ke AI default **mati**; hanya yang dicentang pengguna yang dikirim.
- SSID/BSSID/kredensial jaringan tidak dikumpulkan. `allowBackup=false`.
- Tidak ada telemetri, iklan, atau akun.

## Operasi berbahaya
Aplikasi tidak menyediakan operasi yang mengubah partisi, menghapus data, atau memodifikasi konfigurasi sistem. Bila fitur tulis ditambahkan kelak, wajib memenuhi: penjelasan, risiko, validasi target, konfirmasi, backup, timeout, log, dan pemulihan.

## Keterbatasan yang jujur
- Analisis APK bersifat indikatif, bukan jaminan keamanan.
- Daftar blokir perintah dapat dilewati oleh pengguna yang sengaja; ia mencegah kecelakaan, bukan serangan.
- Perangkat dengan root yang dikompromikan berada di luar model ancaman ini.
