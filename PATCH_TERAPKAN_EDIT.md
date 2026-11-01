# Patch: Terapkan edit file yang benar-benar bekerja (tanpa bergantung Magisk) + persen kesehatan baterai

## Masalah sebelumnya
- Partisi top-level seperti `/tr_product` (erofs, hanya-baca) ditolak: overlay Magisk hanya mengenal `/system`, `/vendor`, `/product`, `/system_ext`, `/odm`.
- Bind-mount hanya dipasang di namespace shell root, jadi proses lain bisa tidak melihatnya.
- `build.prop` dibaca init sebelum mount boot mana pun; mengganti file saja tidak mengubah `getprop`.
- Persistensi bergantung pada module Magisk.
- Daftar partisi hanya dari daftar tetap.

## Perbaikan (`RootEditor.kt`)
Mode terapkan: **Otomatis**, **Langsung**, **Bind-mount**, **Module Magisk**.
- Otomatis: langsung bila partisi bisa ditulis (rw / bisa di-remount, termasuk `blockdev --setrw`), jika gagal -> bind-mount, terakhir module Magisk.
- Bind-mount: file hasil edit disimpan di `/data/adb/senza_edit/store`, dipasang di atas file asli lewat `nsenter` ke namespace init (terlihat oleh semua proses), diverifikasi dengan `cmp`. Bekerja di partisi apa pun, termasuk erofs/squashfs dan top-level.
- Persisten tanpa module: `/data/adb/post-fs-data.d/senza_edit.sh`, `/data/adb/service.d/senza_edit.sh` (bila folder ada), dan `BootReceiver` (BOOT_COMPLETED) sebagai cadangan untuk root apa pun.
- File `*.prop`: nilai yang berubah dipasang juga lewat `resetprop` (langsung terlihat di `getprop`, dipasang ulang tiap boot).
- Tombol restart otomatis sekarang opsional.
- Pemindaian partisi: daftar umum + Transsion `tr_*` + Oplus `my_*` + semua mount erofs/ext4/f2fs/squashfs dari `/proc/mounts` + folder berisi `build.prop`.
- "Hasil edit aktif": lihat dan hapus bind-mount/overlay (file asli kembali langsung).

## Kesehatan baterai
- Persen kesehatan tampil di Home (Ringkasan kondisi) dan di Device -> Baterai.
- Bila perangkat tidak mengekspos kapasitas desain/SOH, bisa memasukkan kapasitas desain (mAh) untuk PERKIRAAN dari charge counter; ditandai jelas sebagai perkiraan.
