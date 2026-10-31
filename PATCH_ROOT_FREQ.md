# Patch: root sekali + frekuensi CPU/GPU terkunci

## Root
- Root diminta SEKALI otomatis saat apk dibuka (`AccessManager.autoRequestRootOnce()` dipanggil dari `MainActivity`).
- Kalau sudah diizinkan di root manager (Magisk/KernelSU/APatch), pengecekan berjalan senyap dan semua fitur langsung aktif.
- Tidak ada dialog konfirmasi lagi di tab Akses. Tombol "Minta izin root" hanya untuk kasus izin tadi ditolak.
- `isRootVerified` sekarang state Compose, jadi layar langsung aktif begitu root disetujui (tanpa buka-tutup tab).

## Frekuensi CPU/GPU
Penyebab frekuensi "turun/pindah sendiri": nilai hanya ditulis SEKALI ke sysfs, lalu governor, PPM (MediaTek),
GED, dan HAL performa vendor menimpanya lagi. Perbaikan:
- `assets/senza_lock.sh`: skrip root yang menerapkan target, memeriksa hasilnya, lalu (mode penjaga) menulis ulang
  tiap 3 detik bila ada yang menurunkan nilainya. Hanya menulis bila nilai berbeda (tanpa fork berlebih).
- Kunci "min = max" untuk CPU dan GPU (devfreq) supaya frekuensi benar-benar ditahan, bukan cuma batas atas.
- Profil baru "Maksimal" (CPU & GPU dikunci di frekuensi tertinggi).
- Tambahan MediaTek (hanya bila node-nya ada): `ppm hard_userlimit_*`, GED `custom_boost/upbound`, `gpufreq_opp_freq`.
- Toggle opsional "Abaikan limit thermal GPU" (default mati, berisiko panas).
- Governor ditulis sebelum frekuensi; kegagalan dilaporkan dengan nilai yang benar-benar terbaca kernel.
- "Terapkan saat boot" sekarang ikut membawa penjaga.
- Tampilan frekuensi aktual (`scaling_cur_freq` / `cur_freq`).
