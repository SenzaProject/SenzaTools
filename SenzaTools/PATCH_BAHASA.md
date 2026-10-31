# Patch: pilihan bahasa (Senza Tools)

Ekstrak ke root proyek (timpa file yang sama). BELUM dikompilasi (tidak ada Android SDK di sini), jadi build dulu dan kirim error-nya kalau ada. Tanpa dependensi baru.

## Isi patch
- `i18n/Lang.kt` (baru): mesin bahasa. Teks di kode tetap bahasa Indonesia; terjemahan dibaca dari `assets/lang/<kode>.json`.
- `ui/components/LanguageDialog.kt` (baru): dialog pemilih bahasa.
- `ui/screens/SettingsScreen.kt`: kartu **Bahasa** di bagian atas Settings.
- `ui/components/Glass.kt`, `LiquidGlass.kt`, `FloatingNav.kt`: semua teks lewat `tr()` supaya seluruh UI ikut berganti.
- `SenzaApp.kt`: `Lang.init(this)`.
- `assets/lang/`: en (lengkap, ±990 teks), ms, es, fr, de, pt, ru, tr, ja, ko, zh, hi, ar (teks inti ±65).
- `docs/FEATURES.md`: catatan fitur bahasa.

## Cara kerja
- Bahasa berganti langsung (Compose state), tanpa restart.
- Urutan cadangan: bahasa terpilih → Inggris → teks Indonesia asli (Melayu → Indonesia).
- Teks bervariabel memakai `{}`, mis. `"Disalin: {}": "Copied: {}"`.

## Menambah bahasa baru (bisa lewat MT Manager)
1. Salin `assets/lang/en.json` menjadi `assets/lang/xx.json` (xx = kode bahasa, mis. `jv`).
2. Ubah `"@name"` menjadi nama bahasa, lalu terjemahkan nilainya (kunci jangan diubah).
3. Build. Bahasa baru muncul otomatis di daftar; teks yang kosong jatuh ke Inggris.

## Batasan
- Bahasa selain Inggris baru memuat teks inti; sisanya tampil Inggris.
- Teks yang disusun dari variabel (isi log, nama paket, hasil perintah) tidak diterjemahkan.
- Penjelasan properti sistem dan beberapa pesan analisis tampil sesuai kamus; yang tidak ada di kamus tetap Indonesia.
- Tata letak tetap kiri-ke-kanan, termasuk untuk Arab (teksnya sendiri tetap tampil benar).
- Nama aplikasi (`app_name`) tidak berubah per bahasa.
