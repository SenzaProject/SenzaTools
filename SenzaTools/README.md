# Senza Tools — Android & System Toolkit

**Intelligent Android. Smarter System Control.**
Designed & developed by **SenzaProject**.

Aplikasi Android (Kotlin + Jetpack Compose, gaya Liquid Glass) untuk menganalisis perangkat, sistem, APK, dan ROM, dengan tiga tingkat akses (Normal / ADB-Shizuku / Root).

## Build APK

### Cara 1 — GitHub Actions (disarankan, cocok dengan alur Termux)
1. Push repo ke GitHub (lihat bagian Termux di bawah).
2. Buka tab **Actions → Build APK**. Workflow berjalan otomatis setiap push ke `main`, atau jalankan manual (*Run workflow*).
3. Setelah selesai, unduh artifact **SenzaTools-debug-apk** atau **SenzaTools-release-apk**.

### Cara 2 — Android Studio / PC
```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # app/build/outputs/apk/release/
```
Butuh JDK 17 dan Android SDK 35. Build lokal tidak memiliki nomor build CI, jadi pemeriksaan pembaruan dilewati.

### Tanda tangan
Semua build (debug dan release) ditandatangani `app/senza-release.jks` yang ikut di repo, supaya APK dari run Actions yang berbeda bisa dipasang di atas satu sama lain. Repo terbuka berarti kunci itu juga terbuka; untuk distribusi publik, buat keystore pribadi dan berikan lewat environment `SENZA_KEYSTORE`, `SENZA_STORE_PASSWORD`, `SENZA_KEY_ALIAS`, `SENZA_KEY_PASSWORD`.

## Pembaruan otomatis (seperti OTA)
Tiap build CI menanamkan nomor run Actions. Saat aplikasi dibuka, nomor itu dibandingkan dengan run sukses terbaru di branch `main`. Bila ada yang lebih baru, aplikasi hanya menampilkan layar **Pembaruan wajib** yang mengarah ke halaman run tersebut (unduh artifact `SenzaTools-release-apk`, lalu pasang di atasnya). Tidak perlu mengedit file apa pun saat merilis: cukup push ke `main` dan tunggu build sukses.

- Mengunduh artifact Actions memerlukan login GitHub di browser.
- Offline / rate limit GitHub: aplikasi tetap bisa dipakai kecuali update sudah pernah terdeteksi sebelumnya.

### Push dari Termux
```bash
cd SenzaTools
git init -b main
git add .
git commit -m "Senza Tools"
git remote add origin https://github.com/SenzaProject/SenzaTools.git
git push -u origin main
```
Gunakan Personal Access Token GitHub sebagai password.

## Persyaratan
- Android 12+ (minSdk 31), target/compile SDK 35.
- Uji pada Android 12–16 menjadi target; kompatibilitas aktual bergantung vendor dan tingkat akses.

## Dokumentasi
- [docs/FEATURES.md](docs/FEATURES.md) — fitur dan batasannya
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — struktur dan keputusan desain
- [docs/SECURITY.md](docs/SECURITY.md) — model keamanan dan privasi


## Terapkan edit file
Lihat `PATCH_TERAPKAN_EDIT.md` untuk mode Otomatis/Langsung/Bind-mount/Module Magisk, persistensi tanpa Magisk, dan persen kesehatan baterai.
