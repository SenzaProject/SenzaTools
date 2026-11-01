# Fake Device

Fitur di **Toolkit → Fake Device** untuk menyamarkan model perangkat dan SoC agar game membuka opsi grafis (kualitas, FPS) yang biasanya hanya untuk HP tertentu. Butuh **root** dan **Magisk / KernelSU / APatch** (memakai `resetprop`).

## Pilihan
- **10 device kelas atas**: Samsung Galaxy S26 Ultra (SM-S948B), ASUS ROG Phone 9 Pro (ASUS_AI2501), RedMagic 11 Pro (NX809J), OnePlus 13 (CPH2655), Xiaomi 15 Ultra (25010PN30G), vivo X500 Pro Max (V2610), Google Pixel 10 Pro XL, OPPO Find X8 Pro (CPH2659), HONOR Magic7 Pro (PTP-AN10), realme GT 7 Pro (RMX5010).
- **SoC**: Snapdragon 8 Elite Extreme Gen 6 (SM8975), 8 Elite Gen 6 (SM8950), 8 Elite Gen 5 (SM8850), 8 Elite (SM8750); Dimensity 9600 Pro (MT6995), 9500 (MT6993), 9400+ (MT6991), 9300+ (MT6989).
- Device dan SoC bisa dipilih terpisah; yang tidak dipilih tetap asli.

## Yang diubah
`ro.product.{model,brand,manufacturer,marketname}` di semua partisi (utama, system, system_ext, vendor, odm, product, bootimage) dan `ro.soc.model` / `ro.soc.manufacturer`.

Juga `ro.hardware.chipname` (nama chip). Secara bawaan **tidak** diubah: `ro.product.device/name` dan fingerprint (lihat Mode game).

## Mode dalam (agar terbaca di DevCheck)
Aplikasi seperti DevCheck mengenali chip dari `ro.board.platform` dan `ro.hardware`, bukan dari `ro.soc.model`. Bila **Mode dalam** aktif, properti ini ikut diubah: `ro.board.platform`, `ro.hardware`, `ro.product.board`, serta `ro.mediatek.platform` / `ro.vendor.mediatek.platform` untuk SoC MediaTek.

- Hanya berlaku sampai restart. Tidak ditulis ke module boot, jadi boot tidak pernah memakai nilai palsu; terapkan lagi setelah boot.
- Dipakai sistem untuk memuat driver GPU/HAL, jadi ada risiko kecil driver atau kamera gagal dimuat di proses yang baru dimulai. Restart memulihkan.
- Hanya tersedia untuk SoC yang kode platformnya diketahui: Snapdragon 8 Elite (`sun`), 8 Elite Gen 5 (`canoe`), Dimensity 9600 Pro / 9500 / 9400+ / 9300+ (`mt6995` / `mt6993` / `mt6991` / `mt6989`).
- **Soft restart** (restart zygote) membuat aplikasi membaca nilai baru tanpa reboot penuh, termasuk `Build.HARDWARE` yang di-cache zygote.

## HP Transsion (Infinix / Tecno / itel)
Di HP Transsion nama chip di Pengaturan dan DevCheck dibaca dari berkas sistem, jadi properti saja tidak cukup. Kartu **Transsion** muncul otomatis dan menulis SoC yang dipilih ke berkas yang sesuai versi Android:

- **Android 14–15**: `/product/etc/build.prop` dengan kunci `ro.hardware.chipname`, `ro.chipname`, `ro.soc.manufacturer`, `ro.soc.model`, `ro.vendor.soc.model.external_name`, `ro.vendor.soc.model.part_name`.
- **Android 16+**: berkas `cpu_info`, isinya nama chip ditulis langsung (mis. `Dimensity 9600 Pro`). Dicari di `/tr_product/etc/asset/transettings/cpu_info` dan `/system/tr_product/etc/asset/transettings/cpu_info`; bila keduanya satu berkas (symlink), hanya diubah sekali. Bila aslinya memakai awalan vendor (`MediaTek ...`), awalan itu dipertahankan.

Berkas diganti lewat Editor File Root (bind-mount, tanpa Magisk, berkas asli tidak ditimpa, tetap aktif setelah restart). Salinan asli disimpan, **Pulihkan berkas** (atau Kembalikan ke asli) melepasnya. Restart setelah menerapkan atau memulihkan.

## Mode game (opsional)
Banyak game memilih profil grafis dari `Build.DEVICE` / `Build.PRODUCT` atau fingerprint, bukan `Build.MODEL`. **Mode game** mengubah `ro.product.device` dan `ro.product.name` di semua partisi. Hanya tersedia untuk device yang kode aslinya diketahui dan terverifikasi: OnePlus 13 (`OP5D55L1` / `CPH2655`) dan Pixel 10 Pro XL (`mustang`). Untuk device lain Mode game dilewati, tidak ada nilai yang dikarang. Tambahkan `codename` (dan `productName`) di `FakeDeviceEngine.DEVICES` bila sudah punya nilai aslinya.

**Ubah fingerprint juga** (hanya aktif bila Mode game aktif) mengganti tiga bagian depan fingerprint asli HP-mu (`merek/nama/device`); versi Android, build id, dan tags tetap milik perangkatmu. Dihitung di perangkat dari nilai asli di `orig.conf`, jadi menerapkan ulang tidak menumpuk nilai palsu.

Risiko, makanya default mati:
- Fingerprint berubah = Android menganggap ada pembaruan sistem. Boot berikutnya dan boot setelah **Kembalikan ke asli** bisa beberapa menit karena aplikasi dioptimalkan ulang.
- Play Integrity / aplikasi bank hampir pasti menolak.
- Device/name dipakai sebagian skrip vendor. Bila boot bermasalah: safe mode Magisk, atau hapus `/data/adb/modules/senza_fakedevice`.
- Berbeda dengan Mode dalam, Mode game ikut **Tetap setelah restart**, karena Build.DEVICE harus sudah terisi sebelum zygote mulai.

## Reset konfigurasi game
Game menyimpan penilaian perangkat di datanya sendiri, jadi fake device baru terbaca setelah itu dibuang. Kartu **Reset konfigurasi game** menutup paksa game lalu menghapus `cache`, `code_cache`, dan (opsional) `shared_prefs` serta folder `Saved/Config` (Unreal Engine). OBB dan `Android/data/<paket>/files` tidak disentuh. Urutan: Terapkan, Restart, Reset game, buka game. Lokasi penyimpanan penilaian berbeda per game; bila satu game tetap membaca perangkat lama, lokasinya perlu ditambahkan.

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
