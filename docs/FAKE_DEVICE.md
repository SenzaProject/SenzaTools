# Fake Device

Fitur di **Toolkit → Fake Device** untuk menyamarkan model perangkat dan SoC agar game membuka opsi grafis (kualitas, FPS) yang biasanya hanya untuk HP tertentu. Butuh **root** dan **Magisk / KernelSU / APatch** (memakai `resetprop`).

## Pilihan
- **10 device kelas atas**: Samsung Galaxy S26 Ultra (SM-S948B), ASUS ROG Phone 9 Pro (ASUS_AI2501), RedMagic 11 Pro (NX809J), OnePlus 13 (CPH2655), Xiaomi 15 Ultra (25010PN30G), vivo X500 Pro Max (V2610), Google Pixel 10 Pro XL, OPPO Find X8 Pro (CPH2659), HONOR Magic7 Pro (PTP-AN10), realme GT 7 Pro (RMX5010).
- **SoC**: Snapdragon 8 Elite Extreme Gen 6 (SM8975), 8 Elite Gen 6 (SM8950), 8 Elite Gen 5 (SM8850), 8 Elite (SM8750); Dimensity 9600 Pro (MT6995), 9500 (MT6993), 9400+ (MT6991), 9300+ (MT6989).
- Device dan SoC bisa dipilih terpisah; yang tidak dipilih tetap asli.

## Yang diubah
`ro.product.{model,brand,manufacturer,marketname}` di semua partisi (utama, system, system_ext, vendor, odm, product, bootimage) dan `ro.soc.model` / `ro.soc.manufacturer`.

Juga `ro.hardware.chipname` (nama chip). Sengaja **tidak** diubah: `ro.product.device/name` dan fingerprint.

## Mode dalam (agar terbaca di DevCheck)
Aplikasi seperti DevCheck mengenali chip dari `ro.board.platform` dan `ro.hardware`, bukan dari `ro.soc.model`. Bila **Mode dalam** aktif, properti ini ikut diubah: `ro.board.platform`, `ro.hardware`, `ro.product.board`, serta `ro.mediatek.platform` / `ro.vendor.mediatek.platform` untuk SoC MediaTek.

- Hanya berlaku sampai restart. Tidak ditulis ke module boot, jadi boot tidak pernah memakai nilai palsu; terapkan lagi setelah boot.
- Dipakai sistem untuk memuat driver GPU/HAL, jadi ada risiko kecil driver atau kamera gagal dimuat di proses yang baru dimulai. Restart memulihkan.
- Hanya tersedia untuk SoC yang kode platformnya diketahui: Snapdragon 8 Elite (`sun`), 8 Elite Gen 5 (`canoe`), Dimensity 9600 Pro / 9500 / 9400+ / 9300+ (`mt6995` / `mt6993` / `mt6991` / `mt6989`).
- **Soft restart** (restart zygote) membuat aplikasi membaca nilai baru tanpa reboot penuh, termasuk `Build.HARDWARE` yang di-cache zygote.

## Cara kerja
1. Nilai asli disimpan di `/data/adb/senza_fakedevice/orig.conf` (sekali per properti).
2. Properti ditimpa dengan `resetprop -n`, lalu dibaca ulang lewat `getprop`. Yang gagal dilaporkan.
3. Bila **Tetap setelah restart** aktif, dipasang module `/data/adb/modules/senza_fakedevice` (`system.prop` + `post-fs-data.sh`).
4. **Kembalikan ke asli** memulihkan semua properti dari `orig.conf` dan menghapus module.

Restart perangkat setelah menerapkan: aplikasi membaca `Build.MODEL` saat prosesnya mulai.

## Batas
- Hanya nama yang berubah; performa CPU/GPU asli tetap.
- Detail inti CPU (jenis dan jumlah core), GPU, dan `/proc/cpuinfo` dibaca dari kernel dan tidak bisa dipalsukan lewat properti.
- DevCheck memakai database chipnya sendiri; chip yang sangat baru bisa tampil Unknown.
- Game yang membaca GL_RENDERER atau `/proc/cpuinfo` tetap melihat perangkat asli.
- Aplikasi bank / Play Integrity bisa menolak perangkat yang modelnya diubah.
