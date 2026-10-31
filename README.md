# Senza AI — Android & System Assistant

**Intelligent Android. Smarter System Control.**
Designed & developed by **SenzaProject**.

Aplikasi Android (Kotlin + Jetpack Compose, gaya Liquid Glass) untuk menganalisis perangkat, sistem, APK, dan ROM, dengan tiga tingkat akses (Normal / ADB-Shizuku / Root) dan asisten AI.

## Build APK

### Cara 1 — GitHub Actions (disarankan, cocok dengan alur Termux)
1. Push repo ke GitHub (lihat bagian Termux di bawah).
2. Buka tab **Actions → Build APK**. Workflow berjalan otomatis setiap push, atau jalankan manual (*Run workflow*).
3. Setelah selesai, unduh artifact **SenzaAI-debug-apk** atau **SenzaAI-release-apk**.

### Cara 2 — Android Studio / PC
```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/
./gradlew assembleRelease    # app/build/outputs/apk/release/
```
Butuh JDK 17 dan Android SDK 35.

> APK release ditandatangani dengan debug key agar bisa langsung dipasang untuk pengujian. Untuk distribusi publik, ganti dengan keystore milikmu sendiri di `app/build.gradle.kts`.

### Push dari Termux
```bash
cd SenzaAI
git init -b main
git add .
git commit -m "Initial commit: Senza AI"
git remote add origin https://github.com/SenzaProject/SenzaAI.git
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
